package org.example.agent_qr.common.dlq;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 死信队列管理器，提供消息入队、重试结果更新和指数退避计算。
 * <p>
 * 重试策略（批次 11 · 任务 11.4.3 / R31 修正注释与公式的一致性，<b>运行时行为未变</b>）：
 * </p>
 * <ul>
 *   <li><b>入队首跳</b>：{@code enqueue} 后等待 {@code backoffBase} 秒（默认 3s，等价
 *       {@link #calcBackoffSeconds(int) calcBackoffSeconds(0)}）；</li>
 *   <li><b>重试退避</b>：第 n 次重试失败后等待 {@code backoffBase^(n+1)} 秒，
 *       即 <b>9s → 27s → 81s</b>（n = 1,2,3；默认 {@code max-retries=4}）；</li>
 *   <li><b>终止</b>：第 {@code max-retries}（默认 4）次失败后标记 {@code DEAD}，不再重试。</li>
 * </ul>
 * <p>
 * ⚠️ 修正前的类注释写"3s → 9s → 27s → 81s"，把入队首跳与重试退避混为一谈，
 * 且 81s 这一跳在默认配置下确实会发生（第 3 次重试失败后），但"首次重试等 3s"与实测不符
 * （实测首次重试等 9s）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class DeadLetterQueue {

    @Autowired
    private DlqMessageMapper dlqMessageMapper;

    /**
     * 最大重试次数，默认 4 次。
     */
    @Value("${agent-qr.dlq.max-retries:4}")
    private int maxRetries;

    /**
     * 退避基数，默认 3 秒。
     */
    @Value("${agent-qr.dlq.backoff-base:3}")
    private int backoffBase;

    /**
     * 将失败操作入队到死信队列。
     * <p>
     * 首次重试延迟 = {@code backoffBase} 秒（默认 3s，走 {@link #calcBackoffSeconds(int)}
     * 的 {@code retryCount=0} 分支——<b>首跳与后续退避同一公式</b>，R31）。
     * </p>
     *
     * @param eventType  事件类型（PARSE / CHUNK / EMBED / DELETE）
     * @param documentId 关联的文档 ID
     * @param payload    原始负载（JSON 格式）
     * @param error      异常信息
     */
    public void enqueue(String eventType, Long documentId, String payload, Throwable error) {
        DlqMessage msg = new DlqMessage();
        msg.setEventType(eventType);
        msg.setDocumentId(documentId);
        msg.setPayload(payload);
        msg.setErrorMsg(error != null ? error.getMessage() : "未知错误");
        msg.setRetryCount(0);
        msg.setNextRetryAt(LocalDateTime.now().plusSeconds(calcBackoffSeconds(0)));
        msg.setStatus(DlqMessage.STATUS_PENDING);
        msg.setCreateTime(LocalDateTime.now());

        dlqMessageMapper.insert(msg);
        log.warn("死信队列入队: eventType={}, documentId={}, msgId={}, nextRetryAt={}",
                eventType, documentId, msg.getId(), msg.getNextRetryAt());
    }

    /**
     * 更新重试结果。
     * <p>
     * 成功则删除记录，失败则递增重试次数并计算下次重试时间，
     * 超过最大重试次数标记为 DEAD。
     * </p>
     * <p>
     * 退避口径（R31）：失败时传入 {@link #calcBackoffSeconds(int)} 的是
     * <b>递增后的新重试次数</b>（1 基）——首次重试失败后等待 9s，随后 27s、81s；
     * 第 {@code max-retries} 次失败直接转 DEAD，不再计算退避。
     * </p>
     *
     * @param msgId  消息 ID
     * @param success 是否成功
     * @param error   失败时的异常（成功时为 null）
     */
    public void updateRetryResult(Long msgId, boolean success, Throwable error) {
        if (success) {
            dlqMessageMapper.deleteById(msgId);
            log.info("DLQ 重试成功，消息已删除: msgId={}", msgId);
            return;
        }

        DlqMessage msg = dlqMessageMapper.selectById(msgId);
        if (msg == null) {
            log.warn("DLQ 消息不存在: msgId={}", msgId);
            return;
        }

        int newRetryCount = msg.getRetryCount() + 1;
        if (newRetryCount >= maxRetries) {
            dlqMessageMapper.updateStatus(msgId, DlqMessage.STATUS_DEAD,
                    error != null ? error.getMessage() : "超过最大重试次数");
            log.error("DLQ 重试耗尽，标记为 DEAD: msgId={}, eventType={}, documentId={}, retryCount={}",
                    msgId, msg.getEventType(), msg.getDocumentId(), newRetryCount);
        } else {
            long backoffSeconds = calcBackoffSeconds(newRetryCount);
            LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
            dlqMessageMapper.updateRetry(msgId, newRetryCount, nextRetryAt,
                    error != null ? error.getMessage() : "重试失败");
            log.warn("DLQ 重试失败，将在 {} 秒后重试: msgId={}, retryCount={}, nextRetryAt={}",
                    backoffSeconds, msgId, newRetryCount, nextRetryAt);
        }
    }

    /**
     * 计算指数退避延迟秒数。
     * <p>
     * 公式：{@code backoffBase ^ (retryCount + 1)} 秒，参数是"<b>已计入的失败次数</b>"：
     * </p>
     * <ul>
     *   <li>{@code retryCount=0} —— 入队后的首跳等待 → 3s（由 {@link #enqueue} 使用）；</li>
     *   <li>{@code retryCount=1,2,3} —— 重试失败后的退避 → 9s / 27s / 81s
     *       （由 {@link #updateRetryResult} 传入 1 基的 {@code newRetryCount}）。</li>
     * </ul>
     * <p>
     * ⚠️ 修正前的注释写"3s → 9s → 27s → 81s"且未说明入参口径，容易被误读为"首次重试等 3s"；
     * 实际首次重试的退避是 9s（R31 实测）。
     * </p>
     *
     * @param retryCount 已计入的失败次数（0 = 尚未重试，即入队首跳）
     * @return 退避延迟秒数
     */
    public long calcBackoffSeconds(int retryCount) {
        return (long) Math.pow(backoffBase, retryCount + 1);
    }
}
