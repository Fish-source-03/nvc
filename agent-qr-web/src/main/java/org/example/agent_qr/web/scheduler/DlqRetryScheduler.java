package org.example.agent_qr.web.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.DlqMessageMapper;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DocumentParsedEvent;
import org.example.agent_qr.compensation.entity.DeleteTask;
import org.example.agent_qr.compensation.mapper.DeleteTaskMapper;
import org.example.agent_qr.compensation.service.DocumentDeleteServiceV2;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.listener.ChunkEmbeddingBatchListener;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.parser.DocumentParserService;
import org.example.agent_qr.knowledge.service.DocumentQueryService;
import org.example.agent_qr.knowledge.service.FileStorageService;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DLQ 定时重试调度器。
 * <p>
 * 每 30 秒扫描到期 PENDING 死信消息，按 eventType 分派到对应的业务处理器**重放实际业务动作**。
 * 放在 web 模块是因为需要注入各业务模块的类（避免循环依赖）。
 * </p>
 * <p>
 * 事件类型契约见 {@link DlqMessage} 的 {@code EVENT_*} 常量；未知类型**保留记录不删除**
 * （契约不匹配属数据丢失风险，需人工介入）。
 * </p>
 *
 * <h3>批次 08 · 任务 8.5（R24）：向量化重放与 7.0 状态机对齐</h3>
 * <p>
 * 重放体（{@code retryEmbed} / {@code retryChromaWrite}）改为<b>同步</b>执行
 * "keyset 分页读未就绪切片 → 向量化 → {@code removeAll}+{@code addAll} 幂等写入 →
 * 回写 {@code READY}"，与批次 07 的批处理路径同一套构件与状态语义。
 * </p>
 * <p>
 * <b>失败语义与防环路</b>：任一步失败都<b>直接抛出</b>，由 {@link #retryDeadLetters()} 捕获后
 * 调用 {@code updateRetryResult(msgId, false, e)}——失败落在<b>当前</b>死信消息上，
 * 走既有指数退避（3s→9s→27s→81s）并在超过最大重试次数后转 {@code DEAD}。
 * 重放体<b>不再入队任何新的 DLQ 消息</b>（旧实现在失败回调里
 * {@code enqueue(EVENT_EMBED/EVENT_CHROMA_WRITE)}，与监听器的失败入队叠加会形成
 * "DLQ 重试 → 事件 → Listener → 失败再入队"的环路，且新消息 retryCount 归零、永不终止）。
 * </p>
 *
 * <h3>批次 11 · 任务 11.4（R30）：PARSE / CHUNK 同类环路的最后闭合</h3>
 * <p>
 * {@code retryParse} / {@code retryChunk} 原先发布 {@link DocumentParsedEvent} 后无条件标记成功，
 * 而消费方是 {@code @Async} 的——确定性切片失败时同样形成无界环路（R1/R24 的同构残余）。
 * 现改为调用 {@code ChunkEmbeddingBatchListener#processDocumentParsed} 这一<b>同步入口</b>
 * （与批次 08 的 {@code retryPhysicalDelete} / 同步向量化重放同一套路）：
 * 失败直接上抛 → 落在当前消息上 → 退避 → {@code DEAD}；主链路（新上传文档）仍是
 * {@code @Async} 事件路径 + 失败入队，语义未变。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class DlqRetryScheduler {

    /**
     * 单批向量化的切片数上限（与 {@code ChunkEmbeddingBatchListener#BATCH_SIZE} 保持一致）。
     * <p>重放走同一条 keyset 分页路径，避免大数据源场景一次性把全部切片读进内存。</p>
     */
    static final int REPLAY_PAGE_SIZE = 200;

    @Autowired
    private DeadLetterQueue deadLetterQueue;

    @Autowired
    private DlqMessageMapper dlqMessageMapper;

    /**
     * 切片链路监听器的<b>同步入口</b>（批次 11 · 任务 11.4.1，R30）。
     * <p>PARSE / CHUNK 重放不再发布 {@code DocumentParsedEvent}（{@code @Async} 消费方失败会自行入队，
     * 形成无界环路），改调 {@code processDocumentParsed}：失败上抛、不自行入队。</p>
     */
    @Autowired(required = false)
    private ChunkEmbeddingBatchListener chunkEmbeddingBatchListener;

    @Autowired(required = false)
    private DocumentParserService parserService;

    @Autowired(required = false)
    private DocumentDeleteServiceV2 documentDeleteServiceV2;

    @Autowired(required = false)
    private DeleteTaskMapper deleteTaskMapper;

    @Autowired(required = false)
    private DocumentMapper documentMapper;

    @Autowired(required = false)
    private ChunkMapper chunkMapper;

    @Autowired(required = false)
    private BatchEmbeddingService batchEmbeddingService;

    @Autowired(required = false)
    private ChromaEmbeddingStore chromaEmbeddingStore;

    /**
     * 向量 id 幂等能力（{@code findVectorIdsByChunkIds} / {@code vectorIdFor}）。
     * <p>批次 08 · 任务 8.5.2：重放写入前必须先 {@code removeAll}（<b>UUID 入参</b>），
     * 才能保证重复重放不产生重复/孤儿向量。</p>
     */
    @Autowired(required = false)
    private ChromaRetriever chromaRetriever;

    /**
     * 物理文件清理服务（批次 08 · 任务 8.4）。
     * <p>DELETE 重试体需要它来重放"物理文件删除失败"的死信（payload 携带 {@code filePath}）。</p>
     */
    @Autowired(required = false)
    private FileStorageService fileStorageService;

    /**
     * 每 30 秒扫描并重试到期的死信消息。
     */
    @Scheduled(fixedDelay = 30000)
    public void retryDeadLetters() {
        List<DlqMessage> pendingMessages = dlqMessageMapper.selectPendingRetries(LocalDateTime.now());

        if (pendingMessages.isEmpty()) {
            return;
        }

        log.info("DLQ 定时重试: 发现 {} 条到期 PENDING 消息", pendingMessages.size());

        for (DlqMessage msg : pendingMessages) {
            try {
                switch (msg.getEventType()) {
                    case DlqMessage.EVENT_PARSE -> retryParse(msg);
                    case DlqMessage.EVENT_CHUNK -> retryChunk(msg);
                    case DlqMessage.EVENT_EMBED -> retryEmbed(msg);
                    case DlqMessage.EVENT_DELETE -> retryDelete(msg);
                    case DlqMessage.EVENT_ETL -> retryEtl(msg);
                    case DlqMessage.EVENT_CHROMA_WRITE -> retryChromaWrite(msg);
                    default -> handleUnknownEventType(msg);
                }
            } catch (Exception e) {
                log.error("DLQ 重试失败: msgId={}, eventType={}, error={}",
                        msg.getId(), msg.getEventType(), e.getMessage());
                deadLetterQueue.updateRetryResult(msg.getId(), false, e);
            }
        }
    }

    // ==================== 各事件类型的重试体 ====================

    /**
     * 重放文档解析：从 payload 取回 filePath/fileType，重新解析并<b>同步</b>推进下游切片链路。
     * <p>
     * <b>批次 11 · 任务 11.4.1（R30）</b>：原实现
     * {@code publishEvent(DocumentParsedEvent)}（消费方 {@code @Async}）后<b>无条件</b>标记成功——
     * 切片阶段持续失败时，消费方会再入队一条 {@code retryCount=0} 的新死信，
     * 形成"链式重放永不终止"。现改调
     * {@link ChunkEmbeddingBatchListener#processDocumentParsed(DocumentParsedEvent)} 同步入口：
     * 失败直接上抛 → 由 {@link #retryDeadLetters()} 的 per-message catch 记到<b>当前</b>消息上
     * → 既有退避 → {@code DEAD}，<b>不再产生任何新死信</b>。
     * </p>
     * <p>
     * ⚠️ 直接调用（而非发布事件）意味着 DLQ 重放路径不再触发
     * {@code DocumentProgressNotifier#onDocumentParsed} 的"切片中"WebSocket 推送——
     * 这是本方案的已知副作用（重放是修复路径，且后续的 {@code ChunksBatchCreatedEvent} /
     * {@code EmbeddingCompletedEvent} 推送不受影响）。
     * </p>
     */
    private void retryParse(DlqMessage msg) {
        log.info("DLQ 重试解析: msgId={}, documentId={}", msg.getId(), msg.getDocumentId());

        String filePath = extractString(msg.getPayload(), "filePath");
        String fileType = extractString(msg.getPayload(), "fileType");
        if (parserService == null || chunkEmbeddingBatchListener == null
                || filePath == null || fileType == null) {
            throw new IllegalStateException(String.format(
                    "PARSE 重试缺少上下文（parserService可用=%s, listener可用=%s, filePath=%s, fileType=%s），无法重放",
                    parserService != null, chunkEmbeddingBatchListener != null, filePath, fileType));
        }

        String content = parserService.parse(filePath, fileType);
        chunkEmbeddingBatchListener.processDocumentParsed(
                new DocumentParsedEvent(this, msg.getDocumentId(), content));
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放切片：重新解析原文，清理上次失败可能残留的切片后<b>同步</b>执行切片与入库。
     * <p>
     * <b>批次 11 · 任务 11.4.1（R30）</b>：与 {@link #retryParse} 同源改造——
     * 同步入口失败上抛，落在当前死信消息上（退避 3→9→27→81s → 超限 {@code DEAD}），
     * 不再"无条件标记成功 + 异步链路持续产生新死信"。
     * </p>
     */
    private void retryChunk(DlqMessage msg) {
        Long documentId = msg.getDocumentId();
        log.info("DLQ 重试切片: msgId={}, documentId={}", msg.getId(), documentId);

        if (chunkMapper == null || documentMapper == null || parserService == null
                || chunkEmbeddingBatchListener == null) {
            throw new IllegalStateException(
                    "CHUNK 重试缺少依赖组件（ChunkMapper / DocumentMapper / DocumentParserService / ChunkEmbeddingBatchListener）");
        }
        if (documentId == null) {
            throw new IllegalStateException("CHUNK 重试缺少 documentId 上下文");
        }

        Document doc = documentMapper.selectById(documentId);
        if (doc == null || doc.getFilePath() == null || doc.getFileType() == null) {
            throw new IllegalStateException(
                    "CHUNK 重试缺少上下文：文档不存在或缺少 filePath/fileType, documentId=" + documentId);
        }

        String content = parserService.parse(doc.getFilePath(), doc.getFileType());
        // 清理上次失败可能残留的切片，避免重放产生重复切片
        chunkMapper.softDeleteByDocumentId(documentId);
        // 同步执行：失败直接抛出 → 记录到当前消息 → 退避 → DEAD（不产生新死信，R30）
        chunkEmbeddingBatchListener.processDocumentParsed(new DocumentParsedEvent(this, documentId, content));
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放向量化：按批次标识（documentId / datasourceId）重放整批<b>未就绪</b>切片的向量化。
     * <p>
     * <b>批次 08 · 任务 8.5（R24）</b>：重放改为<b>同步</b>走 7.0 批处理路径的构件
     * （keyset 分页查询 + {@code removeAll}+{@code addAll} 幂等写入 + {@code READY} 回写），
     * 三处不一致随之修复：
     * </p>
     * <ol>
     *   <li><b>状态断链</b>：向量真正写入后才回写 {@code status = READY}（原先从不回写，
     *       切片永久停在 {@code INDEXED}）；</li>
     *   <li><b>非幂等写入</b>：写入前先按元数据反查既有向量 id 并 {@code removeAll}，
     *       再以 UUIDv3 确定性 id {@code addAll}（原先用 {@code Utils.randomUUID()} 单条 add，
     *       静默产生重复/孤儿向量并覆盖 {@code chroma_id}）；</li>
     *   <li><b>重灌整文档</b>：只取 {@code status <> 'READY'} 的切片（原先取整文档含已就绪切片）。</li>
     * </ol>
     * <p>
     * payload 契约：优先接受批次标识字段 {@code documentId} / {@code datasourceId}；
     * 缺失时回退到单条 {@code chunkId}。
     * </p>
     */
    private void retryEmbed(DlqMessage msg) {
        log.info("DLQ 重试向量化: msgId={}, documentId={}, payload={}",
                msg.getId(), msg.getDocumentId(), msg.getPayload());

        if (batchEmbeddingService == null || chunkMapper == null || chromaEmbeddingStore == null
                || chromaRetriever == null) {
            throw new IllegalStateException(
                    "EMBED 重试缺少依赖组件（BatchEmbeddingService / ChunkMapper / ChromaEmbeddingStore / ChromaRetriever）");
        }

        replayVectorization(msg);
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放 ChromaDB 写入：与 {@link #retryEmbed} 共用同一重放体。
     * <p>
     * <b>批次 08 · 任务 8.5</b>：原实现以 {@code chroma_id} 是否等于 {@code "pending"} 作为
     * "是否已写入"的判据——但 R24 ① 的现场正是"向量已落库、{@code chroma_id} 已被随机 UUID 覆盖、
     * 状态却停在 {@code INDEXED}"，用 {@code chroma_id} 判据会把这些切片<b>跳过</b>，
     * 使其永远无法流转到 {@code READY}。现改为按<b>切片状态</b>判定：只有
     * {@code status = 'READY'} 的切片才跳过；{@code INDEXED} 的一律幂等重放
     * （{@code removeAll} + 确定性 id 写入，不会产生重复向量）。
     * </p>
     */
    private void retryChromaWrite(DlqMessage msg) {
        log.info("DLQ 重试 ChromaDB 写入: msgId={}, documentId={}, payload={}",
                msg.getId(), msg.getDocumentId(), msg.getPayload());

        if (batchEmbeddingService == null || chunkMapper == null || chromaEmbeddingStore == null
                || chromaRetriever == null) {
            throw new IllegalStateException(
                    "CHROMA_WRITE 重试缺少依赖组件（BatchEmbeddingService / ChunkMapper / ChromaEmbeddingStore / ChromaRetriever）");
        }

        replayVectorization(msg);
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放物理删除：幂等判断后<b>同步</b>执行补偿服务的删除。
     * <p>
     * <b>批次 08 · 8.5（风险 R1 修复）</b>：原实现调用
     * {@code DocumentDeleteServiceV2.asyncPhysicalDelete}（{@code @Async} fire-and-forget），
     * 随后<b>无条件</b>把当前消息标记成功——而 {@code asyncPhysicalDelete} 失败时会
     * <b>自行入队</b>一条新死信（{@code retryCount} 归零）。于是形成无界环路：
     * 当前消息被删除（假成功）+ 新消息持续产生，退避与 {@code DEAD} 分支永远走不到；
     * 对"ChromaDB 持续删失败"与"部署中根本没有 ChromaRetriever"两种场景都是死循环。
     * 现改为调用同步入口 {@link DocumentDeleteServiceV2#retryPhysicalDelete}：
     * 失败直接抛出 → 落在<b>当前</b>消息上（退避 3→9→27→81s → 超限 DEAD），
     * <b>不再产生任何新死信</b>。
     * </p>
     * <p>
     * <b>批次 08 · 任务 8.4</b>：payload 若带 {@code filePath}（物理文件删除失败的死信），
     * 先重放文件清理——否则这类死信会被"只重试向量"的重试体当作成功丢弃，文件永久残留。
     * </p>
     */
    private void retryDelete(DlqMessage msg) {
        Long documentId = msg.getDocumentId();
        log.info("DLQ 重试删除: msgId={}, documentId={}", msg.getId(), documentId);

        if (documentDeleteServiceV2 == null) {
            throw new IllegalStateException("DELETE 重试缺少 DocumentDeleteServiceV2");
        }

        List<String> chromaIds = extractChromaIds(msg.getPayload());
        String filePath = extractString(msg.getPayload(), "filePath");

        // 物理文件清理重放（幂等：文件已不存在视为成功）
        if (filePath != null && !filePath.isBlank()) {
            if (fileStorageService == null) {
                throw new IllegalStateException("DELETE 重试缺少 FileStorageService，无法重放物理文件清理");
            }
            if (!fileStorageService.delete(filePath)) {
                throw new IllegalStateException("物理文件删除失败，待下轮重试: " + filePath);
            }
            log.info("DELETE 重试物理文件清理成功: documentId={}, filePath={}", documentId, filePath);
        }

        if (chromaIds.isEmpty()) {
            if (filePath != null && !filePath.isBlank()) {
                // 本次是"仅文件清理"的死信，已在上方完成
                deadLetterQueue.updateRetryResult(msg.getId(), true, null);
                return;
            }
            // 无法确定待删向量 → 不可静默当成功（原实现的缺陷正是"假成功"）
            throw new IllegalStateException("DELETE 重试缺少 chromaIds 上下文，无法重放: " + msg.getPayload());
        }

        // 幂等判断：该文档最近一次删除任务若已 DONE，说明向量已删除，无需重复操作
        if (deleteTaskMapper != null && documentId != null) {
            List<DeleteTask> tasks = deleteTaskMapper.selectByDocumentId(documentId);
            if (!tasks.isEmpty() && DeleteTask.STATUS_DONE.equals(tasks.get(0).getStatus())) {
                log.info("DELETE 重试跳过：该文档已有 DONE 的删除任务, documentId={}", documentId);
                deadLetterQueue.updateRetryResult(msg.getId(), true, null);
                return;
            }
        }

        // 同步执行：失败直接抛出 → 由 retryDeadLetters 的 per-message catch 记录到当前消息
        // （updateRetryResult(msg, false, e) → 退避 → DEAD），不再产生新死信（R1）
        documentDeleteServiceV2.retryPhysicalDelete(documentId, chromaIds);
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放 ETL。
     * <p>
     * ⚠️ 当前无法实现：ETL 的输入是质量检查通过后的 {@code passedData}（原始记录列表），
     * 而 payload 只携带 {@code datasourceId/batchId/recordCount}，缺少可重放的原始数据。
     * 因此按契约显式标记失败（抛异常 → 外层 catch 记为失败），而非静默当成功；
     * 消息将随退避重试直至 DEAD，留待人工介入。
     * </p>
     */
    private void retryEtl(DlqMessage msg) {
        // TODO(批次 07 / 批次 10)：ETL 重试需要原始 passedData（List<Map<String,Object>>）。
        //  需要数据源同步链路提供"可重放的批次快照"（或让 SyncRecord 持久化原始数据）后，
        //  本方法才能从 payload 的 batchId 重建输入并重新发布 DataQualityPassedEvent。
        //  在那之前，本分支显式失败，避免把无法重放的消息当作成功删除。
        log.error("DLQ 重试 ETL 缺少原始数据上下文，标记为失败待人工介入: msgId={}, payload={}",
                msg.getId(), msg.getPayload());
        throw new UnsupportedOperationException(
                "ETL 重试缺少 passedData 上下文（TODO：需批次数据快照，见批次 07）");
    }

    /**
     * 未知事件类型：保留记录不删除。
     * <p>
     * 原实现将该分支标记为"成功"并物理删除记录——未知类型属契约不匹配，
     * 静默删除等于丢数据。现在保留 PENDING 并每轮告警，直到人工修复契约。
     * </p>
     */
    private void handleUnknownEventType(DlqMessage msg) {
        log.error("DLQ 未知事件类型，保留记录不删除（契约不匹配，需人工处理）: eventType={}, msgId={}, documentId={}",
                msg.getEventType(), msg.getId(), msg.getDocumentId());
    }

    // ==================== 内部辅助 ====================

    /**
     * 同步重放向量化（批次 08 · 任务 8.5，R24 的对齐基线）。
     *
     * <h3>为什么是"同步执行 + 失败上抛"，而不是发布 {@code ChunksBatchCreatedEvent}</h3>
     * <p>
     * 方案 8.5.1 的首选是复用 {@code ChunkEmbeddingBatchListener} 的事件入口，但该入口
     * 是 {@code @Async} 的：重试体无法获知执行结果，只能"提交即当成功"；而 Listener 在
     * 批次失败时会<b>再次入队一条新的 DLQ 消息</b>（retryCount 从 0 重新开始）——
     * 于是形成 <b>DLQ 重试 → 事件 → Listener → 失败再入队</b> 的环路：
     * 永久性故障（如 Ollama 不可用）下每小时会新增上千条死信，且永远走不到 DEAD。
     * 本方法改为<b>同步</b>完成"分页 → 向量化 → 幂等写入 → 状态回写"，
     * 失败直接向 {@link #retryDeadLetters()} 抛出 → {@code updateRetryResult(msg, false, e)}
     * → 既有指数退避 → 超过最大重试次数转 DEAD。<b>重试体自身不再入队任何新消息</b>，
     * 环路由此从结构上不存在；DLQ 的退避重试机制原样保留。
     * </p>
     * <p>
     * 复用的仍是 7.0 批处理路径的构件：{@code ChunkMapper#selectPendingByDocumentIdAfterId} /
     * {@code #selectPendingByDatasourceIdAfterId}（keyset 分页 + 未就绪过滤）、
     * {@link ChromaRetriever#findVectorIdsByChunkIds} + {@link ChromaRetriever#vectorIdFor}
     * （先删后写的 UUID 入参）、{@code ChunkMapper#updateStatusByIds}（{@code READY} 回写）。
     * </p>
     *
     * @param msg 死信消息
     * @throws IllegalStateException/ RuntimeException 重放失败时（由调度器记录为失败并退避重试）
     */
    private void replayVectorization(DlqMessage msg) {
        String payload = msg.getPayload();
        Long documentId = extractLong(payload, "documentId");
        if (documentId == null) {
            documentId = msg.getDocumentId();
        }
        Long datasourceId = extractLong(payload, "datasourceId");
        Long chunkId = extractLong(payload, "chunkId");

        // 单条回退路径（legacy payload 只带 chunkId）
        if (documentId == null && datasourceId == null) {
            if (chunkId == null) {
                throw new IllegalStateException(
                        "向量化重试缺少 documentId/datasourceId/chunkId 上下文，无法重放: " + payload);
            }
            replaySingleChunk(chunkId);
            return;
        }

        int ready = 0;
        long afterId = 0;
        while (true) {
            List<Chunk> batch = readPendingBatch(documentId, datasourceId, afterId);
            if (batch.isEmpty()) {
                break;
            }
            afterId = batch.get(batch.size() - 1).getId();
            ready += embedAndWrite(batch);
            if (batch.size() < REPLAY_PAGE_SIZE) {
                break;
            }
        }

        if (ready == 0) {
            if (!targetExists(documentId, datasourceId)) {
                throw new IllegalStateException("向量化重试找不到目标切片: " + payload);
            }
            // 幂等闸门：切片全部已就绪 → 目标状态已达成，重复重放不应报失败
            log.info("DLQ 重放向量化：无未就绪切片（已全部 READY），幂等确认: {}", describeTarget(documentId, datasourceId));
            return;
        }
        log.info("DLQ 重放向量化完成: {}, 已就绪切片={}", describeTarget(documentId, datasourceId), ready);
    }

    /**
     * 单条切片的重放（legacy 路径）。
     *
     * @param chunkId 切片 ID
     */
    private void replaySingleChunk(Long chunkId) {
        Chunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new IllegalStateException("向量化重试找不到切片: chunkId=" + chunkId);
        }
        if (Chunk.STATUS_READY.equals(chunk.getStatus())) {
            log.info("DLQ 重放向量化：切片已就绪，幂等确认: chunkId={}", chunkId);
            return;
        }
        embedAndWrite(List.of(chunk));
        log.info("DLQ 重放向量化完成: chunkId={}, 已就绪切片=1", chunkId);
    }

    /**
     * 按批次标识读取下一批<b>未就绪</b>切片（keyset 分页，与 7.0 批处理路径同源）。
     *
     * @param documentId   文档 ID（优先）
     * @param datasourceId 数据源 ID
     * @param afterId      上一批的最大切片 ID（首批传 0）
     * @return 待向量化切片（按 id 升序）
     */
    private List<Chunk> readPendingBatch(Long documentId, Long datasourceId, long afterId) {
        List<Chunk> batch = documentId != null
                ? chunkMapper.selectPendingByDocumentIdAfterId(documentId, afterId, REPLAY_PAGE_SIZE)
                : chunkMapper.selectPendingByDatasourceIdAfterId(datasourceId, afterId, REPLAY_PAGE_SIZE);
        return batch == null ? List.of() : batch;
    }

    /**
     * 判断重放目标是否真实存在（用于区分"已全部就绪"与"目标不存在"）。
     * <p>查询走 MyBatis-Plus 自动 SQL，{@code @TableLogic} 会自动排除已软删切片。</p>
     *
     * @param documentId   文档 ID
     * @param datasourceId 数据源 ID
     * @return true 表示该文档/数据源下仍有未删除切片
     */
    private boolean targetExists(Long documentId, Long datasourceId) {
        Long count = documentId != null
                ? chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>().eq(Chunk::getDocumentId, documentId))
                : chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>().eq(Chunk::getDatasourceId, datasourceId));
        return count != null && count > 0;
    }

    /**
     * 同步完成一批切片的"向量化 → 幂等写入 → 状态回写"。
     * <p>任何一步失败都抛出（不吞异常、不再入队新消息），由调度器记为失败并退避重试。</p>
     *
     * @param batch 本批切片（非空）
     * @return 本批成功写入并置为 {@code READY} 的切片数
     */
    private int embedAndWrite(List<Chunk> batch) {
        List<Long> chunkIds = batch.stream().map(Chunk::getId).toList();

        // 1. 同步等待整批向量化（整批失败语义，与 7.0 批处理路径一致）
        List<float[]> vectors = awaitVectors(batch);

        // 2. 幂等写入 ChromaDB：先 removeAll（UUID 入参）再 addAll（UUIDv3 确定性 id）
        writeToChromaIdempotently(batch, vectors);

        // 3. 状态回写：向量确实落库之后才置 READY（R24 ①：原先从不回写，永久停在 INDEXED）
        chunkMapper.updateStatusByIds(chunkIds, Chunk.STATUS_READY);

        // 4. 文档状态聚合回写（读库口径为查询时实时聚合，这里同步库内"最近一次已知状态"）
        refreshDocumentStatus(batch);
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
            throw new RuntimeException("DLQ 重放批量向量化失败: " + cause.getMessage(), cause);
        }
        List<float[]> vectors = new ArrayList<>(futures.size());
        for (CompletableFuture<float[]> future : futures) {
            vectors.add(future.join());
        }
        return vectors;
    }

    /**
     * 幂等写入 ChromaDB（批次 08 · 任务 8.5.2 第 1 项）。
     * <p>
     * 与 {@code ChunkEmbeddingBatchListener#writeToChroma} 同一策略：
     * <ol>
     *   <li>删除 id = 元数据 {@code chunk_id} 反查到的既有向量（覆盖历史随机 UUID）∪
     *       {@link ChromaRetriever#vectorIdFor} 确定性 id（覆盖自身重跑），
     *       <b>入参是 ChromaDB 向量 id，不是 chunkId</b>；</li>
     *   <li>以确定性 id {@code addAll} 整批。</li>
     * </ol>
     * 因此<b>重复重放不会新增随机 UUID 向量</b>：同一批切片每轮得到的 id 集合完全相同，
     * {@code removeAll} 会先把上一轮的向量清掉（R24 ②）。
     * </p>
     *
     * @param batch   切片列表
     * @param vectors 与切片一一对应的向量
     */
    private void writeToChromaIdempotently(List<Chunk> batch, List<float[]> vectors) {
        List<Long> numericChunkIds = batch.stream().map(Chunk::getId).toList();

        Set<String> staleVectorIds = new HashSet<>(chromaRetriever.findVectorIdsByChunkIds(numericChunkIds).values());
        batch.forEach(chunk -> staleVectorIds.add(ChromaRetriever.vectorIdFor(chunk.getId())));
        if (!staleVectorIds.isEmpty()) {
            chromaEmbeddingStore.removeAll(staleVectorIds);
            log.debug("DLQ 重放幂等写入：已清理既有向量 {} 条", staleVectorIds.size());
        }

        List<String> vectorIds = new ArrayList<>(batch.size());
        List<Embedding> embeddings = new ArrayList<>(batch.size());
        List<TextSegment> segments = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            Chunk chunk = batch.get(i);
            vectorIds.add(ChromaRetriever.vectorIdFor(chunk.getId()));
            embeddings.add(new Embedding(vectors.get(i)));
            segments.add(TextSegment.from(chunk.getContent(), buildMetadata(chunk)));
        }
        chromaEmbeddingStore.addAll(vectorIds, embeddings, segments);

        // 回写 chroma_id（确定性 id），修复"随机 UUID 覆盖 chroma_id"造成的 id 方案失效
        for (int i = 0; i < batch.size(); i++) {
            Chunk chunk = batch.get(i);
            chunk.setChromaId(vectorIds.get(i));
            chunkMapper.updateById(chunk);
        }
    }

    /**
     * 构造向量元数据（与 7.0 写入路径同构：{@code chunk_id} + 归属标识 + {@code document_title}）。
     * <p>ChromaDB 不接受 null 元数据值，因此标题缺失时回退为占位名。</p>
     *
     * @param chunk 切片
     * @return 元数据
     */
    private Metadata buildMetadata(Chunk chunk) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("chunk_id", chunk.getId().toString());
        if (chunk.getDocumentId() != null) {
            metadata.put("document_id", chunk.getDocumentId().toString());
        }
        if (chunk.getDatasourceId() != null) {
            metadata.put("datasource_id", chunk.getDatasourceId().toString());
        }
        metadata.put("document_title", resolveTitle(chunk));
        return new Metadata(metadata);
    }

    /**
     * 解析向量元数据里的 {@code document_title}。
     *
     * @param chunk 切片
     * @return 文档标题（缺失时回退文件名 / {@code doc-<id>} / "数据源"）
     */
    private String resolveTitle(Chunk chunk) {
        if (chunk.getDocumentId() == null) {
            return "数据源";
        }
        if (documentMapper != null) {
            try {
                Document document = documentMapper.selectById(chunk.getDocumentId());
                if (document != null) {
                    if (document.getTitle() != null && !document.getTitle().isBlank()) {
                        return document.getTitle();
                    }
                    if (document.getFileName() != null && !document.getFileName().isBlank()) {
                        return document.getFileName();
                    }
                }
            } catch (Exception e) {
                log.debug("读取文档标题失败，回退占位标题: documentId={}, error={}",
                        chunk.getDocumentId(), e.getMessage());
            }
        }
        return "doc-" + chunk.getDocumentId();
    }

    /**
     * 按切片聚合推导并回写文档状态（与 7.0 批处理路径同一推导口径）。
     * <p>失败只记 WARN，不影响向量化结果本身。</p>
     *
     * @param batch 本批切片（取其中的 documentId）
     */
    private void refreshDocumentStatus(List<Chunk> batch) {
        if (documentMapper == null) {
            return;
        }
        Long documentId = batch.stream()
                .map(Chunk::getDocumentId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
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
            log.warn("文档状态聚合回写失败（不影响向量化结果）: documentId={}, error={}",
                    documentId, e.getMessage());
        }
    }

    /**
     * 重放目标描述（日志用）。
     */
    private static String describeTarget(Long documentId, Long datasourceId) {
        return documentId != null ? "documentId=" + documentId : "datasourceId=" + datasourceId;
    }

    /**
     * 从 payload 中提取字符串字段（宽松解析）。
     * <p>
     * payload 由各入队方以 {@code String.format} 拼接，可能含未转义的反斜杠
     * （如 Windows 绝对路径），因此不依赖严格 JSON 解析器。
     * </p>
     */
    private static String extractString(String payload, String key) {
        if (payload == null || key == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(payload);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 从 payload 中提取数值字段（宽松解析）。
     */
    private static Long extractLong(String payload, String key) {
        if (payload == null || key == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(payload);
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 payload 中提取 ChromaDB 向量 ID 列表，兼容两种既有格式：
     * <ul>
     *   <li>逗号分隔串 {@code "chromaIds":"a,b,c"}（DocumentDeleteServiceV2 入队）</li>
     *   <li>JSON 数组 {@code "chromaIds":["a","b"]}（DocumentDeleteListener 入队）</li>
     * </ul>
     */
    private static List<String> extractChromaIds(String payload) {
        List<String> ids = new ArrayList<>();
        if (payload == null) {
            return ids;
        }

        String joined = extractString(payload, "chromaIds");
        if (joined != null && !joined.isBlank()) {
            for (String id : joined.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty() && !"null".equals(trimmed)) {
                    ids.add(trimmed);
                }
            }
            return ids;
        }

        Matcher m = Pattern.compile("\"chromaIds\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(payload);
        if (m.find()) {
            for (String part : m.group(1).split(",")) {
                String id = part.trim().replaceAll("^\"|\"$", "");
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
