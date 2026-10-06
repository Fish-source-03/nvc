package org.example.agent_qr.web.scheduler;

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
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.parser.DocumentParserService;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * @author agent-qr
 */
@Slf4j
@Component
public class DlqRetryScheduler {

    /** 切片 ChromaDB 引用 ID 的"尚未写入"占位值（与 Listener 侧一致）。 */
    private static final String CHROMA_ID_PENDING = "pending";

    @Autowired
    private DeadLetterQueue deadLetterQueue;

    @Autowired
    private DlqMessageMapper dlqMessageMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

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
     * 重放文档解析：从 payload 取回 filePath/fileType，重新解析并发布会话内事件推进下游链路。
     */
    private void retryParse(DlqMessage msg) {
        log.info("DLQ 重试解析: msgId={}, documentId={}", msg.getId(), msg.getDocumentId());

        String filePath = extractString(msg.getPayload(), "filePath");
        String fileType = extractString(msg.getPayload(), "fileType");
        if (parserService == null || filePath == null || fileType == null) {
            throw new IllegalStateException(String.format(
                    "PARSE 重试缺少上下文（parserService可用=%s, filePath=%s, fileType=%s），无法重放",
                    parserService != null, filePath, fileType));
        }

        String content = parserService.parse(filePath, fileType);
        eventPublisher.publishEvent(new DocumentParsedEvent(this, msg.getDocumentId(), content));
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放切片：重新解析原文，清理上次失败可能残留的切片后重新发布解析完成事件。
     */
    private void retryChunk(DlqMessage msg) {
        Long documentId = msg.getDocumentId();
        log.info("DLQ 重试切片: msgId={}, documentId={}", msg.getId(), documentId);

        if (chunkMapper == null || documentMapper == null || parserService == null) {
            throw new IllegalStateException(
                    "CHUNK 重试缺少依赖组件（ChunkMapper / DocumentMapper / DocumentParserService）");
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
        eventPublisher.publishEvent(new DocumentParsedEvent(this, documentId, content));
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放向量化：按批次标识（documentId / datasourceId）重新提交整批切片向量化。
     * <p>
     * payload 契约（批次 07 任务 7.0b 前的过渡形态）：优先接受批次标识字段
     * {@code documentId} / {@code datasourceId}；缺失时回退到单条 {@code chunkId}。
     * </p>
     */
    private void retryEmbed(DlqMessage msg) {
        log.info("DLQ 重试向量化: msgId={}, documentId={}, payload={}",
                msg.getId(), msg.getDocumentId(), msg.getPayload());

        if (batchEmbeddingService == null || chunkMapper == null) {
            throw new IllegalStateException(
                    "EMBED 重试缺少依赖组件（BatchEmbeddingService / ChunkMapper）");
        }

        List<Chunk> chunks = resolveChunks(msg);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("EMBED 重试找不到待向量化切片: " + msg.getPayload());
        }

        for (Chunk chunk : chunks) {
            submitVectorizationAndWrite(chunk);
        }
        // 提交为异步（与 Listener 既有语义一致）：异步阶段失败会重新入队
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放 ChromaDB 写入：切片已写入则幂等确认，否则重新向量化并写入。
     */
    private void retryChromaWrite(DlqMessage msg) {
        Long chunkId = extractLong(msg.getPayload(), "chunkId");
        log.info("DLQ 重试 ChromaDB 写入: msgId={}, chunkId={}", msg.getId(), chunkId);

        if (chunkMapper == null || batchEmbeddingService == null || chromaEmbeddingStore == null) {
            throw new IllegalStateException(
                    "CHROMA_WRITE 重试缺少依赖组件（ChunkMapper / BatchEmbeddingService / ChromaEmbeddingStore）");
        }
        if (chunkId == null) {
            throw new IllegalStateException("CHROMA_WRITE 重试缺少 chunkId 上下文: " + msg.getPayload());
        }

        Chunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new IllegalStateException("CHROMA_WRITE 重试找不到切片: chunkId=" + chunkId);
        }
        if (chunk.getChromaId() != null && !CHROMA_ID_PENDING.equals(chunk.getChromaId())) {
            log.info("CHROMA_WRITE 重试跳过：切片已写入向量 chunkId={}, chromaId={}",
                    chunkId, chunk.getChromaId());
            deadLetterQueue.updateRetryResult(msg.getId(), true, null);
            return;
        }

        submitVectorizationAndWrite(chunk);
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    }

    /**
     * 重放物理删除：幂等判断后调用补偿服务的实际删除方法。
     */
    private void retryDelete(DlqMessage msg) {
        Long documentId = msg.getDocumentId();
        log.info("DLQ 重试删除: msgId={}, documentId={}", msg.getId(), documentId);

        if (documentDeleteServiceV2 == null) {
            throw new IllegalStateException("DELETE 重试缺少 DocumentDeleteServiceV2");
        }

        List<String> chromaIds = extractChromaIds(msg.getPayload());
        if (chromaIds.isEmpty()) {
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

        documentDeleteServiceV2.asyncPhysicalDelete(documentId, chromaIds);
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
     * 解析待重放的切片集合（批次语义优先，单条回退）。
     */
    private List<Chunk> resolveChunks(DlqMessage msg) {
        String payload = msg.getPayload();
        Long documentId = extractLong(payload, "documentId");
        if (documentId == null) {
            documentId = msg.getDocumentId();
        }
        Long datasourceId = extractLong(payload, "datasourceId");
        Long chunkId = extractLong(payload, "chunkId");

        if (documentId != null) {
            List<Chunk> chunks = chunkMapper.selectByDocumentId(documentId);
            if (!chunks.isEmpty()) {
                return chunks;
            }
        }
        if (datasourceId != null) {
            List<Chunk> chunks = chunkMapper.selectByDatasourceId(datasourceId);
            if (!chunks.isEmpty()) {
                return chunks;
            }
        }
        if (chunkId != null) {
            Chunk chunk = chunkMapper.selectById(chunkId);
            if (chunk != null) {
                return List.of(chunk);
            }
        }
        return List.of();
    }

    /**
     * 重新提交单个切片的向量化，并在完成后写入 ChromaDB。
     * <p>
     * 与 Listener 既有模式一致：提交为异步，异步阶段失败重新入队 DLQ。
     * ⚠️ 幂等由批次 07 任务 7.0d 负责（写入前 removeAll）；在此之前重放可能产生重复向量。
     * </p>
     */
    private void submitVectorizationAndWrite(Chunk chunk) {
        if (chromaEmbeddingStore == null) {
            throw new IllegalStateException("ChromaEmbeddingStore 不可用，无法写入向量");
        }

        batchEmbeddingService.submit(chunk)
                .thenAccept(vector -> {
                    try {
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("chunk_id", chunk.getId().toString());
                        if (chunk.getDocumentId() != null) {
                            metadata.put("document_id", chunk.getDocumentId().toString());
                        }
                        if (chunk.getDatasourceId() != null) {
                            metadata.put("datasource_id", chunk.getDatasourceId().toString());
                        }
                        TextSegment segment = TextSegment.from(chunk.getContent(), new Metadata(metadata));
                        String chromaId = chromaEmbeddingStore.add(new Embedding(vector), segment);
                        chunk.setChromaId(chromaId);
                        chunkMapper.updateById(chunk);
                        log.info("DLQ 重试向量化成功: chunkId={}, chromaId={}", chunk.getId(), chromaId);
                    } catch (Exception ex) {
                        log.error("DLQ 重试写入 ChromaDB 失败: chunkId={}, error={}", chunk.getId(), ex.getMessage());
                        deadLetterQueue.enqueue(DlqMessage.EVENT_CHROMA_WRITE, chunk.getDocumentId(),
                                chunkPayload(chunk), ex);
                    }
                })
                .exceptionally(ex -> {
                    log.error("DLQ 重试向量化失败: chunkId={}, error={}", chunk.getId(), ex.getMessage());
                    deadLetterQueue.enqueue(DlqMessage.EVENT_EMBED, chunk.getDocumentId(),
                            chunkPayload(chunk), ex);
                    return null;
                });
    }

    /**
     * 构造切片级重试 payload（字段缺失时省略，避免 null 字面量污染）。
     */
    private static String chunkPayload(Chunk chunk) {
        StringBuilder sb = new StringBuilder("{\"chunkId\":").append(chunk.getId());
        if (chunk.getDocumentId() != null) {
            sb.append(",\"documentId\":").append(chunk.getDocumentId());
        }
        if (chunk.getDatasourceId() != null) {
            sb.append(",\"datasourceId\":").append(chunk.getDatasourceId());
        }
        return sb.append('}').toString();
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
