package org.example.agent_qr.statistics.service;

import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.rag.entity.Message;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.statistics.mapper.DailyStatsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FeedbackService} 测试（批次 11 · 任务 11.2.2）。
 * <p>
 * 满意度反馈是仪表盘"满意率"指标的<b>唯一写入方</b>（
 * {@code DailyStats.positiveCount/negativeCount} → {@code satisfactionRate}），
 * 且它同时写两张表（消息 + 每日统计）。用例锁定三组行为：
 * </p>
 * <ul>
 *   <li><b>校验前置</b>：消息不存在 / 对用户提问反馈 → 抛业务异常且<b>不产生任何写入</b>
 *       （否则会污染满意率统计，且无法撤回）；</li>
 *   <li><b>计数口径</b>：positive/negative 各自只增对应的那个计数器（不得互相串），
 *       未知取值不计入任何计数器；</li>
 *   <li><b>统计日期</b>：必须落在"今天"，否则指标永远读不到（仪表盘按当天查询）。</li>
 * </ul>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceTest {

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private DailyStatsMapper dailyStatsMapper;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(messageMapper, dailyStatsMapper);
    }

    private Message assistantMessage() {
        Message message = new Message();
        message.setId(100L);
        message.setRole("assistant");
        message.setContent("这是 AI 回答");
        return message;
    }

    // ==================== 校验前置 ====================

    @Test
    @DisplayName("★ 消息不存在时抛业务异常，且不写消息、不增统计")
    void submitFeedback_shouldReject_whenMessageMissing() {
        when(messageMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.submitFeedback(999L, "positive", null, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息不存在");

        verify(messageMapper, never()).updateFeedback(any(), any(), any());
        verifyNoInteractions(dailyStatsMapper);
    }

    @Test
    @DisplayName("★ 只能对 AI 回答反馈：对用户提问点赞被拒且不落库")
    void submitFeedback_shouldReject_whenTargetIsUserMessage() {
        Message userMessage = assistantMessage();
        userMessage.setRole("user");
        when(messageMapper.selectById(100L)).thenReturn(userMessage);

        assertThatThrownBy(() -> service.submitFeedback(100L, "positive", null, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能对 AI 回答进行反馈");

        verify(messageMapper, never()).updateFeedback(any(), any(), any());
        verifyNoInteractions(dailyStatsMapper);
    }

    // ==================== 计数口径 ====================

    @Test
    @DisplayName("★ 点赞：写消息反馈并只增当日正向计数")
    void submitFeedback_shouldIncrementPositiveOnly() {
        when(messageMapper.selectById(100L)).thenReturn(assistantMessage());

        service.submitFeedback(100L, "positive", "有帮助", 7L);

        verify(messageMapper).updateFeedback(100L, "positive", "有帮助");
        verify(dailyStatsMapper).incrementPositiveCount(LocalDate.now());
        verify(dailyStatsMapper, never()).incrementNegativeCount(any());
    }

    @Test
    @DisplayName("★ 点踩：写消息反馈并只增当日负向计数")
    void submitFeedback_shouldIncrementNegativeOnly() {
        when(messageMapper.selectById(100L)).thenReturn(assistantMessage());

        service.submitFeedback(100L, "negative", "答非所问", 7L);

        verify(messageMapper).updateFeedback(100L, "negative", "答非所问");
        verify(dailyStatsMapper).incrementNegativeCount(LocalDate.now());
        verify(dailyStatsMapper, never()).incrementPositiveCount(any());
    }

    @Test
    @DisplayName("未知反馈取值：不污染任何计数器（仅 positive/negative 计入满意率）")
    void submitFeedback_shouldNotCount_whenFeedbackValueUnknown() {
        when(messageMapper.selectById(100L)).thenReturn(assistantMessage());

        service.submitFeedback(100L, "maybe", null, 7L);

        verify(messageMapper).updateFeedback(eq(100L), eq("maybe"), eq(null));
        verify(dailyStatsMapper, never()).incrementPositiveCount(any());
        verify(dailyStatsMapper, never()).incrementNegativeCount(any());
    }
}
