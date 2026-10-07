package org.example.agent_qr.knowledge.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.ChunksBatchCreatedEvent;
import org.example.agent_qr.common.event.DataQualityPassedEvent;
import org.example.agent_qr.common.util.FingerprintUtils;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.etl.entity.CanonicalRecord;
import org.example.agent_qr.etl.normalizer.DataNormalizer;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.rag.entity.ChunkStructured;
import org.example.agent_qr.rag.mapper.ChunkStructuredMapper;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
 *   <li><b>批量</b>创建 {@link Chunk} 并入库（批次 05 · 任务 5.2.2，1000 条/批；状态 {@code INDEXED}）</li>
 *   <li>发布 {@link ChunksBatchCreatedEvent} 触发向量化（批次 07 · 任务 7.0b）</li>
 *   <li><b>批量</b>提取结构化元数据写入 {@code kb_chunk_structured} 表</li>
 * </ol>
 * 失败时通过 {@link DeadLetterQueue} 入队待重试。
 * </p>
 * <p>
 * <b>改造边界（批次 05 → 批次 07）</b>：批次 05 只把"逐条 INSERT"改为"批量 INSERT"
 * 并留下发布点；批次 07 任务 7.0b 在该发布点接入事件，<b>移除</b>原先"逐条
 * {@code batchEmbeddingService.submit} + 逐条 {@code chromaEmbeddingStore.add}"的实现——
 * 向量化整体交由 {@code ChunkEmbeddingBatchListener} 从 MySQL 分批读回后批量完成，
 * 数据同步链路因此不再持有向量化职责。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncEtlListener {

    /** 每批最多写入 1000 条（方案文档 §2.2 建议值，禁止无上限） */
    static final int BATCH_SIZE = 1000;

    private final DataSourceMapper dataSourceMapper;
    private final DataNormalizer dataNormalizer;
    private final ChunkMapper chunkMapper;
    private final ChunkStructuredMapper chunkStructuredMapper;
    private final DeadLetterQueue deadLetterQueue;
    private final ApplicationEventPublisher eventPublisher;
    private final BM25Retriever bm25Retriever;

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

            // 3a. 构造切片实体（仅构造，不写库）
            List<Chunk> chunks = buildChunks(records, datasourceId, passedData);

            // 3b. 分批写入 kb_chunk（自增主键回填到每个 chunk.id）
            int insertedChunks = batchInsertChunks(chunks, datasourceId, batchId);

            // 3c. 用回填后的 chunkId 构造结构化元数据并分批写入 kb_chunk_structured
            List<ChunkStructured> structuredList = buildStructuredMetadata(records, chunks);
            batchInsertStructured(structuredList, datasourceId, batchId);

            log.info("ETL MySQL 批量写入完成: datasourceId={}, batchId={}, chunks={}, structuredFields={}",
                    datasourceId, batchId, insertedChunks, structuredList.size());

            // 3d. 发布方尽力更新 BM25 索引（批次 07 · 任务 7.0.17）：
            //     切片入库即 INDEXED，关键词检索应立刻可用；失败只记 WARN 不阻断，
            //     Listener 侧的校验补写会兜底（任务 7.0.18）。
            updateBm25BestEffort(chunks, datasourceId, batchId);

            // 4. 发布批量创建事件（批次 07 · 任务 7.0b）
            //    切片已入库（状态 INDEXED：BM25 可检索，向量未写），
            //    由 ChunkEmbeddingBatchListener 从 MySQL 分批读回并批量向量化。
            //    事件粒度=每次数据源同步一次，载荷只带标识（不带切片列表）。
            eventPublisher.publishEvent(ChunksBatchCreatedEvent.forDatasource(datasourceId, batchId));

            log.info("数据同步 ETL 处理完成: datasourceId={}, batchId={}, totalRecords={}, insertedChunks={}, 已发布向量化事件",
                    datasourceId, batchId, records.size(), insertedChunks);

        } catch (Exception e) {
            log.error("数据同步 ETL 处理失败: datasourceId={}, batchId={}, error={}",
                    datasourceId, batchId, e.getMessage(), e);
            String payload = String.format("{\"datasourceId\":%d,\"batchId\":\"%s\",\"recordCount\":%d}",
                    datasourceId, batchId, passedData.size());
            deadLetterQueue.enqueue(DlqMessage.EVENT_ETL, datasourceId, payload, e);
        }
    }

    /**
     * 标准化记录 → Chunk 实体列表（仅构造，不写库）。
     * <p>
     * 下标与 {@code records} / {@code passedData} 严格对齐，供后续回填 chunkId 使用。
     * </p>
     *
     * @param records      标准化记录
     * @param datasourceId 数据源 ID
     * @param passedData   通过质量检查的原始数据（用于计算指纹）
     * @return 切片实体列表
     */
    private List<Chunk> buildChunks(List<CanonicalRecord> records, Long datasourceId,
                                    List<Map<String, Object>> passedData) {
        List<Chunk> chunks = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            CanonicalRecord record = records.get(i);
            Chunk chunk = new Chunk();
            // ⚠️ R39（批次 11 收尾清单）：Chunk.contentType / tableCaption 是**预留字段
            //    （当前无写入方、无读取方）**，本处刻意不填——本链路走
            //    ChunkMapper#insertBatch，其手写 INSERT 的列清单不含这两列，只 set 不补列
            //    等于"看起来接线、实际不落库"；且本链路的 canonicalText 是自然语言段落
            //    （StructuredDataConverter），不含表格标记，语义上恒为 TEXT。详见 Chunk 实体注释。
            chunk.setDocumentId(null);          // 数据同步管线：无关联文档
            chunk.setDatasourceId(datasourceId);
            chunk.setChunkIndex(i);
            chunk.setContent(record.getCanonicalText());
            chunk.setCharCount(record.getCanonicalText() != null
                    ? record.getCanonicalText().length() : 0);
            chunk.setChromaId("pending");
            // 批次 07 · 任务 7.0.8：写入即"已入库"——BM25 可检索，向量尚未写入
            chunk.setStatus(Chunk.STATUS_INDEXED);
            chunk.setDeleted(0);
            // 写入原始记录的 MD5 指纹（供后续跨批次去重使用）
            if (i < passedData.size()) {
                chunk.setRecordHash(
                        FingerprintUtils.computeRecordFingerprint(passedData.get(i)));
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * 分批写入切片（1000 条/批）。
     * <p>
     * 某一批失败时：记录日志、入一次 DLQ（保留批量失败时的降级入口），
     * 并把该批切片的主键置空以便调用方跳过其后续向量化——不影响其他批次。
     * </p>
     *
     * @param chunks       切片列表
     * @param datasourceId 数据源 ID
     * @param batchId      同步批次 ID
     * @return 实际写入的行数
     */
    private int batchInsertChunks(List<Chunk> chunks, Long datasourceId, String batchId) {
        int inserted = 0;
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<Chunk> slice = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            try {
                inserted += chunkMapper.insertBatch(slice);
            } catch (Exception e) {
                log.error("ETL 批量写入切片失败: datasourceId={}, batchId={}, fromIndex={}, size={}, error={}",
                        datasourceId, batchId, i, slice.size(), e.getMessage(), e);
                String payload = String.format(
                        "{\"datasourceId\":%d,\"batchId\":\"%s\",\"fromIndex\":%d,\"size\":%d}",
                        datasourceId, batchId, i, slice.size());
                deadLetterQueue.enqueue(DlqMessage.EVENT_ETL, datasourceId, payload, e);
                slice.forEach(chunk -> chunk.setId(null));
            }
        }
        return inserted;
    }

    /**
     * 分批写入结构化字段（1000 行/批）。
     *
     * @param structuredList 结构化字段列表
     * @param datasourceId   数据源 ID
     * @param batchId        同步批次 ID
     */
    private void batchInsertStructured(List<ChunkStructured> structuredList,
                                       Long datasourceId, String batchId) {
        for (int i = 0; i < structuredList.size(); i += BATCH_SIZE) {
            List<ChunkStructured> slice =
                    structuredList.subList(i, Math.min(i + BATCH_SIZE, structuredList.size()));
            try {
                chunkStructuredMapper.insertBatch(slice);
            } catch (Exception e) {
                log.error("ETL 批量写入结构化字段失败: datasourceId={}, batchId={}, fromIndex={}, size={}, error={}",
                        datasourceId, batchId, i, slice.size(), e.getMessage(), e);
                String payload = String.format(
                        "{\"datasourceId\":%d,\"batchId\":\"%s\",\"structuredFromIndex\":%d,\"size\":%d}",
                        datasourceId, batchId, i, slice.size());
                deadLetterQueue.enqueue(DlqMessage.EVENT_ETL, datasourceId, payload, e);
            }
        }
    }

    /**
     * 发布方尽力更新 BM25 索引（批次 07 · 任务 7.0.17）。
     * <p>
     * 只索引主键非空的切片（写入失败被置空主键的那些已入 DLQ，不在此列）。
     * 更新失败仅记 WARN，<b>不阻断主流程</b>——消费方的校验补写保证最终一致。
     * </p>
     *
     * @param chunks       切片列表
     * @param datasourceId 数据源 ID
     * @param batchId      同步批次 ID
     */
    private void updateBm25BestEffort(List<Chunk> chunks, Long datasourceId, String batchId) {
        List<Chunk> persisted = chunks.stream().filter(chunk -> chunk.getId() != null).toList();
        if (persisted.isEmpty()) {
            return;
        }
        try {
            int indexed = bm25Retriever.addBatchToIndex(persisted);
            log.info("BM25 索引已更新（发布方）: datasourceId={}, batchId={}, 切片数={}",
                    datasourceId, batchId, indexed);
        } catch (Exception e) {
            log.warn("BM25 索引更新失败（发布方尽力而为，等待 Listener 补写）: datasourceId={}, batchId={}, error={}",
                    datasourceId, batchId, e.getMessage());
        }
    }

    /**
     * 将 CanonicalRecord 的结构化元数据构造为待插入实体列表。
     * <p>
     * 自动识别字段类型：数值 → NUMBER、日期 → DATE、
     * 有字典映射 → ENUM、其他 → STRING。
     * </p>
     *
     * @param records 标准化记录
     * @param chunks  已回填主键的切片（与 records 下标对齐）
     * @return 待批量插入的结构化字段列表
     */
    private List<ChunkStructured> buildStructuredMetadata(List<CanonicalRecord> records,
                                                          List<Chunk> chunks) {
        List<ChunkStructured> result = new ArrayList<>();
        for (int i = 0; i < records.size() && i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (chunk.getId() == null) {
                continue;
            }
            CanonicalRecord record = records.get(i);
            Map<String, Object> metadata = record.getMetadata();
            if (metadata == null || metadata.isEmpty()) {
                continue;
            }
            String domain = record.getDomain();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String fieldName = entry.getKey();
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }

                ChunkStructured cs = new ChunkStructured();
                cs.setChunkId(chunk.getId());
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

                result.add(cs);
            }
        }
        return result;
    }
}
