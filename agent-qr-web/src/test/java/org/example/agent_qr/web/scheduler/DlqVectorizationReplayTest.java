package org.example.agent_qr.web.scheduler;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.apache.ibatis.annotations.Select;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.DlqMessageMapper;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.compensation.service.DocumentDeleteServiceV2;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DLQ 向量化重放对齐测试（批次 08 · 任务 8.5，来源 R24 —— 批次 07 独立验证发现）。
 * <p>
 * 7.0 引入双状态机（{@code INDEXED} → {@code EMBEDDING} → {@code READY}）与
 * {@code removeAll} + {@code addAll} 幂等写入后，{@code DlqRetryScheduler} 的重试体仍是旧设计，
 * 三处不一致各由本类一条用例拦截：
 * </p>
 * <ol>
 *   <li>{@link #replay_shouldMarkChunksReady_withDeterministicVectorId()} —— 状态断链（永久停在 INDEXED）；</li>
 *   <li>{@link #replay_shouldBeIdempotent_whenReplayedRepeatedly()} —— 随机 UUID 单条写入产生重复/孤儿向量；</li>
 *   <li>{@link #replay_shouldNotTouchReadyChunks()} —— 按 documentId 取回含 READY 的整文档重灌。</li>
 * </ol>
 * <p>
 * 另加一条结构性用例 {@link #replay_shouldNotEnqueueNewDlqMessage_whenReplayFails()}：
 * 重放失败必须落在<b>当前</b>死信消息的退避重试上，不得再入队新消息
 * （否则 DLQ 重试 → 事件 → Listener → 失败再入队 会形成环路）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DlqVectorizationReplayTest {

    private static final Long DOCUMENT_ID = 700L;

    @Mock
    private DeadLetterQueue deadLetterQueue;

    @Mock
    private DlqMessageMapper dlqMessageMapper;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private DocumentDeleteServiceV2 documentDeleteServiceV2;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private BatchEmbeddingService batchEmbeddingService;

    @Mock
    private ChromaEmbeddingStore chromaEmbeddingStore;

    @Mock
    private ChromaRetriever chromaRetriever;

    @InjectMocks
    private DlqRetryScheduler scheduler;

    private static final float[] VECTOR = new float[]{0.1f, 0.2f, 0.3f};

    @BeforeEach
    void stubEmbeddingAndVectorLookup() {
        when(batchEmbeddingService.submit(any()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(VECTOR));
        when(chromaRetriever.findVectorIdsByChunkIds(anyCollection())).thenReturn(Map.of());
        when(chunkMapper.countByDocumentIdGroupByStatus(anyLong())).thenReturn(List.of());
    }

    // ==================== ① 状态断链 ====================

    @Test
    @DisplayName("★ R24 ①：EMBED 重放后切片必须置 READY，且 chroma_id 为 UUIDv3 确定性 id")
    void replay_shouldMarkChunksReady_withDeterministicVectorId() {
        Chunk c1 = chunk(101L, Chunk.STATUS_INDEXED, "pending");
        Chunk c2 = chunk(102L, Chunk.STATUS_INDEXED, "pending");
        givenPendingChunks(c1, c2);
        givenPendingMessage(1L, DlqMessage.EVENT_EMBED, "{\"chunkId\":101,\"documentId\":700}");

        scheduler.retryDeadLetters();

        // 修复前：只写 chroma_id，从不回写 status → 切片永久停在 INDEXED，文档聚合恒为"部分就绪"
        verify(chunkMapper).updateStatusByIds(eq(List.of(101L, 102L)), eq(Chunk.STATUS_READY));

        // chroma_id 必须是确定性 UUIDv3（vectorIdFor 由 chunkId 派生），不是随机 UUID
        ArgumentCaptor<Chunk> saved = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper, times(2)).updateById(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(chunk -> {
                    assertThat(chunk.getChromaId()).isEqualTo(ChromaRetriever.vectorIdFor(chunk.getId()));
                    assertThat(UUID.fromString(chunk.getChromaId()).version())
                            .as("UUIDv3（MD5 命名空间）确定性 id")
                            .isEqualTo(3);
                });
    }

    // ==================== ② 非幂等写入 ====================

    @Test
    @DisplayName("★ R24 ②：同一批切片重复重放，写入的向量 id 集合不变、无新增随机 UUID")
    void replay_shouldBeIdempotent_whenReplayedRepeatedly() {
        // 第一次重放：既有向量是历史随机 UUID（元数据反查得到）
        Chunk c1 = chunk(201L, Chunk.STATUS_INDEXED, "legacy-random-uuid");
        givenPendingChunks(c1);
        when(chromaRetriever.findVectorIdsByChunkIds(anyCollection()))
                .thenReturn(Map.of(201L, "legacy-random-uuid"));
        givenPendingMessage(2L, DlqMessage.EVENT_EMBED, "{\"chunkId\":201,\"documentId\":700}");
        scheduler.retryDeadLetters();

        // 第二次重放：同一批切片（状态仍为 INDEXED，模拟上一轮写入后再次重放）
        Chunk c1Again = chunk(201L, Chunk.STATUS_INDEXED, ChromaRetriever.vectorIdFor(201L));
        givenPendingChunks(c1Again);
        givenPendingMessage(3L, DlqMessage.EVENT_EMBED, "{\"chunkId\":201,\"documentId\":700}");
        scheduler.retryDeadLetters();

        ArgumentCaptor<List<String>> vectorIds = ArgumentCaptor.forClass(List.class);
        verify(chromaEmbeddingStore, times(2)).addAll(vectorIds.capture(), anyList(), anyList());

        List<List<String>> captured = vectorIds.getAllValues();
        assertThat(captured.get(0))
                .as("写入 id 必须是确定性 id，不得使用 Utils.randomUUID()")
                .containsExactly(ChromaRetriever.vectorIdFor(201L));
        assertThat(captured.get(1))
                .as("重复重放的 id 集合必须与首次完全一致（ChromaDB 向量 id 集合不变）")
                .isEqualTo(captured.get(0));
        assertThat(captured).allSatisfy(ids -> assertThat(ids)
                .noneMatch(id -> id.equals("legacy-random-uuid")));

        // 每轮写入前都先按 UUID 清理既有向量（含历史随机 UUID），因此不会新增孤儿向量
        ArgumentCaptor<Collection<String>> removed = ArgumentCaptor.forClass(Collection.class);
        verify(chromaEmbeddingStore, times(2)).removeAll(removed.capture());
        assertThat(removed.getAllValues().get(0))
                .as("removeAll 的入参是 ChromaDB 向量 id（UUID），必须覆盖历史随机 UUID")
                .contains("legacy-random-uuid", ChromaRetriever.vectorIdFor(201L));

        InOrder order = inOrder(chromaEmbeddingStore);
        order.verify(chromaEmbeddingStore).removeAll(anyCollection());
        order.verify(chromaEmbeddingStore).addAll(anyList(), anyList(), anyList());
    }

    // ==================== ③ 重灌整文档 ====================

    @Test
    @DisplayName("★ R24 ③：重放只取未就绪切片，已 READY 的切片不得被重新处理")
    void replay_shouldNotTouchReadyChunks() {
        // 已 READY 的切片：单条重放路径下必须直接确认，不提交向量化
        Chunk ready = chunk(301L, Chunk.STATUS_READY, ChromaRetriever.vectorIdFor(301L));
        when(chunkMapper.selectById(301L)).thenReturn(ready);
        givenPendingMessage(4L, DlqMessage.EVENT_CHROMA_WRITE, "{\"chunkId\":301}");

        scheduler.retryDeadLetters();

        verify(batchEmbeddingService, never()).submit(any(Chunk.class));
        verify(deadLetterQueue).updateRetryResult(eq(4L), eq(true), isNull());

        // 批次路径：查询走 keyset 分页的"未就绪"查询，绝不使用 selectByDocumentId（含 READY 切片）
        Chunk indexed = chunk(302L, Chunk.STATUS_INDEXED, "pending");
        givenPendingChunks(indexed);
        givenPendingMessage(5L, DlqMessage.EVENT_EMBED, "{\"chunkId\":302,\"documentId\":700}");
        scheduler.retryDeadLetters();

        verify(chunkMapper, never()).selectByDocumentId(anyLong());
        verify(chunkMapper).selectPendingByDocumentIdAfterId(eq(DOCUMENT_ID), eq(0L), anyInt());
        verify(batchEmbeddingService).submit(indexed);
        verify(batchEmbeddingService, never()).submit(ready);
    }

    @Test
    @DisplayName("★ R24 ③（SQL 层）：分页查询本身必须带 status <> 'READY' 与 deleted = 0")
    void pendingQueryForReplay_mustFilterReadyAndDeleted() throws Exception {
        Method method = ChunkMapper.class.getMethod("selectPendingByDocumentIdAfterId",
                Long.class, long.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql).contains("deleted = 0").contains("status <> 'READY'");
    }

    // ==================== 防环路（8.5 的结构性保证） ====================

    @Test
    @DisplayName("★ 重放失败：只让当前死信走退避重试，绝不入队新消息（防 DLQ↔Listener 环路）")
    void replay_shouldNotEnqueueNewDlqMessage_whenReplayFails() {
        CompletableFuture<float[]> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("Ollama 不可用"));
        when(batchEmbeddingService.submit(any())).thenReturn(failed);
        givenPendingChunks(chunk(401L, Chunk.STATUS_INDEXED, "pending"));
        givenPendingMessage(6L, DlqMessage.EVENT_EMBED, "{\"chunkId\":401,\"documentId\":700}");

        scheduler.retryDeadLetters();

        // 失败落在【当前】消息上 → 指数退避 → 超限转 DEAD（退避机制原样保留）
        verify(deadLetterQueue).updateRetryResult(eq(6L), eq(false), any(RuntimeException.class));
        verify(deadLetterQueue, never()).updateRetryResult(eq(6L), eq(true), any());
        // 关键：不得再入队新消息（原实现失败回调里 enqueue(EVENT_EMBED/EVENT_CHROMA_WRITE) 会形成环路）
        verify(deadLetterQueue, never()).enqueue(any(), any(), any(), any());
        verify(chunkMapper, never()).updateStatusByIds(anyList(), eq(Chunk.STATUS_READY));
    }

    // ==================== 辅助 ====================

    private void givenPendingChunks(Chunk... chunks) {
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(DOCUMENT_ID), eq(0L), anyInt()))
                .thenReturn(List.of(chunks));
    }

    private void givenPendingMessage(Long msgId, String eventType, String payload) {
        DlqMessage msg = new DlqMessage();
        msg.setId(msgId);
        msg.setEventType(eventType);
        // documentId 只从 payload 解析（本类 payload 自带 documentId）；置空以便覆盖单条 chunkId 回退路径
        msg.setDocumentId(null);
        msg.setPayload(payload);
        msg.setStatus(DlqMessage.STATUS_PENDING);
        msg.setRetryCount(0);
        msg.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        when(dlqMessageMapper.selectPendingRetries(any(LocalDateTime.class))).thenReturn(List.of(msg));
    }

    private static Chunk chunk(Long id, String status, String chromaId) {
        Chunk chunk = new Chunk();
        chunk.setId(id);
        chunk.setDocumentId(DOCUMENT_ID);
        chunk.setChunkIndex(0);
        chunk.setContent("切片内容-" + id);
        chunk.setChromaId(chromaId);
        chunk.setStatus(status);
        chunk.setDeleted(0);
        return chunk;
    }
}
