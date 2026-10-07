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
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 是否执行向量化（批次 10 · 任务 10.5.3，问题 38；配置键 {@code rag.embedding.write-to-chromadb}）。
     * <p>
     * <b>语义（已确认的决策）</b>：关闭 = <b>跳过向量化</b>——切片只到
     * {@link Chunk#STATUS_INDEXED}（已入库、BM25 关键词可搜、向量未写），不写 ChromaDB；
     * 与批次 07 的双状态机自洽（INDEXED = "部分就绪"，前端已能区分）。
     * </p>
     * <p>
     * <b>为什么保留这个开关</b>：该键在历史上确实起过作用（实测老切片 7362/7386-7397 无向量），
     * 保留它即保留"Ollama/ChromaDB 故障或想省算力时一键停写"的运维应急能力
     * （复盘报告 §4 经验 1：每个环节都应有灰度开关）。
     * </p>
     * <p>
     * <b>修复前的注释与取值自相矛盾</b>：{@code application-p1.yml} 写"P1: 关闭 ChromaDB 写入"
     * 而值为 {@code true}，且无任何 Java 读取点。现已接线并修正注释。
     * </p>
     * <p>
     * <b>关闭期间产生的数据不会自动补做向量化</b>（已评估的决策，理由与恢复路径见
     * {@code doc/修复-tasks/progress.md} 批次 10 记录）：自动启动补扫会发布
     * {@link EmbeddingCompletedEvent} 从而污染统计口径（docUploadCount），且可能带来
     * 不可预期的启动期全量重算；恢复方式见下方 {@link #handleChunksBatchCreated} 的注释。
     * </p>
     */
    @Value("${rag.embedding.write-to-chromadb:true}")
    private boolean writeToChromaDb = true;

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
     * 处理文档解析完成事件（<b>主链路</b>入口：文档上传 / 解析成功后的事件路径）。
     * <p>
     * 语义（批次 07 起，不得改变）：{@code @Async} 异步执行；失败时回写文档 {@code FAILED}
     * 并入队一次 {@code CHUNK} 死信，交由 {@code DlqRetryScheduler} 退避重放。
     * </p>
     * <p>
     * ⚠️ <b>DLQ 重放不走本方法</b>（批次 11 · 任务 11.4.1，R30）：{@code DlqRetryScheduler#retryChunk}
     * 改调同步入口 {@link #processDocumentParsed(DocumentParsedEvent)}。原因是本方法是
     * {@code @Async} 的：重放体无法获知其成败，只会在失败时让本方法<b>再入队一条
     * {@code retryCount=0} 的新死信</b>——确定性切片失败下形成"链式重放永不终止"的无界环路
     * （与 R1/R24 同构）。
     * </p>
     *
     * @param event 文档解析完成事件
     */
    @Async("chunkExecutor")
    @EventListener
    public void handleDocumentParsed(DocumentParsedEvent event) {
        try {
            processDocumentParsed(event);
        } catch (Exception e) {
            // 主链路失败语义：入队一条 CHUNK 死信（retryCount 从 0 开始，由 DLQ 退避驱动）
            String payload = String.format("{\"documentId\":%d}", event.getDocumentId());
            deadLetterQueue.enqueue(DlqMessage.EVENT_CHUNK, event.getDocumentId(), payload, e);
        }
    }

    /**
     * <b>同步</b>执行"切片 → 入库（INDEXED）→ 发布向量化事件"，失败<b>直接上抛且不自行入队</b>
     * （批次 11 · 任务 11.4.1，R30）。
     * <p>
     * 供 {@code DlqRetryScheduler#retryChunk} 重放调用（照搬批次 08 处理 EMBED 的做法——
     * {@code DocumentDeleteServiceV2#retryPhysicalDelete} 的同步入口）：异常回到调度器后
     * 落在<b>当前</b>死信消息上 → 既有指数退避 → 超限转 {@code DEAD}。
     * <b>重放路径不再产生任何新死信</b>，环路从结构上不存在。
     * </p>
     * <p>
     * 文档状态回写（{@code FAILED} + 错误信息）保留在本方法内：无论主链路还是重放链路，
     * 切片失败都应对用户可见。
     * </p>
     *
     * @param event 文档解析完成事件
     * @throws RuntimeException 切片/入库/发布任一环节失败时（调用方负责记录与退避）
     */
    public void processDocumentParsed(DocumentParsedEvent event) {
        Long documentId = event.getDocumentId();
        log.info("开始处理文档切片与索引: id={}", documentId);

        try {
            // 1. 更新状态为 CHUNKING
            documentMapper.updateStatus(documentId, DocumentStatus.CHUNKING.name());

            // 2. 文本切片
            List<String> chunkTexts = textSplitter.split(event.getContent());
            log.info("文本切片完成: documentId={}, 切片数={}", documentId, chunkTexts.size());

            // 3. 保存切片到数据库 —— 状态 INDEXED（已入库，BM25 可检索，向量未写）
            //    ⚠️ R39（批次 11 收尾清单，二选一后取"明确标注为预留给后续批次"）：
            //    Chunk.contentType / tableCaption 是**预留字段，当前无写入方、无读取方**——
            //    此处刻意不填：闭环需同时改本处与 ChunkMapper#insertBatch 的列清单
            //    （后者的手写 INSERT 不含该列，只 set 不补列 = "看起来接线、实际不落库"）
            //    并决定检索侧是否消费；详见 Chunk 实体注释与设计文档 §8.15.2。
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

        } catch (RuntimeException e) {
            log.error("文档切片处理失败: id={}, error={}", documentId, e.getMessage(), e);
            documentMapper.updateStatus(documentId, DocumentStatus.FAILED.name());
            documentMapper.updateErrorMsg(documentId, "处理失败: " + e.getMessage());
            throw e;
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

        // ★ 批次 10 · 任务 10.5.3：向量化总开关（rag.embedding.write-to-chromadb=false 时跳过）
        //   关闭语义 = 只到 INDEXED：切片已入库、BM25 关键词可搜（发布方已尽力更新索引），
        //   向量不写 ChromaDB、状态不推进到 READY。降级必须留日志（本开关是运维显式动作，非静默降级）。
        //   恢复：关闭期间产生的 INDEXED 切片不会被自动补做（决策与理由见字段注释）；
        //   重新打开后需要补做时，走既有 DLQ 重放路径（插入 eventType=EMBED、payload 含
        //   documentId/datasourceId 的死信消息，DlqRetryScheduler 会按 keyset 分页读取
        //   "status <> READY"的切片重放，与本次跳过的是同一批数据），或重新上传/重新同步该文档。
        if (!writeToChromaDb) {
            log.warn("向量化已关闭（rag.embedding.write-to-chromadb=false）: {} 跳过 ChromaDB 写入，"
                            + "切片停留在 INDEXED（关键词可搜，语义检索暂不可用），关闭期间的数据不会自动补做向量化",
                    event.describeTarget());
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
