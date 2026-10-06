package org.example.agent_qr.knowledge.listener;

import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.ChunksBatchCreatedEvent;
import org.example.agent_qr.common.event.DataQualityPassedEvent;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.etl.entity.CanonicalRecord;
import org.example.agent_qr.etl.enums.DataType;
import org.example.agent_qr.etl.normalizer.DataNormalizer;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.rag.entity.ChunkStructured;
import org.example.agent_qr.rag.mapper.ChunkStructuredMapper;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private DeadLetterQueue deadLetterQueue;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BM25Retriever bm25Retriever;

    private DataSyncEtlListener listener;

    private final AtomicLong idSequence = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        listener = new DataSyncEtlListener(dataSourceMapper, dataNormalizer, chunkMapper,
                chunkStructuredMapper, deadLetterQueue, eventPublisher, bm25Retriever);

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
    }

    @Test
    @DisplayName("★ 批次 07 · 7.0.8：数据同步链路改为发布 ChunksBatchCreatedEvent，不再直接 submit")
    void handleDataQualityPassed_shouldPublishChunksBatchCreatedEvent() {
        when(dataNormalizer.normalize(anyList(), any(), anyString()))
                .thenReturn(records(4));

        listener.handleDataQualityPassed(event(4));

        ArgumentCaptor<ChunksBatchCreatedEvent> captor =
                ArgumentCaptor.forClass(ChunksBatchCreatedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        ChunksBatchCreatedEvent published = captor.getValue();
        assertThat(published.getDatasourceId()).isEqualTo(55L);
        assertThat(published.getSyncBatchId()).isEqualTo("zz_b05_batch");
        assertThat(published.getDocumentId()).isNull();
        assertThat(published.hasTarget()).isTrue();
        // 事件只带标识，不携带切片列表
        assertThat(published).hasNoNullFieldsOrPropertiesExcept("documentId");
    }

    @Test
    @DisplayName("★ 批次 07 · 7.0.8：切片入库状态为 INDEXED（不是 READY）")
    void handleDataQualityPassed_shouldInsertChunksAsIndexed() {
        when(dataNormalizer.normalize(anyList(), any(), anyString()))
                .thenReturn(records(3));

        listener.handleDataQualityPassed(event(3));

        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkMapper).insertBatch(captor.capture());
        assertThat(captor.getValue())
                .as("切片写入即 INDEXED：BM25 可检索但向量未写，不得提前置 READY")
                .allSatisfy(chunk -> assertThat(chunk.getStatus()).isEqualTo(Chunk.STATUS_INDEXED));
    }

    @Test
    @DisplayName("无通过质检数据时不发布向量化事件")
    void handleDataQualityPassed_shouldNotPublishEvent_whenNoData() {
        listener.handleDataQualityPassed(new DataQualityPassedEvent("{}", List.of(), 55L, "b-1"));

        verify(eventPublisher, never()).publishEvent(any(ChunksBatchCreatedEvent.class));
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
