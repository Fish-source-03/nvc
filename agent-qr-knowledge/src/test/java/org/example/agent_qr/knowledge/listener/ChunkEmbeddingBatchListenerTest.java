package org.example.agent_qr.knowledge.listener;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.ChunksBatchCreatedEvent;
import org.example.agent_qr.common.event.ChunksCreatedEvent;
import org.example.agent_qr.common.event.DocumentParsedEvent;
import org.example.agent_qr.common.event.EmbeddingCompletedEvent;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.splitter.TextSplitter;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkEmbeddingBatchListener} 测试（批次 07 · 任务 7.0b / 7.0.7 / 7.0.8 / 7.0.9）。
 * <p>
 * 拦截的核心缺陷（问题 28）：切片入库后<b>立刻</b>把文档置为 READY——
 * 此时向量尚未写入 ChromaDB，用户看到"就绪"却搜不到。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChunkEmbeddingBatchListenerTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private TextSplitter textSplitter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private DeadLetterQueue deadLetterQueue;

    @Mock
    private BatchEmbeddingService batchEmbeddingService;

    @Mock
    private ChromaEmbeddingStore chromaEmbeddingStore;

    @Mock
    private ChromaRetriever chromaRetriever;

    @Mock
    private BM25Retriever bm25Retriever;

    private ChunkEmbeddingBatchListener listener;

    private final AtomicLong idSequence = new AtomicLong(20000);

    @BeforeEach
    void setUp() {
        listener = new ChunkEmbeddingBatchListener(documentMapper, chunkMapper, dataSourceMapper,
                textSplitter, eventPublisher, deadLetterQueue, batchEmbeddingService,
                chromaEmbeddingStore, chromaRetriever, bm25Retriever);

        when(textSplitter.split(anyString())).thenReturn(List.of("片段一", "片段二", "片段三"));

        // 模拟自增主键回填（真实实现由 MyBatis useGeneratedKeys 完成）
        when(chunkMapper.insert(any(Chunk.class))).thenAnswer(invocation -> {
            Chunk chunk = invocation.getArgument(0);
            chunk.setId(idSequence.incrementAndGet());
            return 1;
        });
        when(batchEmbeddingService.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(new float[]{0.1f, 0.2f, 0.3f}));
        when(chromaRetriever.findVectorIdsByChunkIds(any())).thenReturn(Map.of());
        when(bm25Retriever.findMissingChunkIds(any())).thenReturn(List.of());
        when(bm25Retriever.addBatchToIndex(anyList())).thenReturn(1);
        when(chunkMapper.updateStatusByIds(anyList(), anyString())).thenReturn(1);
        when(chunkMapper.updateById(any(Chunk.class))).thenReturn(1);
        when(chunkMapper.updateStatus(anyLong(), anyString())).thenReturn(1);
    }

    // ==================== 切片阶段 ====================

    @Test
    @DisplayName("★ chunk 写入 MySQL 后状态为 INDEXED，且 document 不得在向量化前进入 READY")
    void handleDocumentParsed_shouldInsertChunksAsIndexed_andNotMarkReady() {
        listener.handleDocumentParsed(new DocumentParsedEvent(this, 9L, "正文内容"));

        ArgumentCaptor<Chunk> chunkCaptor = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkMapper, times(3)).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getAllValues())
                .allSatisfy(chunk -> {
                    assertThat(chunk.getStatus()).isEqualTo(Chunk.STATUS_INDEXED);
                    assertThat(chunk.getChromaId()).isEqualTo("pending");
                });

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentMapper, atLeastOnce()).updateStatus(eq(9L), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues())
                .as("切片入库后文档只能是 CHUNKING → INDEXED，绝不能出现 READY")
                .containsExactly(DocumentStatus.CHUNKING.name(), DocumentStatus.INDEXED.name())
                .doesNotContain(DocumentStatus.READY.name());

        verify(documentMapper, never()).updateStatus(9L, DocumentStatus.READY.name());
    }

    @Test
    @DisplayName("★ 发布方：文档链路切片入库后发布 ChunksBatchCreatedEvent（只带标识）")
    void handleDocumentParsed_shouldPublishChunksBatchCreatedEvent() {
        listener.handleDocumentParsed(new DocumentParsedEvent(this, 9L, "正文内容"));

        // 既有契约保持不变
        verify(eventPublisher).publishEvent(any(ChunksCreatedEvent.class));

        ArgumentCaptor<ChunksBatchCreatedEvent> captor =
                ArgumentCaptor.forClass(ChunksBatchCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ChunksBatchCreatedEvent batchEvent = captor.getValue();
        assertThat(batchEvent.getDocumentId()).isEqualTo(9L);
        assertThat(batchEvent.getDatasourceId()).isNull();
        assertThat(batchEvent.getSyncBatchId()).isNull();
    }

    @Test
    @DisplayName("切片阶段失败：回写 FAILED + 入 CHUNK 死信，不发布向量化事件")
    void handleDocumentParsed_shouldMarkFailedAndEnqueueDlq_onFailure() {
        when(textSplitter.split(anyString())).thenThrow(new RuntimeException("解析器崩溃"));

        listener.handleDocumentParsed(new DocumentParsedEvent(this, 9L, "正文内容"));

        verify(documentMapper).updateStatus(9L, DocumentStatus.FAILED.name());
        verify(documentMapper).updateErrorMsg(eq(9L), anyString());
        verify(deadLetterQueue).enqueue(eq(DlqMessage.EVENT_CHUNK), eq(9L), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any(ChunksBatchCreatedEvent.class));
    }

    // ==================== 向量化阶段 ====================

    @Test
    @DisplayName("★ 向量化完成后 chunk 置 READY、document 聚合为 READY（失败路径见下一条用例）")
    void handleChunksBatchCreated_shouldMarkReadyOnlyOnSuccess() {
        List<Chunk> batch = chunks(3, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());
        when(chunkMapper.countByDocumentIdGroupByStatus(9L)).thenReturn(statusCounts(Chunk.STATUS_READY, 3));

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkMapper, times(2)).updateStatusByIds(idsCaptor.capture(), anyString());
        assertThat(idsCaptor.getAllValues().get(0)).containsExactlyElementsOf(ids(batch));
        verify(chunkMapper, never()).updateStatusByIds(anyList(), eq(Chunk.STATUS_INDEXED));
        // 文档状态由聚合推导（此处聚合结果为 READY）
        verify(documentMapper).updateStatus(9L, DocumentStatus.READY.name());
    }

    @Test
    @DisplayName("★ 向量化失败：chunk 回退为 INDEXED（不置 READY），整批只入一次 EMBED 死信")
    void handleChunksBatchCreated_shouldRevertToIndexed_onEmbedFailure() {
        List<Chunk> batch = chunks(2, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());
        when(batchEmbeddingService.submit(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Ollama 不可达")));
        when(chunkMapper.countByDocumentIdGroupByStatus(9L)).thenReturn(statusCounts(Chunk.STATUS_INDEXED, 2));

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        verify(chunkMapper).updateStatusByIds(anyList(), eq(Chunk.STATUS_INDEXED));
        verify(chunkMapper, never()).updateStatusByIds(anyList(), eq(Chunk.STATUS_READY));
        verify(chromaEmbeddingStore, never()).addAll(anyList(), anyList(), anyList());
        verify(documentMapper, never()).updateStatus(9L, DocumentStatus.READY.name());
        // 整批失败只入一次死信（不是每切片一条）
        verify(deadLetterQueue, times(1)).enqueue(eq(DlqMessage.EVENT_EMBED), eq(9L), anyString(), any());
    }

    @Test
    @DisplayName("★ ChromaDB 写入失败：入 CHROMA_WRITE（迁移清单未遗漏）+ 回退 INDEXED")
    void handleChunksBatchCreated_shouldEnqueueChromaWrite_onWriteFailure() {
        List<Chunk> batch = chunks(2, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());
        doThrow(new RuntimeException("Connection refused"))
                .when(chromaEmbeddingStore).addAll(anyList(), anyList(), anyList());

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        verify(deadLetterQueue).enqueue(eq(DlqMessage.EVENT_CHROMA_WRITE), eq(9L), anyString(), any());
        verify(chunkMapper).updateStatusByIds(anyList(), eq(Chunk.STATUS_INDEXED));
        verify(chunkMapper, never()).updateStatusByIds(anyList(), eq(Chunk.STATUS_READY));
    }

    @Test
    @DisplayName("★ Listener 分批读取：每批不超过 BATCH_SIZE，游标逐批递进（不一次性加载全部切片）")
    void handleChunksBatchCreated_shouldReadInPages() {
        int pageSize = ChunkEmbeddingBatchListener.BATCH_SIZE;
        int total = pageSize * 2 + 1;
        List<Chunk> all = chunks(total, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    long afterId = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    return all.stream()
                            .filter(chunk -> chunk.getId() > afterId)
                            .limit(limit)
                            .toList();
                });

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        ArgumentCaptor<Long> afterCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(chunkMapper, times(3))
                .selectPendingByDocumentIdAfterId(eq(9L), afterCaptor.capture(), limitCaptor.capture());

        assertThat(limitCaptor.getAllValues())
                .as("单批上限固定为 BATCH_SIZE，禁止无上限加载（数据同步可能几十万条）")
                .allSatisfy(limit -> assertThat(limit).isEqualTo(pageSize));
        assertThat(afterCaptor.getAllValues())
                .as("keyset 游标：首批 0，随后是上一批的末位 id（严格递增）")
                .containsExactly(0L, all.get(pageSize - 1).getId(), all.get(pageSize * 2 - 1).getId());
        assertThat(afterCaptor.getAllValues()).isSorted();
        // 401 条 → 3 批（200 + 200 + 1），每批各写一次 ChromaDB
        verify(chunkMapper, times(3)).updateStatusByIds(anyList(), eq(Chunk.STATUS_READY));
    }

    @Test
    @DisplayName("★ 元数据修正：document_title 取自 getTitle() 而非 getFileName()")
    void handleChunksBatchCreated_shouldUseDocumentTitle_notFileName() {
        Document doc = new Document();
        doc.setId(9L);
        doc.setTitle("2024 年度人力资源报告");
        doc.setFileName("hr_report_2024_final_v3.pdf");
        when(documentMapper.selectById(9L)).thenReturn(doc);

        List<Chunk> batch = chunks(1, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        TextSegment segment = capturedSegments().get(0);
        Metadata metadata = segment.metadata();

        assertThat(metadata.getString("document_title")).isEqualTo("2024 年度人力资源报告");
        assertThat(metadata.getString("document_title")).isNotEqualTo(doc.getFileName());
        assertThat(metadata.getString("chunk_id")).isEqualTo(batch.get(0).getId().toString());
        assertThat(metadata.getString("document_id")).isEqualTo("9");
    }

    @Test
    @DisplayName("标题为空时回退文件名，再回退 doc-<id>（元数据值不得为 null）")
    void resolveTitle_shouldFallBack_whenTitleBlank() {
        Document doc = new Document();
        doc.setId(9L);
        doc.setFileName("fallback.pdf");
        when(documentMapper.selectById(9L)).thenReturn(doc);

        List<Chunk> batch = chunks(1, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        assertThat(capturedSegments().get(0).metadata().getString("document_title"))
                .isEqualTo("fallback.pdf");
    }

    @Test
    @DisplayName("★ 数据同步链路：按 datasourceId 分页读取，元数据带 datasource_id 与数据源名")
    void handleChunksBatchCreated_shouldSupportDatasourceChain() {
        DataSourceConfig config = new DataSourceConfig();
        config.setId(2L);
        config.setSourceName("HR 主数据");
        when(dataSourceMapper.selectById(2L)).thenReturn(config);

        List<Chunk> batch = chunks(2, null, 2L);
        when(chunkMapper.selectPendingByDatasourceIdAfterId(eq(2L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDatasource(2L, "b-1"));

        verify(chunkMapper, never()).selectPendingByDocumentIdAfterId(anyLong(), anyLong(), anyInt());
        List<TextSegment> segments = capturedSegments();
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).metadata().getString("datasource_id")).isEqualTo("2");
        assertThat(segments.get(0).metadata().getString("document_title")).isEqualTo("HR 主数据");
        // 数据同步链路无文档，不发布 EmbeddingCompletedEvent
        verify(eventPublisher, never()).publishEvent(any(EmbeddingCompletedEvent.class));
    }

    @Test
    @DisplayName("★ successCount 计数语义修正：统计'向量化并写入成功'的切片数，而非'提交成功'")
    void handleChunksBatchCreated_shouldReportVectorizedCount_notSubmittedCount() {
        List<Chunk> batch = chunks(4, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());
        // 第 3 条向量化失败 → 整批失败，成功数应为 0（不是"提交成功 4 条"）
        when(batchEmbeddingService.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(new float[]{1f}),
                        CompletableFuture.completedFuture(new float[]{1f}),
                        CompletableFuture.failedFuture(new RuntimeException("Ollama 不可达")),
                        CompletableFuture.completedFuture(new float[]{1f}));

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        ArgumentCaptor<EmbeddingCompletedEvent> captor =
                ArgumentCaptor.forClass(EmbeddingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getChunkCount())
                .as("原实现统计的是 submit 提交成功数（恒等于切片数），修正后应为真正写入 ChromaDB 的数量")
                .isZero();
    }

    @Test
    @DisplayName("缺标识的事件被忽略，不产生任何读写")
    void handleChunksBatchCreated_shouldIgnoreEventWithoutTarget() {
        listener.handleChunksBatchCreated(new ChunksBatchCreatedEvent());

        verify(chunkMapper, never()).selectPendingByDocumentIdAfterId(anyLong(), anyLong(), anyInt());
        verify(chunkMapper, never()).selectPendingByDatasourceIdAfterId(anyLong(), anyLong(), anyInt());
        verify(chromaEmbeddingStore, never()).addAll(anyList(), anyList(), anyList());
    }

    @Test
    @DisplayName("无待向量化切片时不写 ChromaDB、不报错")
    void handleChunksBatchCreated_shouldNoOp_whenNothingPending() {
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(List.of());

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        verify(chromaEmbeddingStore, never()).addAll(anyList(), anyList(), anyList());
        verify(chunkMapper, never()).updateStatusByIds(anyList(), anyString());
    }

    // ==================== 事件驱动接线（7.0b） ====================

    @Test
    @DisplayName("★ 两条链路都是事件驱动：Spring 发布 DocumentParsedEvent / ChunksBatchCreatedEvent 能被 Listener 消费")
    void bothChains_shouldBeWiredAsSpringEventListeners() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("chunkEmbeddingBatchListener", ChunkEmbeddingBatchListener.class,
                    () -> listener);
            context.refresh();

            // 文档上传链路入口
            context.publishEvent(new DocumentParsedEvent(this, 9L, "正文内容"));
            verify(chunkMapper, times(3)).insert(any(Chunk.class));
            verify(eventPublisher).publishEvent(any(ChunksBatchCreatedEvent.class));

            // 数据同步链路入口：直接发布批量创建事件（ETL 的发布点见 DataSyncEtlListenerBatchWriteTest）
            List<Chunk> batch = chunks(1, null, 55L);
            when(chunkMapper.selectPendingByDatasourceIdAfterId(eq(55L), eq(0L), anyInt()))
                    .thenReturn(batch, List.of());
            context.publishEvent(ChunksBatchCreatedEvent.forDatasource(55L, "b-1"));
            verify(chunkMapper).selectPendingByDatasourceIdAfterId(eq(55L), eq(0L), anyInt());
            verify(chunkMapper).updateStatusByIds(anyList(), eq(Chunk.STATUS_READY));
        }
    }

    @Test
    @DisplayName("★ 旧 Listener 已退役：ChunkEmbeddingListener 源文件已从代码库移除")
    void legacyListener_shouldBeRemoved() {
        java.nio.file.Path source = java.nio.file.Path.of("src", "main", "java", "org", "example",
                "agent_qr", "knowledge", "listener", "ChunkEmbeddingListener.java");
        assertThat(java.nio.file.Files.exists(source))
                .as("批次 07 · 7.0.9：ChunkEmbeddingListener 应已删除，职责整体迁至 "
                        + "ChunkEmbeddingBatchListener（其 5 项迁移职责见 javadoc 与测试）")
                .isFalse();
    }

    // ==================== BM25 双保险（7.0e） ====================

    @Test
    @DisplayName("★ 发布方尽力更新 BM25：切片入库即索引（此时 INDEXED 成立、关键词可搜）")
    void handleDocumentParsed_shouldUpdateBm25OnInsert() {
        listener.handleDocumentParsed(new DocumentParsedEvent(this, 9L, "正文内容"));

        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(bm25Retriever).addBatchToIndex(captor.capture());
        assertThat(captor.getValue())
                .as("入库的 3 条切片应全部进入 BM25 索引")
                .hasSize(3)
                .allSatisfy(chunk -> assertThat(chunk.getId()).isNotNull());
    }

    @Test
    @DisplayName("★ 发布方 BM25 更新失败不阻断主流程：仍置 INDEXED 并发布向量化事件")
    void handleDocumentParsed_shouldNotBlock_whenBm25UpdateFails() {
        when(bm25Retriever.addBatchToIndex(anyList()))
                .thenThrow(new RuntimeException("Lucene 索引写失败"));

        listener.handleDocumentParsed(new DocumentParsedEvent(this, 9L, "正文内容"));

        verify(documentMapper).updateStatus(9L, DocumentStatus.INDEXED.name());
        verify(documentMapper, never()).updateStatus(9L, DocumentStatus.FAILED.name());
        verify(eventPublisher).publishEvent(any(ChunksBatchCreatedEvent.class));
    }

    @Test
    @DisplayName("★ 发布方漏更新时 Listener 校验补写：缺失的切片被补进 BM25 索引")
    void handleChunksBatchCreated_shouldBackfillMissingBm25Entries() {
        List<Chunk> batch = chunks(3, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());
        // 前两条缺席索引（模拟发布方更新失败）
        when(bm25Retriever.findMissingChunkIds(any()))
                .thenReturn(List.of(batch.get(0).getId(), batch.get(1).getId()));

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(bm25Retriever).addBatchToIndex(captor.capture());
        assertThat(captor.getValue())
                .as("只补写缺失的两条，不重复写已索引的")
                .hasSize(2)
                .extracting(Chunk::getId)
                .containsExactly(batch.get(0).getId(), batch.get(1).getId());
    }

    @Test
    @DisplayName("★ 索引已完整时不重复补写（校验补写幂等，避免每次向量化都重写索引）")
    void handleChunksBatchCreated_shouldSkipBackfill_whenNothingMissing() {
        List<Chunk> batch = chunks(2, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());
        when(bm25Retriever.findMissingChunkIds(any())).thenReturn(List.of());

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        verify(bm25Retriever, never()).addBatchToIndex(anyList());
    }

    // ==================== 幂等（7.0.15） ====================

    @Test
    @DisplayName("★ 幂等：写入前先 removeAll 既有向量 id，再 addAll（ChromaEmbeddingStore 无 upsert）")
    void writeToChroma_shouldRemoveExistingVectors_beforeAddAll() {
        List<Chunk> batch = chunks(2, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());
        // 既有随机 UUID（历史向量）：chunkId → vectorId 反查结果
        Map<Long, String> existing = new LinkedHashMap<>();
        existing.put(batch.get(0).getId(), "11111111-2222-3333-4444-555555555555");
        when(chromaRetriever.findVectorIdsByChunkIds(any())).thenReturn(existing);

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        ArgumentCaptor<Set<String>> removed = ArgumentCaptor.forClass(Set.class);
        verify(chromaEmbeddingStore).removeAll(removed.capture());
        assertThat(removed.getValue())
                .as("removeAll 的入参必须是 ChromaDB 侧向量 id（UUID），不是 chunkId")
                .contains("11111111-2222-3333-4444-555555555555")
                .contains(ChromaRetriever.vectorIdFor(batch.get(0).getId()))
                .contains(ChromaRetriever.vectorIdFor(batch.get(1).getId()))
                .noneSatisfy(id -> assertThat(id).isEqualTo(String.valueOf(batch.get(0).getId())));

        // 顺序：先 removeAll 后 addAll
        InOrder inOrder = inOrder(chromaEmbeddingStore);
        inOrder.verify(chromaEmbeddingStore).removeAll(any(Set.class));
        inOrder.verify(chromaEmbeddingStore).addAll(anyList(), anyList(), anyList());
    }

    @Test
    @DisplayName("★ 幂等：同一批 chunk 重复向量化不报错，且向量 id 稳定（可重复执行）")
    void writeToChroma_shouldBeRepeatable() {
        List<Chunk> batch = chunks(2, 9L, null);
        // 每轮各查一次（本批 2 条 < BATCH_SIZE，读满即结束），两轮都返回同一批切片
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch);

        // 第一遍
        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));
        // 第二遍：模拟 DLQ 重放/存量重跑——ChromaDB 中已有第一遍写入的向量
        Map<Long, String> existing = new LinkedHashMap<>();
        for (Chunk chunk : batch) {
            existing.put(chunk.getId(), ChromaRetriever.vectorIdFor(chunk.getId()));
        }
        when(chromaRetriever.findVectorIdsByChunkIds(any())).thenReturn(existing);
        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chromaEmbeddingStore, times(2)).addAll(idsCaptor.capture(), anyList(), anyList());
        assertThat(idsCaptor.getAllValues().get(0))
                .as("两次写入必须使用同一组向量 id（由 chunkId 确定），否则重跑会撞 DuplicateIDError")
                .isEqualTo(idsCaptor.getAllValues().get(1));
        // 每轮都先删后写
        verify(chromaEmbeddingStore, times(2)).removeAll(any(Set.class));
        // 第二遍仍需覆盖既有 id（第二遍的 find 结果并集确定性 id）
        verify(chromaRetriever, times(2)).findVectorIdsByChunkIds(any());
    }

    @Test
    @DisplayName("★ 向量 id 由 chunkId 确定性派生，且形如 UUID")
    void vectorIdFor_shouldBeDeterministicUuid() {
        String first = ChromaRetriever.vectorIdFor(7387L);
        assertThat(first).isEqualTo(ChromaRetriever.vectorIdFor(7387L));
        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(first).isNotEqualTo(ChromaRetriever.vectorIdFor(7388L));
    }

    @Test
    @DisplayName("★ 批量写入：一批一次 addAll，不再逐条 add（批次 07 · 7.0.14）")
    void writeToChroma_shouldUseAddAll_notRowByRow() {
        List<Chunk> batch = chunks(5, 9L, null);
        when(chunkMapper.selectPendingByDocumentIdAfterId(eq(9L), eq(0L), anyInt()))
                .thenReturn(batch, List.of());

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        ArgumentCaptor<List<Embedding>> embeddings = ArgumentCaptor.forClass(List.class);
        verify(chromaEmbeddingStore, times(1)).addAll(anyList(), embeddings.capture(), anyList());
        assertThat(embeddings.getValue()).hasSize(5);
    }

    // ==================== 辅助 ====================

    /** 构造一批取回切片，id 连续递增（模拟 MySQL 自增主键） */
    private List<Chunk> chunks(int count, Long documentId, Long datasourceId) {
        long base = idSequence.addAndGet(1000);
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Chunk chunk = new Chunk();
            chunk.setId(base + i);
            chunk.setDocumentId(documentId);
            chunk.setDatasourceId(datasourceId);
            chunk.setChunkIndex(i);
            chunk.setContent("片段内容 " + i);
            chunk.setStatus(Chunk.STATUS_INDEXED);
            chunk.setDeleted(0);
            chunks.add(chunk);
        }
        return chunks;
    }

    private static List<Long> ids(List<Chunk> chunks) {
        return chunks.stream().map(Chunk::getId).toList();
    }

    /** 抓取最近一次 addAll 的 segments 入参 */
    @SuppressWarnings("unchecked")
    private List<TextSegment> capturedSegments() {
        ArgumentCaptor<List<TextSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(chromaEmbeddingStore, atLeastOnce()).addAll(anyList(), anyList(), segments.capture());
        return segments.getAllValues().get(segments.getAllValues().size() - 1);
    }

    private static List<Map<String, Object>> statusCounts(String status, int count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", status);
        row.put("cnt", count);
        return List.of(row);
    }
}
