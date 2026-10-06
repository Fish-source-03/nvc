package org.example.agent_qr.knowledge.listener;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.example.agent_qr.knowledge.service.DocumentQueryService;
import org.example.agent_qr.knowledge.splitter.TextSplitter;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 切片批量向量化监听器（批次 07 · 任务 7.0.7）—— 取代已退役的
 * {@code ChunkEmbeddingListener}。
 * <p>
 * 本类承担两条链路汇合后的<b>全部</b>向量化职责，入口有两个：
 * <ol>
 *   <li>{@link DocumentParsedEvent}（文档上传链路 / DLQ 重放 CHUNK）：
 *       文本切片 → 批量写入 MySQL（状态 {@code INDEXED}）→ 发布
 *       {@link ChunksBatchCreatedEvent}；</li>
 *   <li>{@link ChunksBatchCreatedEvent}（两条链路共用）：从 MySQL <b>分批</b>读回
 *       待向量化切片 → 批量向量化 → 批量写入 ChromaDB → 回写切片状态。</li>
 * </ol>
 * </p>
 *
 * <h3>从 {@code ChunkEmbeddingListener} 迁移过来的职责（逐条核对，任务 7.0.9）</h3>
 * <ol>
 *   <li><b>文档状态流转</b>：{@code CHUNKING} → {@code INDEXED} → {@code EMBEDDING} → {@code READY}。
 *       原实现把 {@code EMBEDDING → READY} 写在了"提交成功"之后（问题 28 的根因），
 *       现改为<b>向量真正写入 ChromaDB 之后</b>才置 READY；</li>
 *   <li><b>DLQ 入队</b>：切片阶段失败入 {@code CHUNK}；向量化失败入 {@code EMBED}；
 *       ChromaDB 写入失败入 {@code CHROMA_WRITE}（三类均保留）；</li>
 *   <li><b>失败回写 {@code FAILED}</b>：切片阶段失败时回写文档状态与错误信息；</li>
 *   <li><b>{@code successCount} 计数语义修正</b>：原实现统计的是"提交成功的切片数"
 *       （{@code submit} 返回即计数），现改为<b>向量化并成功写入 ChromaDB 的切片数</b>，
 *       并据此发布 {@link EmbeddingCompletedEvent}（statistics 模块的消费口径随之修正）；</li>
 *   <li><b>元数据修正</b>：{@code document_title} 原取自 {@code doc.getFileName()}
 *       （文件名，含扩展名且与标题可能不一致），现改为 {@code doc.getTitle()}
 *       （标题为空时回退文件名，再回退 {@code doc-<id>}）。</li>
 * </ol>
 *
 * <h3>分批读取（硬要求）</h3>
 * <p>
 * 待向量化切片用 <b>keyset 分页</b>（{@code id > afterId}）逐批读回，单批上限
 * {@value #BATCH_SIZE} 条——数据同步场景单次可能产生几十万条切片，
 * 一次性 {@code findAll} 会 OOM。之所以不用 OFFSET：处理过程中切片会被置为 READY
 * 从而退出过滤集，OFFSET 分页会因此跳行。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkEmbeddingBatchListener {

    /**
     * 单批读回的切片数上限。
     * <p>200 条 × 2560 维 float ≈ 2 MB 向量，加上文本与元数据仍在单次 HTTP 请求的
     * 合理范围内；同时保证几十万条切片的场景不会一次性进内存。</p>
     */
    static final int BATCH_SIZE = 200;

    /** 切片尚未写入 ChromaDB 时的占位值（与 {@code DlqRetryScheduler} 保持一致） */
    static final String CHROMA_ID_PENDING = "pending";

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final DataSourceMapper dataSourceMapper;
    private final TextSplitter textSplitter;
    private final ApplicationEventPublisher eventPublisher;
    private final DeadLetterQueue deadLetterQueue;
    private final BatchEmbeddingService batchEmbeddingService;
    private final ChromaEmbeddingStore chromaEmbeddingStore;
    private final ChromaRetriever chromaRetriever;
    private final BM25Retriever bm25Retriever;

    // ==================== 入口 1：切片 ====================

    /**
     * 处理文档解析完成事件：文本切片 → 入库（INDEXED）→ 发布批量创建事件。
     * <p>
     * ⚠️ 本方法同时服务 DLQ 的 {@code CHUNK} 重放（{@code DlqRetryScheduler#retryChunk}
     * 重新发布 {@link DocumentParsedEvent}），因此<b>不得</b>改为只处理新上传文档。
     * </p>
     *
     * @param event 文档解析完成事件
     */
    @Async("chunkExecutor")
    @EventListener
    public void handleDocumentParsed(DocumentParsedEvent event) {
        Long documentId = event.getDocumentId();
        log.info("开始处理文档切片与索引: id={}", documentId);

        try {
            // 1. 更新状态为 CHUNKING
            documentMapper.updateStatus(documentId, DocumentStatus.CHUNKING.name());

            // 2. 文本切片
            List<String> chunkTexts = textSplitter.split(event.getContent());
            log.info("文本切片完成: documentId={}, 切片数={}", documentId, chunkTexts.size());

            // 3. 保存切片到数据库 —— 状态 INDEXED（已入库，BM25 可检索，向量未写）
            List<Chunk> savedChunks = new ArrayList<>(chunkTexts.size());
            for (int i = 0; i < chunkTexts.size(); i++) {
                Chunk chunk = new Chunk();
                chunk.setDocumentId(documentId);
                chunk.setChunkIndex(i);
                chunk.setContent(chunkTexts.get(i));
                chunk.setCharCount(chunkTexts.get(i).length());
                chunk.setChromaId(CHROMA_ID_PENDING);
                chunk.setStatus(Chunk.STATUS_INDEXED);
                chunk.setDeleted(0);
                chunkMapper.insert(chunk);
                savedChunks.add(chunk);
            }

            // 4. 发布切片创建事件（既有契约，保持不变）
            eventPublisher.publishEvent(new ChunksCreatedEvent(this, documentId, chunkTexts));

            // 5. 发布方尽力更新 BM25 索引（任务 7.0.17）：此刻 INDEXED 成立，关键词即可检索。
            //    失败只记 WARN，不阻断主流程——Listener 侧的校验补写会兜底（任务 7.0.18）。
            addToBm25BestEffort(savedChunks, "document:" + documentId);

            // 6. 文档状态 → INDEXED（已入库，关键词可搜；向量化尚未开始）
            documentMapper.updateStatus(documentId, DocumentStatus.INDEXED.name());

            // 7. 发布批量创建事件 → 触发向量化（本类的另一个入口消费）
            eventPublisher.publishEvent(ChunksBatchCreatedEvent.forDocument(documentId));
            log.info("文档切片入库完成，已发布向量化事件: documentId={}, 切片数={}", documentId, chunkTexts.size());

        } catch (Exception e) {
            log.error("文档切片处理失败: id={}, error={}", documentId, e.getMessage(), e);
            documentMapper.updateStatus(documentId, DocumentStatus.FAILED.name());
            documentMapper.updateErrorMsg(documentId, "处理失败: " + e.getMessage());

            String payload = String.format("{\"documentId\":%d}", documentId);
            deadLetterQueue.enqueue(DlqMessage.EVENT_CHUNK, documentId, payload, e);
        }
    }

    // ==================== 入口 2：向量化 ====================

    /**
     * 处理切片批量创建事件：分批读回待向量化切片并写入 ChromaDB。
     * <p>
     * 使用 {@code embedExecutor}（而非 {@code chunkExecutor}）：本方法会阻塞等待
     * 攒批向量化结果，占用向量化专用线程池更合适。
     * </p>
     *
     * @param event 批量创建事件
     */
    @Async("embedExecutor")
    @EventListener
    public void handleChunksBatchCreated(ChunksBatchCreatedEvent event) {
        if (event == null || !event.hasTarget()) {
            log.warn("收到无效的切片批量创建事件（缺少 documentId/datasourceId），已忽略: {}", event);
            return;
        }

        int processed = 0;
        long afterId = 0;
        try {
            while (true) {
                List<Chunk> batch = readNextBatch(event, afterId, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                afterId = batch.get(batch.size() - 1).getId();
                processed += embedAndWrite(event, batch);
                if (batch.size() < BATCH_SIZE) {
                    break;      // 已到末尾，少一次空查询
                }
            }
            log.info("向量化批次处理完成: {}, 已就绪切片={}", event.describeTarget(), processed);
        } catch (Exception e) {
            log.error("向量化批次处理失败: {}, error={}", event.describeTarget(), e.getMessage(), e);
        } finally {
            refreshDocumentStatus(event.getDocumentId());
            if (event.getDocumentId() != null) {
                // 计数语义：向量化并成功写入 ChromaDB 的切片数（原实现统计"提交成功"）
                eventPublisher.publishEvent(
                        new EmbeddingCompletedEvent(this, event.getDocumentId(), processed));
            }
        }
    }

    /**
     * 分批读取下一批待向量化切片（keyset 分页）。
     *
     * @param event   事件（携带 documentId 或 datasourceId）
     * @param afterId 上一批的最大切片 ID（首批传 0）
     * @param limit   单批上限
     * @return 待向量化切片（按 id 升序）；无则返回空列表
     */
    private List<Chunk> readNextBatch(ChunksBatchCreatedEvent event, long afterId, int limit) {
        if (event.getDocumentId() != null) {
            return chunkMapper.selectPendingByDocumentIdAfterId(event.getDocumentId(), afterId, limit);
        }
        return chunkMapper.selectPendingByDatasourceIdAfterId(event.getDatasourceId(), afterId, limit);
    }

    /**
     * 向量化一批切片并写入 ChromaDB，成功后把切片置为 READY。
     * <p>
     * <b>失败语义：整批失败</b>——任一向量化任务失败则不写任何一条（避免半批成功导致
     * 状态与实际向量不一致），整批回退为 {@code INDEXED} 并<b>只入一次</b> DLQ。
     * 切片已在 BM25 索引中，回退到 INDEXED 后关键词检索仍可用。
     * </p>
     *
     * @param event 事件（用于构造批次级 DLQ payload）
     * @param batch 本批切片（非空）
     * @return 成功写入向量并被置为 READY 的切片数（失败时为 0）
     */
    private int embedAndWrite(ChunksBatchCreatedEvent event, List<Chunk> batch) {
        List<Long> chunkIds = batch.stream().map(Chunk::getId).toList();
        // 校验补写 BM25 索引（任务 7.0.18）：发布方尽力更新可能失败（只记 WARN），
        // 这里保证"最终一致"——即使发布方漏更新，切片也能被关键词检索到。
        backfillBm25(event, batch);
        // 标记 EMBEDDING：让文档聚合推导能反映"向量化中"
        chunkMapper.updateStatusByIds(chunkIds, DocumentStatus.EMBEDDING.name());

        List<float[]> vectors;
        try {
            vectors = awaitVectors(batch);
        } catch (Exception e) {
            log.error("批量向量化失败（整批回退为 INDEXED）: {}, chunkIds={}..{}, error={}",
                    event.describeTarget(), chunkIds.get(0), chunkIds.get(chunkIds.size() - 1), e.getMessage());
            chunkMapper.updateStatusByIds(chunkIds, Chunk.STATUS_INDEXED);
            deadLetterQueue.enqueue(DlqMessage.EVENT_EMBED, event.getDocumentId(),
                    batchPayload(event, chunkIds), e);
            return 0;
        }

        try {
            writeToChroma(event, batch, vectors);
        } catch (Exception e) {
            log.error("ChromaDB 向量写入失败（整批回退为 INDEXED）: {}, error={}",
                    event.describeTarget(), e.getMessage());
            chunkMapper.updateStatusByIds(chunkIds, Chunk.STATUS_INDEXED);
            deadLetterQueue.enqueue(DlqMessage.EVENT_CHROMA_WRITE, event.getDocumentId(),
                    batchPayload(event, chunkIds), e);
            return 0;
        }

        chunkMapper.updateStatusByIds(chunkIds, Chunk.STATUS_READY);
        return batch.size();
    }

    /**
     * 提交整批向量化任务并等待全部完成。
     *
     * @param batch 切片列表
     * @return 与 {@code batch} 一一对应的向量
     * @throws RuntimeException 任一任务失败时（整批失败）
     */
    private List<float[]> awaitVectors(List<Chunk> batch) {
        List<CompletableFuture<float[]>> futures = new ArrayList<>(batch.size());
        for (Chunk chunk : batch) {
            futures.add(batchEmbeddingService.submit(chunk));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException | CancellationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("批量向量化存在失败任务: " + cause.getMessage(), cause);
        }
        List<float[]> vectors = new ArrayList<>(futures.size());
        for (CompletableFuture<float[]> future : futures) {
            vectors.add(future.join());
        }
        return vectors;
    }

    /**
     * 批量写入 ChromaDB（批次 07 · 任务 7.0.14 / 7.0.15）。
     * <p>
     * <b>幂等策略：先 removeAll 再 addAll</b>（已确认的方案，不区分"常规写入"与"重跑"）。
     * 理由：{@code ChromaEmbeddingStore} 没有 {@code upsert}，对已存在的 id 执行 add 会抛
     * {@code DuplicateIDError}——存量重跑、DLQ 重放（批次 01 修复后重试体真的会重放）、
     * 失败重试都会撞上；统一"先删后写"一劳永逸。新数据上 {@code removeAll} 是无操作，语义无害。
     * </p>
     * <p>
     * 删除的 id 来源有两处（并集）：
     * <ol>
     *   <li>{@link ChromaRetriever#findVectorIdsByChunkIds} —— 元数据 {@code chunk_id} 反查，
     *       覆盖历史（随机 UUID）与其它写入路径产生的向量；</li>
     *   <li>{@link ChromaRetriever#vectorIdFor} —— 本路径的确定性 id，
     *       即使元数据查询失败也能覆盖自身重跑（ChromaDB 删除不存在的 id 是无操作）。</li>
     * </ol>
     * ⚠️ 注意入参是 <b>ChromaDB 侧向量 id（UUID）</b>，不是 chunkId。
     * </p>
     *
     * @param event   事件
     * @param batch   切片列表
     * @param vectors 与切片一一对应的向量
     */
    private void writeToChroma(ChunksBatchCreatedEvent event, List<Chunk> batch, List<float[]> vectors) {
        List<String> chunkIds = batch.stream().map(chunk -> String.valueOf(chunk.getId())).toList();
        List<Long> numericChunkIds = batch.stream().map(Chunk::getId).toList();

        Set<String> staleVectorIds = new HashSet<>(chromaRetriever.findVectorIdsByChunkIds(numericChunkIds).values());
        batch.forEach(chunk -> staleVectorIds.add(ChromaRetriever.vectorIdFor(chunk.getId())));
        if (!staleVectorIds.isEmpty()) {
            chromaEmbeddingStore.removeAll(staleVectorIds);
            log.debug("幂等写入：已清理既有向量 {} 条: {}", staleVectorIds.size(), event.describeTarget());
        }

        String title = resolveTitle(event);
        List<String> vectorIds = new ArrayList<>(batch.size());
        List<Embedding> embeddings = new ArrayList<>(batch.size());
        List<TextSegment> segments = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            Chunk chunk = batch.get(i);
            vectorIds.add(ChromaRetriever.vectorIdFor(chunk.getId()));
            embeddings.add(new Embedding(vectors.get(i)));
            segments.add(TextSegment.from(chunk.getContent(), buildMetadata(chunk, title)));
        }
        chromaEmbeddingStore.addAll(vectorIds, embeddings, segments);

        for (int i = 0; i < batch.size(); i++) {
            Chunk chunk = batch.get(i);
            chunk.setChromaId(vectorIds.get(i));
            chunkMapper.updateById(chunk);
        }
        log.debug("批量写入 ChromaDB 完成: {}, count={}, chunkIds={}", event.describeTarget(),
                batch.size(), chunkIds);
    }

    /**
     * 构造 ChromaDB 元数据。
     * <p>与原实现一致：{@code chunk_id} + 归属标识（{@code document_id} 或
     * {@code datasource_id}）+ {@code document_title}。</p>
     *
     * @param chunk 切片
     * @param title 文档标题（已解析）
     * @return 元数据
     */
    private Metadata buildMetadata(Chunk chunk, String title) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("chunk_id", chunk.getId().toString());
        if (chunk.getDocumentId() != null) {
            metadata.put("document_id", chunk.getDocumentId().toString());
        }
        if (chunk.getDatasourceId() != null) {
            metadata.put("datasource_id", chunk.getDatasourceId().toString());
        }
        metadata.put("document_title", title);
        return new Metadata(metadata);
    }

    /**
     * 解析向量元数据中的 {@code document_title}。
     * <p>
     * <b>任务 7.0.9 迁移项 5（元数据修正）</b>：文档上传链路原实现取
     * {@code doc.getFileName()}（原始文件名，含扩展名，可能与标题不一致），
     * 现改为 {@code doc.getTitle()}；标题为空时回退文件名，再回退 {@code doc-<id>}，
     * 保证字段永不为 null（ChromaDB 不接受 null 元数据值）。
     * </p>
     * <p>数据同步链路无关联文档，取数据源名称（与原实现一致）。</p>
     *
     * @param event 事件
     * @return 文档标题
     */
    private String resolveTitle(ChunksBatchCreatedEvent event) {
        if (event.getDocumentId() != null) {
            Document document = documentMapper.selectById(event.getDocumentId());
            if (document != null) {
                if (document.getTitle() != null && !document.getTitle().isBlank()) {
                    return document.getTitle();
                }
                if (document.getFileName() != null && !document.getFileName().isBlank()) {
                    return document.getFileName();
                }
            }
            return "doc-" + event.getDocumentId();
        }

        DataSourceConfig config = event.getDatasourceId() == null
                ? null : dataSourceMapper.selectById(event.getDatasourceId());
        if (config != null && config.getSourceName() != null && !config.getSourceName().isBlank()) {
            return config.getSourceName();
        }
        return "数据源";
    }

    /**
     * 构造批次级 DLQ payload。
     * <p>
     * 契约（批次 07 起）：<b>批次标识优先</b>——{@code documentId} 或
     * {@code datasourceId + batchId}；同时保留 {@code chunkId}（本批首个切片），
     * 以便批次 01 建立的单条重试体（{@code DlqRetryScheduler#retryEmbed} /
     * {@code #retryChromaWrite}）在整批重放扩展之前仍可动作。
     * </p>
     *
     * @param event    事件
     * @param chunkIds 本批切片 ID（非空）
     * @return JSON 字符串
     */
    private static String batchPayload(ChunksBatchCreatedEvent event, List<Long> chunkIds) {
        StringBuilder sb = new StringBuilder("{\"chunkId\":").append(chunkIds.get(0));
        if (event.getDocumentId() != null) {
            sb.append(",\"documentId\":").append(event.getDocumentId());
        }
        if (event.getDatasourceId() != null) {
            sb.append(",\"datasourceId\":").append(event.getDatasourceId());
        }
        if (event.getSyncBatchId() != null) {
            sb.append(",\"batchId\":\"").append(event.getSyncBatchId()).append('"');
        }
        sb.append(",\"chunkCount\":").append(chunkIds.size());
        return sb.append('}').toString();
    }

    // ==================== BM25 双保险（7.0e） ====================

    /**
     * 发布方尽力更新 BM25 索引（任务 7.0.17）。
     * <p>
     * 切片写入 MySQL 后<b>立即</b>更新 BM25 索引——此时 {@code INDEXED} 语义成立，
     * 关键词检索即可命中。更新失败只记 WARN，<b>不阻断主流程</b>：
     * 发布方失败由消费方 {@link #backfillBm25} 兜底。
     * </p>
     *
     * @param chunks    已入库切片（主键非空）
     * @param sourceTag 日志标识（如 {@code document:9} / {@code datasource:2}）
     */
    void addToBm25BestEffort(List<? extends Chunk> chunks, String sourceTag) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            int indexed = bm25Retriever.addBatchToIndex(chunks);
            log.info("BM25 索引已更新（发布方）: {}, 切片数={}", sourceTag, indexed);
        } catch (Exception e) {
            log.warn("BM25 索引更新失败（发布方尽力而为，等待 Listener 补写）: {}, error={}",
                    sourceTag, e.getMessage());
        }
    }

    /**
     * 校验并补写 BM25 索引（任务 7.0.18）。
     * <p>
     * 消费方收到事件后校验这批切片是否已在索引中，缺失的补写——
     * 保证即使发布方更新失败，索引也最终一致。补写同样走幂等的
     * {@code addBatchToIndex}（按 chunkId {@code updateDocument}），重复执行安全。
     * </p>
     *
     * @param event 事件（日志用）
     * @param batch 本批切片
     */
    private void backfillBm25(ChunksBatchCreatedEvent event, List<Chunk> batch) {
        try {
            List<Long> missingIds = bm25Retriever.findMissingChunkIds(batch.stream().map(Chunk::getId).toList());
            if (missingIds.isEmpty()) {
                return;
            }
            Set<Long> missing = new HashSet<>(missingIds);
            List<Chunk> toIndex = batch.stream().filter(chunk -> missing.contains(chunk.getId())).toList();
            int indexed = bm25Retriever.addBatchToIndex(toIndex);
            log.warn("BM25 索引校验发现缺失并补写: {}, 缺失={}, 已补写={}",
                    event.describeTarget(), missingIds.size(), indexed);
        } catch (Exception e) {
            log.warn("BM25 索引校验补写失败（不阻断向量化）: {}, error={}",
                    event.describeTarget(), e.getMessage());
        }
    }

    /**
     * 按切片聚合推导并回写文档状态（任务 7.0.4）。
     * <p>
     * 消费方<b>不能</b>调用 {@link DocumentQueryService}：其方法是
     * {@code @Transactional(readOnly = true)}，CQRS 下会路由到读库，
     * 刚写入的切片状态可能尚未同步（主从延迟）→ 推导出过期状态。
     * 因此这里直接用写库的 Mapper 查询，并复用同一套推导规则（单一口径）。
     * </p>
     *
     * @param documentId 文档 ID（数据同步链路为 null，直接跳过）
     */
    private void refreshDocumentStatus(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            Map<String, Integer> counts = new HashMap<>();
            for (Map<String, Object> row : chunkMapper.countByDocumentIdGroupByStatus(documentId)) {
                Object status = row.get("status");
                Object cnt = row.get("cnt");
                if (status == null || cnt == null) {
                    continue;
                }
                counts.put(status.toString(),
                        cnt instanceof Number number ? number.intValue() : Integer.parseInt(cnt.toString()));
            }
            if (counts.isEmpty()) {
                return;
            }
            DocumentStatus derived = DocumentQueryService.deriveStatus(counts);
            documentMapper.updateStatus(documentId, derived.name());
        } catch (Exception e) {
            log.warn("文档状态聚合回写失败（不影响向量化结果）: documentId={}, error={}", documentId, e.getMessage());
        }
    }
}
