package org.example.agent_qr.web.scheduler;

import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.DlqMessageMapper;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.compensation.entity.DeleteTask;
import org.example.agent_qr.compensation.mapper.DeleteTaskMapper;
import org.example.agent_qr.compensation.service.DocumentDeleteServiceV2;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DELETE 死信重放的<b>退避收敛</b>测试（批次 08 · 8.5，风险 R1 —— 无界环路）。
 * <p>
 * <b>被拦截的环路</b>：原 {@code retryDelete} 调用
 * {@code DocumentDeleteServiceV2.asyncPhysicalDelete}（{@code @Async} fire-and-forget），
 * 随后<b>无条件</b>把当前消息标记成功（即删除队列记录）；而 {@code asyncPhysicalDelete}
 * 失败时会<b>自行入队</b>一条新死信（{@code retryCount} 归零）。二者叠加 →
 * "当前消息被吞掉 + 新消息无限产生 + 退避/DEAD 分支永不执行"。
 * 只要"MySQL 删成功 + Chroma 持续删失败"（问题 29 的目标场景）或
 * "部署中无 ChromaRetriever"（8.2 把它改成了显式失败，属正确改动但会持续触发）存在，
 * 每 30 秒就新增一条死信且永不到 DEAD。
 * </p>
 * <p>
 * 本测试用<b>真实的</b> {@link DeadLetterQueue}（而非 mock）驱动完整机制：
 * 连续 4 轮重放（每次失败后把 {@code nextRetryAt} 拨到过去，模拟退避时间流逝），
 * 断言：① <b>从未产生新死信</b>；② 重试次数按 1→2→3 递增、退避 <b>9s/27s/81s</b>
 * （R31 口径：入队首跳为 3s，<b>重试路径</b>从 9s 起——原类注释误写为 3s/9s/27s，
 * 与下方断言自相矛盾，批次 11 · 任务 11.4.3 已统一）；
 * ③ 第 4 轮达到 max-retries 后转 {@code DEAD}；④ 当前消息从未被当作成功删除。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DlqDeleteReplayBackoffTest {

    private static final Long MSG_ID = 77L;
    private static final Long DOCUMENT_ID = 900L;

    @Mock
    private DlqMessageMapper dlqMessageMapper;

    @Mock
    private DeleteTaskMapper deleteTaskMapper;

    @Mock
    private ChromaRetriever chromaRetriever;

    /** 被重放的真实对象：真实的补偿服务 + 真实的 DLQ（机制不被 mock 掉） */
    private DocumentDeleteServiceV2 documentDeleteServiceV2;
    private DeadLetterQueue deadLetterQueue;
    private DlqRetryScheduler scheduler;

    /** 内存中的死信行（模拟 dlq_message 表） */
    private final AtomicReference<DlqMessage> row = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        // ---------- 真实的 DeadLetterQueue（退避与 DEAD 判定用真实实现） ----------
        deadLetterQueue = new DeadLetterQueue();
        ReflectionTestUtils.setField(deadLetterQueue, "dlqMessageMapper", dlqMessageMapper);
        ReflectionTestUtils.setField(deadLetterQueue, "maxRetries", 4);
        ReflectionTestUtils.setField(deadLetterQueue, "backoffBase", 3);

        // ---------- 真实的 DocumentDeleteServiceV2（Chroma 用"持续失败"的桩） ----------
        documentDeleteServiceV2 = new DocumentDeleteServiceV2();
        ReflectionTestUtils.setField(documentDeleteServiceV2, "chromaRetriever", chromaRetriever);
        ReflectionTestUtils.setField(documentDeleteServiceV2, "deleteTaskMapper", deleteTaskMapper);
        ReflectionTestUtils.setField(documentDeleteServiceV2, "deadLetterQueue", deadLetterQueue);
        doThrow(new RuntimeException("ChromaDB 持续不可达"))
                .when(chromaRetriever).deleteByIds(any());
        doAnswer(invocation -> {
            DeleteTask task = invocation.getArgument(0);
            task.setId(1L);
            return 1;
        }).when(deleteTaskMapper).insert(any(DeleteTask.class));
        when(deleteTaskMapper.selectByDocumentId(anyLong())).thenReturn(List.of());

        // ---------- 待重放的消息行 ----------
        DlqMessage msg = new DlqMessage();
        msg.setId(MSG_ID);
        msg.setEventType(DlqMessage.EVENT_DELETE);
        msg.setDocumentId(DOCUMENT_ID);
        msg.setPayload("{\"documentId\":900,\"chromaIds\":\"vec-1,vec-2\"}");
        msg.setStatus(DlqMessage.STATUS_PENDING);
        msg.setRetryCount(0);
        msg.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        row.set(msg);

        when(dlqMessageMapper.selectById(MSG_ID)).thenAnswer(invocation -> row.get());
        when(dlqMessageMapper.selectPendingRetries(any(LocalDateTime.class)))
                .thenAnswer(invocation -> DlqMessage.STATUS_PENDING.equals(row.get().getStatus())
                        && row.get().getNextRetryAt() != null
                        && !row.get().getNextRetryAt().isAfter((LocalDateTime) invocation.getArgument(0))
                        ? List.of(row.get()) : List.of());
        doAnswer(invocation -> {
            row.get().setRetryCount(invocation.getArgument(1));
            row.get().setNextRetryAt(invocation.getArgument(2));
            return 1;
        }).when(dlqMessageMapper).updateRetry(anyLong(), anyInt(), any(LocalDateTime.class), anyString());
        doAnswer(invocation -> {
            row.get().setStatus(invocation.getArgument(1));
            return 1;
        }).when(dlqMessageMapper).updateStatus(anyLong(), anyString(), anyString());

        // ---------- 调度器 ----------
        scheduler = new DlqRetryScheduler();
        ReflectionTestUtils.setField(scheduler, "deadLetterQueue", deadLetterQueue);
        ReflectionTestUtils.setField(scheduler, "dlqMessageMapper", dlqMessageMapper);
        ReflectionTestUtils.setField(scheduler, "documentDeleteServiceV2", documentDeleteServiceV2);
        ReflectionTestUtils.setField(scheduler, "deleteTaskMapper", deleteTaskMapper);
        ReflectionTestUtils.setField(scheduler, "fileStorageService", null);
        ReflectionTestUtils.setField(scheduler, "chunkMapper", null);
        ReflectionTestUtils.setField(scheduler, "documentMapper", null);
    }

    @Test
    @DisplayName("★ R1：Chroma 持续失败时，重放不产生新死信、按退避推进、max-retries 后转 DEAD")
    void deleteReplay_shouldBackOffAndReachDead_withoutProducingNewDeadLetters() {
        clearInvocations(dlqMessageMapper);

        // 第 1~3 轮：既有实现 calcBackoffSeconds(newRetryCount) = 3^(newRetryCount+1)
        // → 9s / 27s / 81s（批次 01 的退避实现，本批次不改其公式）；
        // 每轮结束后把 nextRetryAt 拨到过去，模拟退避时间流逝。
        for (int round = 1; round <= 3; round++) {
            scheduler.retryDeadLetters();

            assertThat(row.get().getRetryCount())
                    .as("第 %d 轮后重试次数应递增", round)
                    .isEqualTo(round);
            assertThat(row.get().getStatus())
                    .as("未达上限前仍是 PENDING，等待退避重试")
                    .isEqualTo(DlqMessage.STATUS_PENDING);
            long expectedBackoff = (long) Math.pow(3, round + 1);
            long backoff = Duration.between(LocalDateTime.now(), row.get().getNextRetryAt()).getSeconds();
            assertThat(backoff)
                    .as("退避应为 3^%d 秒（既有指数退避机制未被绕过）", round + 1)
                    .isBetween(expectedBackoff - 2, expectedBackoff + 2);

            row.get().setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        }

        // 第 4 轮：达到 maxRetries(4) → 转 DEAD，链路收敛
        scheduler.retryDeadLetters();
        assertThat(row.get().getStatus()).isEqualTo(DlqMessage.STATUS_DEAD);

        // ① 从未产生新死信（修复前每轮都会新增一条 retryCount=0 的 DELETE 死信）
        verify(dlqMessageMapper, never()).insert(any(DlqMessage.class));
        // ④ 当前消息从未被当作成功删除（修复前 updateRetryResult(true) 会 deleteById）
        verify(dlqMessageMapper, never()).deleteById(anyLong());
        // 失败落库仍可见（delete_task 每次尝试都留痕）
        verify(deleteTaskMapper, org.mockito.Mockito.atLeast(4))
                .updateStatus(anyLong(), eq(DeleteTask.STATUS_FAILED));
    }

    @Test
    @DisplayName("★ 收敛后的 DEAD 消息不再被扫描（避免无效重放刷日志）")
    void deadMessage_shouldNotBePickedUpAgain() {
        for (int i = 0; i < 4; i++) {
            scheduler.retryDeadLetters();
            row.get().setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        }
        assertThat(row.get().getStatus()).isEqualTo(DlqMessage.STATUS_DEAD);

        clearInvocations(deleteTaskMapper);
        scheduler.retryDeadLetters();

        verify(deleteTaskMapper, never()).insert(any(DeleteTask.class));
    }

    @Test
    @DisplayName("★ 删除成功后：当前消息被确认删除（成功路径仍按原语义收敛）")
    void deleteReplay_shouldConfirmAndDeleteMessage_whenDeleteSucceeds() {
        doAnswer(invocation -> 2).when(chromaRetriever).deleteByIds(any());
        clearInvocations(dlqMessageMapper);

        scheduler.retryDeadLetters();

        verify(dlqMessageMapper).deleteById(MSG_ID);
        verify(dlqMessageMapper, never()).insert(any(DlqMessage.class));
    }
}
