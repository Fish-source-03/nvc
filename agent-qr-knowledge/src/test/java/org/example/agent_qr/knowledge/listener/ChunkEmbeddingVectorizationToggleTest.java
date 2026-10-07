package org.example.agent_qr.knowledge.listener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.event.ChunksBatchCreatedEvent;
import org.example.agent_qr.common.event.EmbeddingCompletedEvent;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.splitter.TextSplitter;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code rag.embedding.write-to-chromadb} 接线测试（批次 10 · 任务 10.5.3，问题 38）。
 * <p>
 * 语义（已确认的决策）：关闭 = <b>跳过向量化</b>——切片停留在 {@code INDEXED}
 * （已入库、BM25 可搜、向量未写），不写 ChromaDB，文档不推进到 READY。
 * 修复前该键<b>无任何读取点</b>，且注释（"P1: 关闭 ChromaDB 写入"）与取值（true）自相矛盾。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChunkEmbeddingVectorizationToggleTest {

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
    private Logger listenerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        listener = new ChunkEmbeddingBatchListener(documentMapper, chunkMapper, dataSourceMapper,
                textSplitter, eventPublisher, deadLetterQueue, batchEmbeddingService,
                chromaEmbeddingStore, chromaRetriever, bm25Retriever);

        when(batchEmbeddingService.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(new float[]{0.1f, 0.2f}));
        when(chromaRetriever.findVectorIdsByChunkIds(any())).thenReturn(Map.of());
        when(bm25Retriever.findMissingChunkIds(any())).thenReturn(List.of());
        when(chunkMapper.updateStatusByIds(anyList(), anyString())).thenReturn(1);
        when(chunkMapper.updateById(any(Chunk.class))).thenReturn(1);
        when(chunkMapper.countByDocumentIdGroupByStatus(any())).thenReturn(List.of());

        listenerLogger = (Logger) LoggerFactory.getLogger(ChunkEmbeddingBatchListener.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        listenerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("★ write-to-chromadb=false：不向量化、不写 ChromaDB，切片保持 INDEXED，并留 WARN 日志")
    void handleChunksBatchCreated_shouldSkipVectorization_whenDisabled() {
        ReflectionTestUtils.setField(listener, "writeToChromaDb", false);

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        verify(batchEmbeddingService, never()).submit(any());
        verify(chromaEmbeddingStore, never()).addAll(anyList(), anyList(), anyList());
        verify(chunkMapper, never()).updateStatusByIds(anyList(), anyString());
        verify(chunkMapper, never()).selectPendingByDocumentIdAfterId(any(), anyLong(), anyInt());
        // 不发布"向量化完成"事件（切片根本没有被向量化）
        verify(eventPublisher, never()).publishEvent(any(EmbeddingCompletedEvent.class));

        assertThat(logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage))
                .as("关闭向量化必须留下 WARN（运维要能知道这批数据没写入向量）")
                .anySatisfy(message -> assertThat(message)
                        .contains("rag.embedding.write-to-chromadb=false")
                        .contains("INDEXED"));
    }

    @Test
    @DisplayName("回归：write-to-chromadb 默认 true → 正常向量化并写 ChromaDB、置 READY、发布完成事件")
    void handleChunksBatchCreated_shouldVectorize_whenEnabled() {
        ReflectionTestUtils.setField(listener, "writeToChromaDb", true);

        Chunk chunk = new Chunk();
        chunk.setId(11771L);
        chunk.setContent("内容");
        when(chunkMapper.selectPendingByDocumentIdAfterId(any(), anyLong(), anyInt()))
                .thenReturn(List.of(chunk), List.of());

        listener.handleChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(9L));

        verify(batchEmbeddingService).submit(chunk);
        verify(chromaEmbeddingStore).addAll(anyList(), anyList(), anyList());

        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(chunkMapper, org.mockito.Mockito.atLeastOnce()).updateStatusByIds(idsCaptor.capture(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).contains(Chunk.STATUS_READY);
        verify(eventPublisher).publishEvent(any(EmbeddingCompletedEvent.class));
    }
}
