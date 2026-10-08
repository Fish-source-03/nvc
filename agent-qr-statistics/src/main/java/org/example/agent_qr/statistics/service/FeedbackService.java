package org.example.agent_qr.statistics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.rag.entity.Conversation;
import org.example.agent_qr.rag.entity.Message;
import org.example.agent_qr.rag.mapper.ConversationMapper;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.statistics.entity.DailyStats;
import org.example.agent_qr.statistics.mapper.DailyStatsMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 满意度反馈服务（P2 新增）。
 * <p>
 * 处理用户对 AI 回答的点赞/点踩反馈，
 * 更新消息记录和每日统计数据。
 * </p>
 * <p>
 * <b>R48（批次 11）：消息归属校验</b>。修复前 {@code userId} 仅用于日志——
 * <b>任何已登录用户可对任意 messageId 提交点赞/点踩</b>，直接影响
 * {@code stat_daily.positive_count/negative_count}（满意率指标的分子），
 * 即指标可被任意用户污染。现在提交前校验"该消息所属会话的 owner = 当前用户"，
 * 不满足即拒绝且不产生任何写入。
 * </p>
 * <p>
 * <b>为何 admin 不例外</b>：满意度是"消息作者本人的体验信号"，代他人点赞/点踩
 * 在语义上就是污染（正是本缺陷的形态）；仪表盘对 admin 是<b>只读</b>，
 * 不存在"需要以他人身份提交反馈"的运维场景，故不做 admin 直通——
 * 少一条例外就少一条可被滥用的路径。
 * </p>
 * <p>
 * <b>R53（遗留项）：满意率计数静默丢失</b>。修复前计数走
 * {@code UPDATE stat_daily … WHERE stat_date = ?}（无 upsert），
 * <b>当天尚无任何问答</b>（表无今日行）时 UPDATE 影响 0 行且不报错 → 计数静默丢失；
 * 现对齐问答路径 {@code StatisticsUpdateListener} 的"<b>不存在则建行</b>"口径，
 * 详见 {@link #incrementFeedbackCount(LocalDate, boolean)}。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final MessageMapper messageMapper;
    private final DailyStatsMapper dailyStatsMapper;
    private final ConversationMapper conversationMapper;

    /**
     * 提交满意度反馈。
     *
     * @param messageId 消息 ID
     * @param feedback  反馈类型：positive / negative
     * @param reason    反馈原因（可选）
     * @param userId    用户 ID（当前认证用户；R48 起参与归属校验）
     * @throws BusinessException 消息不存在 / 非 AI 回答 / 消息不属于当前用户
     */
    @Transactional
    public void submitFeedback(Long messageId, String feedback, String reason, Long userId) {
        // 1. 校验消息存在且为 AI 回答
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BusinessException("消息不存在");
        }
        if (!"assistant".equals(message.getRole())) {
            throw new BusinessException("只能对 AI 回答进行反馈");
        }

        // 1.5 ★ R48：消息归属校验（fail-closed）——
        //     ownership 取自会话 owner（chat_message 无 user_id 列），
        //     会话缺失 / userId 缺失 / 不匹配一律拒绝，避免污染满意率指标。
        Conversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null || !Objects.equals(conversation.getUserId(), userId)) {
            log.warn("满意度反馈被拒（消息不属于当前用户）: messageId={}, userId={}", messageId, userId);
            throw new BusinessException(403, "无权对他人的消息提交反馈");
        }

        // 2. 更新消息反馈
        messageMapper.updateFeedback(messageId, feedback, reason);

        // 3. 更新每日统计
        LocalDate today = LocalDate.now();
        if ("positive".equals(feedback)) {
            incrementFeedbackCount(today, true);
        } else if ("negative".equals(feedback)) {
            incrementFeedbackCount(today, false);
        }

        log.info("满意度反馈已提交: messageId={}, feedback={}, userId={}", messageId, feedback, userId);
    }

    /**
     * 当日满意度计数 +1；<b>当天尚无统计行时先建行</b>（★ R53）。
     * <p>
     * 修复前这里直接调用 {@code incrementPositiveCount/…NegativeCount}，其 SQL 是
     * {@code UPDATE stat_daily … WHERE stat_date = ?}——<b>没有 upsert</b>。
     * 当"当天尚无任何问答"（{@code stat_daily} 无今日行）时，UPDATE 影响 0 行、
     * 且不报错，<b>反馈计数静默丢失</b>（实测：提交返回 200，但表无今日行、无任何变化），
     * 仪表盘的满意率指标因此失真。
     * </p>
     * <p>
     * 现按问答路径 {@code StatisticsUpdateListener} 的既有口径对齐——
     * "不存在则建行"：{@code selectByDate} 为 null 时 {@code insert} 一条以本次反馈
     * 计数为 1 的今日行，否则对已有行累加。两条写入 {@code stat_daily} 的路径
     * （问答 / 反馈）自此语义一致，不再出现"一条会建行、另一条不会"的错位。
     * </p>
     * <p>
     * <b>为何不改成 upsert</b>：{@code INSERT … ON DUPLICATE KEY UPDATE} 需要与本路径的
     * "对照路径"（{@code StatisticsUpdateListener}）一起改才谈得上口径统一，而后者不在本次
     * 改动范围内；只给反馈路径换写法，会把两条路径的差异从"行为"挪到"实现"，收益有限。
     * </p>
     * <p>
     * <b>已知残留</b>：与问答路径同一形态——同一天的<b>首次</b>写入若被并发触发，
     * 两方可能同时建行，其中一方将因 {@code stat_date} 唯一键冲突失败（事务回滚、
     * 计数不丢但请求报错）。这是既有形态，本次不引入新风险；如需根治，
     * 应连同 {@code StatisticsUpdateListener} 一起改为 upsert。
     * </p>
     *
     * @param date     统计日期（调用方固定传"今天"）
     * @param positive true=点赞，false=点踩
     */
    private void incrementFeedbackCount(LocalDate date, boolean positive) {
        DailyStats existing = dailyStatsMapper.selectByDate(date);
        if (existing == null) {
            DailyStats newStats = new DailyStats();
            newStats.setStatDate(date);
            if (positive) {
                newStats.setPositiveCount(1);
            } else {
                newStats.setNegativeCount(1);
            }
            dailyStatsMapper.insert(newStats);
            log.info("创建今日统计记录并设置反馈计数为 1: date={}, positive={}", date, positive);
        } else if (positive) {
            dailyStatsMapper.incrementPositiveCount(date);
        } else {
            dailyStatsMapper.incrementNegativeCount(date);
        }
    }
}
