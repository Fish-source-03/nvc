package org.example.agent_qr.knowledge.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DataQualityPassedEvent;
import org.example.agent_qr.common.util.FingerprintUtils;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.etl.entity.CanonicalRecord;
import org.example.agent_qr.etl.normalizer.DataNormalizer;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.entity.ChunkStructured;
import org.example.agent_qr.rag.mapper.ChunkStructuredMapper;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 数据同步 ETL 事件监听器。
 * <p>
 * 监听 {@link DataQualityPassedEvent}（由 data-quality 模块在质量检查通过后发布），
 * 驱动完整的 ETL → 切片 → 向量化 → 结构化元数据存储管线：
 * <ol>
 *   <li>从数据库获取 {@link DataSourceConfig}</li>
 *   <li>调用 {@link DataNormalizer#normalize} 将原始数据转为标准化文本</li>
 *   <li>为每条 {@link CanonicalRecord} 创建 {@link Chunk} 并入库</li>
 *   <li>提交到 {@link BatchEmbeddingService} 批量向量化</li>
 *   <li>提取结构化元数据写入 {@code kb_chunk_structured} 表</li>
 * </ol>
 * 失败时通过 {@link DeadLetterQueue} 入队待重试。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncEtlListener {

    private final DataSourceMapper dataSourceMapper;
    private final DataNormalizer dataNormalizer;
    private final ChunkMapper chunkMapper;
    private final ChunkStructuredMapper chunkStructuredMapper;
    private final BatchEmbeddingService batchEmbeddingService;
    private final ChromaEmbeddingStore chromaEmbeddingStore;
    private final DeadLetterQueue deadLetterQueue;

    /**
     * 处理数据质量通过事件：执行 ETL 标准化并接入知识库。
     *
     * @param event 质量通过事件（携带 passedData、datasourceId、syncBatchId）
     */
    @Async("chunkExecutor")
    @EventListener
    public void handleDataQualityPassed(DataQualityPassedEvent event) {
        Long datasourceId = event.getDatasourceId();
        String batchId = event.getSyncBatchId();
        List<Map<String, Object>> passedData = event.getPassedData();

        if (passedData == null || passedData.isEmpty()) {
            log.info("ETL 跳过：无通过质量检查的数据, datasourceId={}, batchId={}", datasourceId, batchId);
            return;
        }

        log.info("开始数据同步 ETL 处理: datasourceId={}, batchId={}, recordCount={}",
                datasourceId, batchId, passedData.size());

        try {
            // 1. 获取数据源配置
            DataSourceConfig config = dataSourceMapper.selectById(datasourceId);
            if (config == null) {
                log.error("ETL 失败：数据源配置不存在, datasourceId={}", datasourceId);
                deadLetterQueue.enqueue(DlqMessage.EVENT_ETL, datasourceId,
                        String.format("{\"datasourceId\":%d,\"batchId\":\"%s\"}", datasourceId, batchId),
                        new RuntimeException("数据源配置不存在: id=" + datasourceId));
                return;
            }

            // 2. ETL 标准化：rawData → CanonicalRecord（含自然语言文本 + 结构化元数据）
            List<CanonicalRecord> records = dataNormalizer.normalize(passedData, config, batchId);
            log.info("ETL 标准化完成: datasourceId={}, recordCount={}", datasourceId, records.size());

            // 3. 为每条标准化记录创建切片 → 入库 → 向量化 → 写结构化元数据
            int successCount = 0;
            for (int i = 0; i < records.size(); i++) {
                CanonicalRecord record = records.get(i);
                try {
                    // 3a. 创建切片
                    Chunk chunk = new Chunk();
                    chunk.setDocumentId(null);          // 数据同步管线：无关联文档
                    chunk.setDatasourceId(datasourceId);
                    chunk.setChunkIndex(i);
                    chunk.setContent(record.getCanonicalText());
                    chunk.setCharCount(record.getCanonicalText() != null
                            ? record.getCanonicalText().length() : 0);
                    chunk.setChromaId("pending");
                    chunk.setDeleted(0);
                    // 写入原始记录的 MD5 指纹（供后续跨批次去重使用）
                    if (i < passedData.size()) {
                        chunk.setRecordHash(
                                FingerprintUtils.computeRecordFingerprint(passedData.get(i)));
                    }
                    chunkMapper.insert(chunk);

                    // 3b. 提交批量向量化（异步完成，回调写入 ChromaDB 并更新 chromaId）
                    final String sourceName = config.getSourceName();
                    batchEmbeddingService.submit(chunk)
                            .thenAccept(vector -> {
                                try {
                                    // 写入 ChromaDB
                                    Embedding embedding = new Embedding(vector);
                                    TextSegment segment = TextSegment.from(
                                            chunk.getContent(),
                                            new Metadata(Map.of("chunk_id", chunk.getId().toString(),
                                                   "datasource_id", datasourceId.toString(),
                                                   "document_title", sourceName != null ? sourceName : "数据源")));
                                    String chromaId = chromaEmbeddingStore.add(embedding, segment);
                                    chunk.setChromaId(chromaId);
                                    chunkMapper.updateById(chunk);
                                    log.debug("ChromaDB 向量写入成功: chunkId={}, chromaId={}", chunk.getId(), chromaId);
                                } catch (Exception ex) {
                                    log.error("ChromaDB 向量写入失败: chunkId={}, datasourceId={}, error={}",
                                            chunk.getId(), datasourceId, ex.getMessage());
                                    String payload = String.format(
                                            "{\"chunkId\":%d,\"datasourceId\":%d,\"batchId\":\"%s\"}",
                                            chunk.getId(), datasourceId, batchId);
                                    deadLetterQueue.enqueue(DlqMessage.EVENT_CHROMA_WRITE, datasourceId, payload, ex);
                                }
                            })
                            .exceptionally(ex -> {
                                log.error("数据切片向量化失败: chunkId={}, datasourceId={}, error={}",
                                        chunk.getId(), datasourceId, ex.getMessage());
                                String payload = String.format(
                                        "{\"chunkId\":%d,\"datasourceId\":%d,\"batchId\":\"%s\"}",
                                        chunk.getId(), datasourceId, batchId);
                                deadLetterQueue.enqueue(DlqMessage.EVENT_EMBED, datasourceId, payload, ex);
                                return null;
                            });

                    // 3c. 写入结构化元数据（用于 RAG 检索时 MySQL B+ 树前置过滤）
                    saveStructuredMetadata(chunk.getId(), record);

                    successCount++;
                } catch (Exception e) {
                    log.error("ETL 切片创建失败: datasourceId={}, index={}, error={}",
                            datasourceId, i, e.getMessage(), e);
                }
            }

            log.info("数据同步 ETL 处理完成: datasourceId={}, batchId={}, totalRecords={}, successChunks={}",
                    datasourceId, batchId, records.size(), successCount);

        } catch (Exception e) {
            log.error("数据同步 ETL 处理失败: datasourceId={}, batchId={}, error={}",
                    datasourceId, batchId, e.getMessage(), e);
            String payload = String.format("{\"datasourceId\":%d,\"batchId\":\"%s\",\"recordCount\":%d}",
                    datasourceId, batchId, passedData.size());
            deadLetterQueue.enqueue(DlqMessage.EVENT_ETL, datasourceId, payload, e);
        }
    }

    /**
     * 将 CanonicalRecord 的结构化元数据写入 kb_chunk_structured 表。
     * <p>
     * 自动识别字段类型：数值 → NUMBER、日期 → DATE、
     * 有字典映射 → ENUM、其他 → STRING。
     * </p>
     */
    private void saveStructuredMetadata(Long chunkId, CanonicalRecord record) {
        Map<String, Object> metadata = record.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return;
        }

        String domain = record.getDomain();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            ChunkStructured cs = new ChunkStructured();
            cs.setChunkId(chunkId);
            cs.setDomain(domain);
            cs.setFieldName(fieldName);
            cs.setFieldValue(value.toString());

            // 自动识别字段类型
            if (value instanceof Number) {
                cs.setFieldType(ChunkStructured.TYPE_NUMBER);
                cs.setNumericValue(new BigDecimal(value.toString()));
            } else if (value instanceof String strVal) {
                // 尝试解析为数值
                try {
                    cs.setNumericValue(new BigDecimal(strVal));
                    cs.setFieldType(ChunkStructured.TYPE_NUMBER);
                } catch (NumberFormatException nfe1) {
                    // 尝试解析为日期
                    try {
                        cs.setDateValue(LocalDate.parse(strVal));
                        cs.setFieldType(ChunkStructured.TYPE_DATE);
                    } catch (DateTimeParseException nfe2) {
                        cs.setFieldType(ChunkStructured.TYPE_STRING);
                    }
                }
            } else {
                cs.setFieldType(ChunkStructured.TYPE_STRING);
            }

            chunkStructuredMapper.insert(cs);
        }
    }
}
