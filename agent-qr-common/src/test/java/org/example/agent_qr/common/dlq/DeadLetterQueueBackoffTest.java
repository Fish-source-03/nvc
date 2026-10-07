package org.example.agent_qr.common.dlq;

import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 死信退避口径测试（批次 11 · 任务 11.4.3，风险 R31 —— 退避 off-by-one 与注释矛盾）。
 * <p>
 * <b>被拦截的问题</b>：类注释曾写"3^1=3s → 3^2=9s → 3^3=27s → 3^4=81s"，
 * 读起来像"首次重试等 3s"，但 {@code updateRetryResult} 传入的是<b>递增后的 1 基</b>
 * {@code newRetryCount}——首次重试实际等 <b>9s</b>（{@code calcBackoffSeconds(0)=3s}
 * 只出现在入队路径）。{@code DlqDeleteReplayBackoffTest} 的类注释（3s/9s/27s）
 * 与其实断言（9s/27s/81s）也因此自相矛盾。
 * </p>
 * <p>
 * 本测试锁定修正后的<b>统一口径</b>：入队首跳 3s（走 {@code calcBackoffSeconds(0)}，
 * 与重试路径同一公式），重试退避 9s → 27s → 81s，第 max-retries 次失败转 DEAD。
 * <b>运行时行为与修正前完全一致</b>（本次只统一公式与注释）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeadLetterQueueBackoffTest {

    @Mock
    private DlqMessageMapper dlqMessageMapper;

    private DeadLetterQueue deadLetterQueue;

    private final AtomicReference<DlqMessage> row = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        deadLetterQueue = new DeadLetterQueue();
        ReflectionTestUtils.setField(deadLetterQueue, "dlqMessageMapper", dlqMessageMapper);
        ReflectionTestUtils.setField(deadLetterQueue, "maxRetries", 4);
        ReflectionTestUtils.setField(deadLetterQueue, "backoffBase", 3);

        when(dlqMessageMapper.selectById(anyLong())).thenAnswer(invocation -> row.get());
        doAnswer(invocation -> {
            row.get().setRetryCount(invocation.getArgument(1));
            row.get().setNextRetryAt(invocation.getArgument(2));
            return 1;
        }).when(dlqMessageMapper).updateRetry(anyLong(), anyInt(), any(LocalDateTime.class), anyString());
        doAnswer(invocation -> {
            row.get().setStatus(invocation.getArgument(1));
            return 1;
        }).when(dlqMessageMapper).updateStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("★ R31：退避公式 = base^(retryCount+1)；入队首跳 3s、重试路径 9s/27s/81s（口径唯一）")
    void calcBackoffSeconds_shouldHaveSingleDocumentedScale() {
        assertThat(deadLetterQueue.calcBackoffSeconds(0))
                .as("入队首跳：enqueue 也走同一公式（修正前该值在重试路径是死值）")
                .isEqualTo(3L);
        assertThat(deadLetterQueue.calcBackoffSeconds(1)).isEqualTo(9L);
        assertThat(deadLetterQueue.calcBackoffSeconds(2)).isEqualTo(27L);
        assertThat(deadLetterQueue.calcBackoffSeconds(3)).isEqualTo(81L);
    }

    @Test
    @DisplayName("★ R31：enqueue 写入的首跳等待为 3s（与 calcBackoffSeconds(0) 同源）")
    void enqueue_shouldScheduleFirstRetryAfterBaseSeconds() {
        deadLetterQueue.enqueue(DlqMessage.EVENT_CHUNK, 9L, "{\"documentId\":9}", new RuntimeException("boom"));

        org.mockito.ArgumentCaptor<DlqMessage> captor = org.mockito.ArgumentCaptor.forClass(DlqMessage.class);
        org.mockito.Mockito.verify(dlqMessageMapper).insert(captor.capture());
        DlqMessage inserted = captor.getValue();
        assertThat(inserted.getRetryCount()).isZero();
        assertThat(Duration.between(LocalDateTime.now(), inserted.getNextRetryAt()).getSeconds())
                .as("入队首跳 = backoffBase = 3s")
                .isBetween(2L, 4L);
    }

    @Test
    @DisplayName("★ R31：首次重试失败的退避是 9s（不是注释曾暗示的 3s），随后 27s、末次直接 DEAD")
    void updateRetryResult_shouldBackOffFromNineSeconds_andDieAtMaxRetries() {
        DlqMessage msg = new DlqMessage();
        msg.setId(1L);
        msg.setEventType(DlqMessage.EVENT_CHUNK);
        msg.setDocumentId(9L);
        msg.setRetryCount(0);
        msg.setStatus(DlqMessage.STATUS_PENDING);
        row.set(msg);

        // 第 1 次重试失败 → retryCount=1，退避 3^2 = 9s
        deadLetterQueue.updateRetryResult(1L, false, new RuntimeException("失败"));
        assertThat(row.get().getRetryCount()).isEqualTo(1);
        assertThat(Duration.between(LocalDateTime.now(), row.get().getNextRetryAt()).getSeconds())
                .as("首次重试的退避是 9s（R31 实测口径）")
                .isBetween(8L, 10L);

        // 第 2 次 → 27s
        deadLetterQueue.updateRetryResult(1L, false, new RuntimeException("失败"));
        assertThat(Duration.between(LocalDateTime.now(), row.get().getNextRetryAt()).getSeconds())
                .isBetween(26L, 28L);

        // 第 3 次 → 81s
        deadLetterQueue.updateRetryResult(1L, false, new RuntimeException("失败"));
        assertThat(Duration.between(LocalDateTime.now(), row.get().getNextRetryAt()).getSeconds())
                .isBetween(80L, 82L);

        // 第 4 次 → 达到 max-retries，转 DEAD（不再计算退避）
        deadLetterQueue.updateRetryResult(1L, false, new RuntimeException("失败"));
        assertThat(row.get().getStatus()).isEqualTo(DlqMessage.STATUS_DEAD);
    }
}
