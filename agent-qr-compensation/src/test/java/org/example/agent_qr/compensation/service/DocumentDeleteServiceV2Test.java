package org.example.agent_qr.compensation.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.compensation.entity.DeleteTask;
import org.example.agent_qr.compensation.mapper.DeleteTaskMapper;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link DocumentDeleteServiceV2} 单元测试。
 * <ul>
 *   <li>批次 01 · 任务 1.1（问题 30）：失败必须落 {@code FAILED}，不得停留 {@code PENDING}；</li>
 *   <li>批次 08 · 任务 8.2（问题 31）：{@code ChromaRetriever == null} 是<b>依赖缺失</b>，
 *       必须与"无需删除"区分，走失败路径而不是假成功。</li>
 * </ul>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentDeleteServiceV2Test {

    @Mock
    private ChromaRetriever chromaRetriever;

    @Mock
    private DeleteTaskMapper deleteTaskMapper;

    @Mock
    private DeadLetterQueue deadLetterQueue;

    @InjectMocks
    private DocumentDeleteServiceV2 service;

    /** 捕获日志，用于断言"跳过…完成"这类自相矛盾的措辞已消失（任务 8.2.2） */
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    /**
     * 模拟 MyBatis-Plus 的自增主键回填：insert 后实体携带 ID，
     * 后续 updateStatus/incrementRetryCount 才可被真实参数断言。
     */
    @BeforeEach
    void stubInsertIdBackfill() {
        doAnswer(invocation -> {
            DeleteTask task = invocation.getArgument(0);
            task.setId(1L);
            return 1;
        }).when(deleteTaskMapper).insert(any(DeleteTask.class));

        serviceLogger = (Logger) LoggerFactory.getLogger(DocumentDeleteServiceV2.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (serviceLogger != null && logAppender != null) {
            serviceLogger.detachAppender(logAppender);
        }
    }

    // ==================== 批次 08 · 任务 8.2（问题 31） ====================

    @Test
    @DisplayName("★ ChromaRetriever 未装配时：必须落 FAILED + 入 DLQ，绝不置 DONE（原缺陷：假成功）")
    void asyncPhysicalDelete_shouldMarkFailed_whenChromaRetrieverIsNull() {
        ReflectionTestUtils.setField(service, "chromaRetriever", null);

        service.asyncPhysicalDelete(1004L, List.of("vec-1", "vec-2"));

        // 修复前：只打 WARN，随后照常 updateStatus(DONE) —— 一条向量都没删却记录为完成
        verify(deleteTaskMapper).updateStatus(eq(1L), eq(DeleteTask.STATUS_FAILED));
        verify(deleteTaskMapper).incrementRetryCount(eq(1L));
        verify(deadLetterQueue).enqueue(eq(DlqMessage.EVENT_DELETE), eq(1004L),
                anyString(), any(IllegalStateException.class));
        verify(deleteTaskMapper, never()).updateStatus(anyLong(), eq(DeleteTask.STATUS_DONE));
        assertThat(service.dependencyMissingCount())
                .as("依赖缺失次数可观测（任务 8.2.3），用于发现长期装配异常")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 依赖缺失时日志不得出现「完成」字样（任务 8.2.2：杜绝「跳过…完成」）")
    void asyncPhysicalDelete_shouldNotLogCompletion_whenChromaRetrieverIsNull() {
        ReflectionTestUtils.setField(service, "chromaRetriever", null);

        service.asyncPhysicalDelete(1005L, List.of("vec-1"));

        List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages)
                .as("实际日志: %s", messages)
                .anyMatch(m -> m.contains("依赖缺失"))
                .noneMatch(m -> m.contains("物理删除完成"))
                .noneMatch(m -> m.contains("跳过") && m.contains("完成"));
    }

    @Test
    @DisplayName("★ 依赖已装配且无向量 ID 时：置 DONE 是正确语义（与依赖缺失严格区分）")
    void asyncPhysicalDelete_shouldMarkDoneWithoutChromaCall_whenChromaIdsEmpty() {
        service.asyncPhysicalDelete(1006L, List.of());

        verify(deleteTaskMapper).updateStatus(eq(1L), eq(DeleteTask.STATUS_DONE));
        verify(chromaRetriever, never()).deleteByIds(any());
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
        assertThat(service.dependencyMissingCount()).isZero();
    }

    // ==================== 批次 01 · 任务 1.1（问题 30，回归） ====================

    @Test
    @DisplayName("物理删除抛出异常时：任务状态应落 FAILED，且仍递增重试次数并入 DLQ")
    void asyncPhysicalDelete_shouldMarkFailed_whenChromaDeleteThrows() {
        doThrow(new RuntimeException("ChromaDB 不可达"))
                .when(chromaRetriever).deleteByIds(any());

        service.asyncPhysicalDelete(1001L, List.of("vec-1", "vec-2"));

        // 修复前：只有 incrementRetryCount + 入队，状态停留 PENDING
        verify(deleteTaskMapper).updateStatus(eq(1L), eq(DeleteTask.STATUS_FAILED));
        verify(deleteTaskMapper).incrementRetryCount(eq(1L));
        verify(deadLetterQueue).enqueue(eq(DlqMessage.EVENT_DELETE), eq(1001L), anyString(), any(RuntimeException.class));
        // 失败路径不应标记 DONE
        verify(deleteTaskMapper, never()).updateStatus(anyLong(), eq(DeleteTask.STATUS_DONE));
    }

    @Test
    @DisplayName("物理删除成功时：任务状态应落 DONE，且不入 DLQ")
    void asyncPhysicalDelete_shouldMarkDone_whenChromaDeleteSucceeds() {
        service.asyncPhysicalDelete(1002L, List.of("vec-9"));

        verify(deleteTaskMapper).updateStatus(eq(1L), eq(DeleteTask.STATUS_DONE));
        verify(deleteTaskMapper, never()).incrementRetryCount(anyLong());
        verify(deleteTaskMapper, never()).updateStatus(anyLong(), eq(DeleteTask.STATUS_FAILED));
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("无向量 ID 时：直接标记 DONE 且不调用 ChromaDB（批次 01 原有语义）")
    void asyncPhysicalDelete_shouldMarkDone_whenChromaIdsEmpty() {
        service.asyncPhysicalDelete(1003L, List.of());

        verify(deleteTaskMapper).updateStatus(eq(1L), eq(DeleteTask.STATUS_DONE));
        verify(chromaRetriever, never()).deleteByIds(any());
    }

    // ==================== 批次 08 · 8.5（风险 R1）：DLQ 重放的同步入口 ====================

    @Test
    @DisplayName("★ R1：重放走同步入口，成功时置 DONE 且不入队（调用方负责标记当前死信成功）")
    void retryPhysicalDelete_shouldMarkDone_andNeverEnqueue_whenSucceeds() {
        service.retryPhysicalDelete(2001L, List.of("vec-1"));

        verify(deleteTaskMapper).updateStatus(eq(1L), eq(DeleteTask.STATUS_DONE));
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("★ R1：重放失败必须【抛出】且【不自行入队】——否则与调度器的成功标记叠加成无界环路")
    void retryPhysicalDelete_shouldThrowAndNotEnqueue_whenChromaDeleteFails() {
        doThrow(new RuntimeException("ChromaDB 不可达"))
                .when(chromaRetriever).deleteByIds(any());

        assertThatThrownBy(() -> service.retryPhysicalDelete(2002L, List.of("vec-1", "vec-2")))
                .as("失败必须抛给调度器，由其记到【当前】死信消息上（退避 → DEAD）")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ChromaDB 不可达");

        // 失败可见性保留（delete_task 落 FAILED + retryCount 递增）
        verify(deleteTaskMapper).updateStatus(eq(1L), eq(DeleteTask.STATUS_FAILED));
        verify(deleteTaskMapper).incrementRetryCount(eq(1L));
        // 关键：重放路径不得自行入队新死信（R1 的环路源头）
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("★ R1：依赖缺失（ChromaRetriever 为 null）时同样抛出且不入队，计数仍可见")
    void retryPhysicalDelete_shouldThrowAndNotEnqueue_whenChromaRetrieverIsNull() {
        ReflectionTestUtils.setField(service, "chromaRetriever", null);

        assertThatThrownBy(() -> service.retryPhysicalDelete(2003L, List.of("vec-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未装配");
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
        assertThat(service.dependencyMissingCount()).isEqualTo(1);
    }

    // ==================== 批次 08 · 任务 8.2.4：启动期校验 ====================

    @Test
    @DisplayName("★ 启动期校验：依赖缺失时给出 ERROR 结论但不抛异常（保持可独立装载）")
    void verifyDependencyOnStartup_shouldOnlyLog_whenChromaRetrieverIsNull() {
        ReflectionTestUtils.setField(service, "chromaRetriever", null);

        service.verifyDependencyOnStartup();

        List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains("启动检查") && m.contains("ChromaRetriever 未装配"));
    }

    @Test
    @DisplayName("★ 启动期校验：依赖就绪时给出 INFO 结论")
    void verifyDependencyOnStartup_shouldLogReady_whenChromaRetrieverPresent() {
        service.verifyDependencyOnStartup();

        List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains("启动检查") && m.contains("已装配"));
    }
}
