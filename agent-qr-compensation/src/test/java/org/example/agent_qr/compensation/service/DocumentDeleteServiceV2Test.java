package org.example.agent_qr.compensation.service;

import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.compensation.entity.DeleteTask;
import org.example.agent_qr.compensation.mapper.DeleteTaskMapper;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link DocumentDeleteServiceV2} 单元测试（批次 01 · 任务 1.1，问题 30）。
 * <p>
 * 拦截的核心缺陷：物理删除失败时任务状态停留 {@code PENDING}，
 * 且 {@code DeleteTask.STATUS_FAILED} 常量全仓无引用。
 * </p>
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
    }

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
    @DisplayName("无向量 ID 时：直接标记 DONE 且不调用 ChromaDB")
    void asyncPhysicalDelete_shouldMarkDoneWithoutChromaCall_whenChromaIdsEmpty() {
        service.asyncPhysicalDelete(1003L, List.of());

        verify(deleteTaskMapper).updateStatus(eq(1L), eq(DeleteTask.STATUS_DONE));
        verify(chromaRetriever, never()).deleteByIds(any());
    }
}
