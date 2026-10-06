package org.example.agent_qr.knowledge.listener;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DataQualityPassedEvent;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.etl.entity.CanonicalRecord;
import org.example.agent_qr.etl.enums.DataType;
import org.example.agent_qr.etl.normalizer.DataNormalizer;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.entity.ChunkStructured;
import org.example.agent_qr.rag.mapper.ChunkStructuredMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataSyncEtlListener} 批量写入编排测试（批次 05 · 任务 5.2.2 / 问题 21 ②）。
 * <p>
 * 拦截的核心缺陷：ETL 管线对每条记录逐条 {@code chunkMapper.insert()}（60 万次 SQL 往返），
 * 且对每个字段逐条 {@code chunkStructuredMapper.insert()}（N 个字段 = N 次往返）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataSyncEtlListenerBatchWriteTest {

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private DataNormalizer dataNormalizer;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private ChunkStructuredMapper chunkStructuredMapper;

    @Mock
    private BatchEmbeddingService batchEmbeddingService;

    @Mock
    private ChromaEmbeddingStore chromaEmbeddingStore;

    @Mock
    private DeadLetterQueue deadLetterQueue;

    private DataSyncEtlListener listener;

    private final AtomicLong idSequence = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        listener = new DataSyncEtlListener(dataSourceMapper, dataNormalizer, chunkMapper,
                chunkStructuredMapper, batchEmbeddingService, chromaEmbeddingStore, deadLetterQueue);

        DataSourceConfig config = new DataSourceConfig();
        config.setId(55L);
        config.setSourceName("zz_b05_etl");
        when(dataSourceMapper.selectById(55L)).thenReturn(config);

        // 模拟自增主键回填（真实实现由 MyBatis useGeneratedKeys 完成）
        when(chunkMapper.insertBatch(anyList())).thenAnswer(invocation -> {
            List<Chunk> chunks = invocation.getArgument(0);
            chunks.forEach(chunk -> chunk.setId(idSequence.incrementAndGet()));
            return chunks.size();
        });
        when(chunkStructuredMapper.insertBatch(anyList())).thenAnswer(
                invocation -> ((List<?>) invocation.getArgument(0)).size());
        when(batchEmbeddingService.submit(any()))
                .thenReturn(CompletableFuture.completedFuture(new float[]{1f, 2f}));
        when(chromaEmbeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("chroma-id");
    }

    @Test
    @DisplayName("★ ETL 改为批量写入 kb_chunk：不再调用逐条 insert")
    void handleDataQualityPassed_shouldUseBatchInsert_notRowByRow() {
        when(dataNormalizer.normalize(anyList(), any(), anyString()))
                .thenReturn(records(3));

        listener.handleDataQualityPassed(event(3));

        verify(chunkMapper, times(1)).insertBatch(anyList());
        verify(chunkMapper, never()).insert(any(Chunk.class));
        verify(chunkStructuredMapper, never()).insert(any(ChunkStructured.class));
    }

    @Test
    @DisplayName("★ 超过 1000 条时按 1000/批切分（批量大小不得无上限）")
    void handleDataQualityPassed_shouldSplitIntoBatchesOf1000() {
        when(dataNormalizer.normalize(anyList(), any(), anyString()))
                .thenReturn(records(2500));

        listener.handleDataQualityPassed(event(2500));

        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkMapper, times(3)).insertBatch(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(1000, 1000, 500);
    }

    @Test
    @DisplayName("★ 结构化元数据同样改为批量写入（原实现为每字段一条 INSERT）")
    void handleDataQualityPassed_shouldBatchInsertStructuredFields() {
        when(dataNormalizer.normalize(anyList(), any(), anyString()))
                .thenReturn(records(10));

        listener.handleDataQualityPassed(event(10));

        verify(chunkStructuredMapper, times(1)).insertBatch(anyList());
        ArgumentCaptor<List<ChunkStructured>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkStructuredMapper).insertBatch(captor.capture());
        // 每条记录 2 个字段（dept / salary）
        assertThat(captor.getValue()).hasSize(20);
        assertThat(captor.getValue()).allSatisfy(cs -> assertThat(cs.getChunkId()).isNotNull());
    }

    @Test
    @DisplayName("★ 批量校验失败时保留 DLQ 降级入口，且不为未入库切片提交向量化")
    void handleDataQualityPassed_shouldKeepDlqEntryPoint_whenBatchInsertFails() {
        when(dataNormalizer.normalize(anyList(), any(), anyString()))
                .thenReturn(records(5));
        when(chunkMapper.insertBatch(anyList()))
                .thenThrow(new RuntimeException("Duplicate entry for key 'PRIMARY'"));

        listener.handleDataQualityPassed(event(5));

        verify(deadLetterQueue, atLeastOnce()).enqueue(eq(DlqMessage.EVENT_ETL), eq(55L), anyString(), any());
        verify(batchEmbeddingService, never()).submit(any());
    }

    @Test
    @DisplayName("向量化调用点保持原样（批次 07 才改）：切片入库后仍逐条 submit → 写 ChromaDB")
    void handleDataQualityPassed_shouldStillSubmitToBatchEmbeddingService() {
        when(dataNormalizer.normalize(anyList(), any(), anyString()))
                .thenReturn(records(4));

        listener.handleDataQualityPassed(event(4));

        verify(batchEmbeddingService, times(4)).submit(any());
        verify(chromaEmbeddingStore, times(4)).add(any(Embedding.class), any(TextSegment.class));
        verify(chunkMapper, times(4)).updateById(any(Chunk.class));
    }

    @Test
    @DisplayName("无通过质检数据时直接跳过，不做任何写入")
    void handleDataQualityPassed_shouldSkip_whenNoData() {
        listener.handleDataQualityPassed(new DataQualityPassedEvent("{}", List.of(), 55L, "b-1"));

        verify(dataSourceMapper, never()).selectById(anyLong());
        verify(chunkMapper, never()).insertBatch(anyList());
    }

    // ==================== 辅助 ====================

    private DataQualityPassedEvent event(int count) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("dept", "HR");
            raw.add(row);
        }
        return new DataQualityPassedEvent("{}", raw, 55L, "zz_b05_batch");
    }

    private List<CanonicalRecord> records(int count) {
        List<CanonicalRecord> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("dept", "HR");
            metadata.put("salary", 10000 + i);
            records.add(CanonicalRecord.builder()
                    .sourceSystem("zz_b05_etl")
                    .domain("HR")
                    .dataType(DataType.STRUCTURED)
                    .canonicalText("员工 " + i + " 属于 HR 部门")
                    .metadata(metadata)
                    .datasourceId(55L)
                    .syncBatchId("zz_b05_batch")
                    .build());
        }
        return records;
    }
}
