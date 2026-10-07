package org.example.agent_qr.statistics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.rag.entity.Conversation;
import org.example.agent_qr.rag.entity.Message;
import org.example.agent_qr.rag.mapper.ConversationMapper;
import org.example.agent_qr.rag.mapper.MessageMapper;
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
            dailyStatsMapper.incrementPositiveCount(today);
        } else if ("negative".equals(feedback)) {
            dailyStatsMapper.incrementNegativeCount(today);
        }

        log.info("满意度反馈已提交: messageId={}, feedback={}, userId={}", messageId, feedback, userId);
    }
}
