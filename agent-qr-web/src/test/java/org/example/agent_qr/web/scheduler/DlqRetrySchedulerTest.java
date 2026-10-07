package org.example.agent_qr.web.scheduler;

import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.DlqMessageMapper;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DocumentParsedEvent;
import org.example.agent_qr.compensation.entity.DeleteTask;
import org.example.agent_qr.compensation.mapper.DeleteTaskMapper;
import org.example.agent_qr.compensation.service.DocumentDeleteServiceV2;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.parser.DocumentParserService;
import org.example.agent_qr.knowledge.service.FileStorageService;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DlqRetryScheduler} 单元测试（批次 01 · 任务 1.2，问题 02）。
 * <p>
 * 拦截的核心缺陷：
 * <ol>
 *   <li>未知 eventType 被当"成功"删除 → 静默丢数据；</li>
 *   <li>四个 retryXxx 是空壳，只打日志便标记成功 → 失败任务被清除而非重试；</li>
 *   <li>入队方写入的 ETL / CHROMA_WRITE 类型不在 switch 覆盖内。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DlqRetrySchedulerTest {

    @Mock
    private DeadLetterQueue deadLetterQueue;

    @Mock
    private DlqMessageMapper dlqMessageMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private DocumentParserService parserService;

    @Mock
    private DocumentDeleteServiceV2 documentDeleteServiceV2;

    @Mock
    private DeleteTaskMapper deleteTaskMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private BatchEmbeddingService batchEmbeddingService;

    @Mock
    private ChromaEmbeddingStore chromaEmbeddingStore;

    /** 批次 08 · 任务 8.5：重放写入前需要 UUID 反查/确定性 id 能力 */
    @Mock
    private ChromaRetriever chromaRetriever;

    /** 批次 08 · 任务 8.4：DELETE 死信可携带 filePath 重放物理文件清理 */
    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private DlqRetryScheduler scheduler;

    /**
     * 向量化提交默认返回已完成的 Future，使重试体的异步回调在同一线程内跑完
     * （Mockito 默认返回 null，会让 {@code thenAccept} 抛 NPE 而掩盖真实断言）。
     */
    @BeforeEach
    void stubEmbeddingFuture() {
        when(batchEmbeddingService.submit(any()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(new float[]{0.1f, 0.2f, 0.3f}));
    }

    // ==================== 用例：未知类型不得被删除（修复的核心） ====================

    @Test
    @DisplayName("未知 eventType 的消息经重试调度后仍是 PENDING，不得被标记成功删除")
    void unknownEventType_shouldNotBeDeletedFromDlq() {
        givenPending(message(1L, "SOME_UNKNOWN_TYPE", 100L, "{\"documentId\":100}"));

        scheduler.retryDeadLetters();

        // 修复前：default 分支调用 updateRetryResult(id, true) → 记录被物理删除
        verify(deadLetterQueue, never()).updateRetryResult(anyLong(), anyBoolean(), any());
    }

    // ==================== 用例：switch 覆盖全部已定义事件类型 ====================

    @ParameterizedTest(name = "已知类型 {0} 应进入对应重试分支，而非落入未知类型分支")
    @ValueSource(strings = {
            DlqMessage.EVENT_PARSE,
            DlqMessage.EVENT_CHUNK,
            DlqMessage.EVENT_EMBED,
            DlqMessage.EVENT_DELETE,
            DlqMessage.EVENT_ETL,
            DlqMessage.EVENT_CHROMA_WRITE
    })
    void everyDefinedEventType_shouldReachARetryBranch(String eventType) {
        givenPending(message(2L, eventType, 200L, "{\"documentId\":200,\"chunkId\":201,\"chromaIds\":\"c1\"}"));

        scheduler.retryDeadLetters();

        // 未知类型分支不调用 updateRetryResult（保留记录），因此只要调用即说明命中了具体分支
        verify(deadLetterQueue, atLeastOnce()).updateRetryResult(eq(2L), anyBoolean(), any());
    }

    // ==================== 用例：PARSE 重放实际业务动作 ====================

    @Test
    @DisplayName("PARSE 重试：应从 payload 取回 filePath/fileType 重新解析并发布 DocumentParsedEvent")
    void retryParse_shouldReparseAndPublishEvent() {
        // 模拟真实的未转义 Windows 路径（入队方 String.format 拼接所致）
        String payload = "{\"documentId\":7,\"filePath\":\"C:\\uploads\\a.pdf\",\"fileType\":\"pdf\"}";
        givenPending(message(3L, DlqMessage.EVENT_PARSE, 7L, payload));
        when(parserService.parse(anyString(), anyString())).thenReturn("解析后的正文");

        scheduler.retryDeadLetters();

        verify(parserService).parse(eq("C:\\uploads\\a.pdf"), eq("pdf"));
        verify(eventPublisher).publishEvent(any(DocumentParsedEvent.class));
        verify(deadLetterQueue).updateRetryResult(eq(3L), eq(true), isNull());
    }

    @Test
    @DisplayName("PARSE 重试体抛异常时应标记为失败（而非成功）")
    void retryParse_shouldMarkFailed_whenParserThrows() {
        String payload = "{\"documentId\":8,\"filePath\":\"C:\\uploads\\b.pdf\",\"fileType\":\"pdf\"}";
        givenPending(message(4L, DlqMessage.EVENT_PARSE, 8L, payload));
        when(parserService.parse(anyString(), anyString()))
                .thenThrow(new RuntimeException("文件不存在"));

        scheduler.retryDeadLetters();

        verify(deadLetterQueue).updateRetryResult(eq(4L), eq(false), any(RuntimeException.class));
        verify(deadLetterQueue, never()).updateRetryResult(eq(4L), eq(true), any());
    }

    @Test
    @DisplayName("PARSE 重试缺少 filePath 上下文时应显式标记失败，不得静默成功")
    void retryParse_shouldMarkFailed_whenPayloadLacksContext() {
        givenPending(message(5L, DlqMessage.EVENT_PARSE, 9L, "{\"documentId\":9}"));

        scheduler.retryDeadLetters();

        verify(deadLetterQueue).updateRetryResult(eq(5L), eq(false), any(IllegalStateException.class));
        verify(deadLetterQueue, never()).updateRetryResult(eq(5L), eq(true), any());
    }

    // ==================== 用例：ETL 缺上下文显式失败 ====================

    @Test
    @DisplayName("ETL 重试缺少 passedData 上下文时应标记失败并保留 TODO，不得当成功删除")
    void retryEtl_shouldMarkFailed_whenReplayContextMissing() {
        givenPending(message(6L, DlqMessage.EVENT_ETL, 300L,
                "{\"datasourceId\":300,\"batchId\":\"b-1\",\"recordCount\":5}"));

        scheduler.retryDeadLetters();

        verify(deadLetterQueue).updateRetryResult(eq(6L), eq(false), any(UnsupportedOperationException.class));
        verify(deadLetterQueue, never()).updateRetryResult(eq(6L), eq(true), any());
    }

    // ==================== 用例：DELETE 重放实际业务动作 ====================

    @Test
    @DisplayName("DELETE 重试：应解析 chromaIds 并调用补偿服务的实际删除方法")
    void retryDelete_shouldInvokeCompensationService() {
        givenPending(message(7L, DlqMessage.EVENT_DELETE, 400L,
                "{\"documentId\":400,\"chromaIds\":\"vec-1,vec-2\"}"));
        when(deleteTaskMapper.selectByDocumentId(400L)).thenReturn(List.of());

        scheduler.retryDeadLetters();

        verify(documentDeleteServiceV2).retryPhysicalDelete(eq(400L), eq(List.of("vec-1", "vec-2")));
        verify(deadLetterQueue).updateRetryResult(eq(7L), eq(true), isNull());
        // 结构性护栏（批次 08 · 8.5 / 风险 R1）：重放不得走 @Async fire-and-forget 入口，
        // 否则"当前消息已置成功"与"异步失败再入队"叠加会形成无界环路
        verify(documentDeleteServiceV2, never()).asyncPhysicalDelete(anyLong(), any());
    }

    @Test
    @DisplayName("DELETE 重试兼容 JSON 数组形式的 chromaIds")
    void retryDelete_shouldParseJsonArrayChromaIds() {
        givenPending(message(8L, DlqMessage.EVENT_DELETE, 401L,
                "{\"documentId\":401,\"chunkIds\":[1,2],\"chromaIds\":[\"vec-a\",\"vec-b\"]}"));
        when(deleteTaskMapper.selectByDocumentId(401L)).thenReturn(List.of());

        scheduler.retryDeadLetters();

        verify(documentDeleteServiceV2).retryPhysicalDelete(eq(401L), eq(List.of("vec-a", "vec-b")));
    }

    @Test
    @DisplayName("DELETE 重试缺少 chromaIds 时应标记失败，不得假成功")
    void retryDelete_shouldMarkFailed_whenChromaIdsMissing() {
        givenPending(message(9L, DlqMessage.EVENT_DELETE, 402L, "{\"documentId\":402}"));

        scheduler.retryDeadLetters();

        verify(deadLetterQueue).updateRetryResult(eq(9L), eq(false), any(IllegalStateException.class));
        verify(documentDeleteServiceV2, never()).retryPhysicalDelete(anyLong(), any());
    }

    @Test
    @DisplayName("DELETE 重试应幂等：该文档已有 DONE 的删除任务时直接确认，不重复删除")
    void retryDelete_shouldSkip_whenLatestTaskAlreadyDone() {
        DeleteTask done = new DeleteTask();
        done.setStatus(DeleteTask.STATUS_DONE);
        when(deleteTaskMapper.selectByDocumentId(403L)).thenReturn(List.of(done));
        givenPending(message(10L, DlqMessage.EVENT_DELETE, 403L,
                "{\"documentId\":403,\"chromaIds\":\"vec-x\"}"));

        scheduler.retryDeadLetters();

        verify(documentDeleteServiceV2, never()).retryPhysicalDelete(anyLong(), any());
        verify(deadLetterQueue).updateRetryResult(eq(10L), eq(true), isNull());
    }

    // ==================== 用例：EMBED 按批次标识整批重放（批次 08 · 8.5 更新） ====================

    @Test
    @DisplayName("EMBED 重试：按批次标识读取【未就绪】切片并整批重放（keyset 分页查询）")
    void retryEmbed_shouldReplayWholeBatchByDocumentId() {
        Chunk c1 = chunk(11L, 500L, null);
        Chunk c2 = chunk(12L, 500L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(500L), eq(0L), anyInt()))
                .thenReturn(List.of(c1, c2));
        givenPending(message(11L, DlqMessage.EVENT_EMBED, 500L, "{\"chunkId\":11,\"documentId\":500}"));

        scheduler.retryDeadLetters();

        verify(batchEmbeddingService).submit(c1);
        verify(batchEmbeddingService).submit(c2);
        // 批次 08 · 8.5：写入成功后必须回写 READY（原先从不回写）
        verify(chunkMapper).updateStatusByIds(eq(List.of(11L, 12L)), eq(Chunk.STATUS_READY));
        verify(deadLetterQueue).updateRetryResult(eq(11L), eq(true), isNull());
    }

    @Test
    @DisplayName("EMBED 重试：目标不存在且无待重放切片时应标记失败")
    void retryEmbed_shouldMarkFailed_whenNoChunksResolved() {
        when(chunkMapper.selectPendingByDocumentIdAfterId(anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(chunkMapper.selectCount(any())).thenReturn(0L);
        givenPending(message(12L, DlqMessage.EVENT_EMBED, 501L, "{\"chunkId\":99,\"documentId\":501}"));

        scheduler.retryDeadLetters();

        verify(deadLetterQueue).updateRetryResult(eq(12L), eq(false), any(IllegalStateException.class));
    }

    // ==================== 用例：CHROMA_WRITE 幂等（批次 08 · 8.5 更新） ====================

    @Test
    @DisplayName("CHROMA_WRITE 重试：切片已 READY 时幂等确认，不重复向量化")
    void retryChromaWrite_shouldSkip_whenChunkAlreadyReady() {
        Chunk chunk = chunk(13L, 600L, null);
        chunk.setChromaId(ChromaRetriever.vectorIdFor(13L));
        chunk.setStatus(Chunk.STATUS_READY);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(600L), eq(0L), anyInt()))
                .thenReturn(List.of());
        when(chunkMapper.selectCount(any())).thenReturn(1L);
        givenPending(message(13L, DlqMessage.EVENT_CHROMA_WRITE, 600L, "{\"chunkId\":13,\"documentId\":600}"));

        scheduler.retryDeadLetters();

        verify(batchEmbeddingService, never()).submit(any(Chunk.class));
        verify(deadLetterQueue).updateRetryResult(eq(13L), eq(true), isNull());
    }

    @Test
    @DisplayName("CHROMA_WRITE 重试：切片状态为 INDEXED（chunk_id 已被随机 UUID 覆盖）时必须幂等重放，"
            + "不得用 chroma_id 判据跳过（R24 ①）")
    void retryChromaWrite_shouldReplay_whenChunkStillIndexed() {
        Chunk chunk = chunk(14L, 601L, null);
        chunk.setChromaId("legacy-random-uuid");
        chunk.setStatus(Chunk.STATUS_INDEXED);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(601L), eq(0L), anyInt()))
                .thenReturn(List.of(chunk));
        givenPending(message(14L, DlqMessage.EVENT_CHROMA_WRITE, 601L, "{\"chunkId\":14,\"documentId\":601}"));

        scheduler.retryDeadLetters();

        verify(batchEmbeddingService).submit(chunk);
        verify(chunkMapper).updateStatusByIds(eq(List.of(14L)), eq(Chunk.STATUS_READY));
        verify(deadLetterQueue).updateRetryResult(eq(14L), eq(true), isNull());
    }

    // ==================== 用例：无待重试消息时不动作 ====================

    @Test
    @DisplayName("无到期消息时调度器不执行任何重试动作")
    void retryDeadLetters_shouldDoNothing_whenNoPendingMessages() {
        when(dlqMessageMapper.selectPendingRetries(any(LocalDateTime.class))).thenReturn(List.of());

        scheduler.retryDeadLetters();

        verify(deadLetterQueue, never()).updateRetryResult(anyLong(), anyBoolean(), any());
    }

    // ==================== 测试辅助 ====================

    private void givenPending(DlqMessage... messages) {
        when(dlqMessageMapper.selectPendingRetries(any(LocalDateTime.class)))
                .thenReturn(List.of(messages));
    }

    private static DlqMessage message(Long id, String eventType, Long documentId, String payload) {
        DlqMessage msg = new DlqMessage();
        msg.setId(id);
        msg.setEventType(eventType);
        msg.setDocumentId(documentId);
        msg.setPayload(payload);
        msg.setStatus(DlqMessage.STATUS_PENDING);
        msg.setRetryCount(0);
        msg.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return msg;
    }

    private static Chunk chunk(Long id, Long documentId, Long datasourceId) {
        Chunk chunk = new Chunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setDatasourceId(datasourceId);
        chunk.setContent("切片内容-" + id);
        chunk.setChromaId("pending");
        chunk.setDeleted(0);
        return chunk;
    }
}
