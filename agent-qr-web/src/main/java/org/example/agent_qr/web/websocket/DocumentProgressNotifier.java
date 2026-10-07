package org.example.agent_qr.web.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.event.ChunksBatchCreatedEvent;
import org.example.agent_qr.common.event.DocumentParsedEvent;
import org.example.agent_qr.common.event.DocumentUploadedEvent;
import org.example.agent_qr.common.event.EmbeddingCompletedEvent;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档处理进度推送器（批次 10 · 任务 10.2.3，问题 34 场景①）。
 * <p>
 * 把既有的文档处理事件链转换为 STOMP 推送，<b>逐用户投递</b>：
 * </p>
 * <pre>
 *   DocumentUploadedEvent   → PARSING（开始解析）
 *   DocumentParsedEvent     → CHUNKING（开始切片）
 *   ChunksBatchCreatedEvent → INDEXED（切片已入库，关键词可搜）
 *   EmbeddingCompletedEvent → READY / FAILED / INDEXED（以数据库中的最终状态为准）
 * </pre>
 * <p>
 * <b>目的地 = {@code /user/{uploadUserId}/queue/documents/progress}</b>：
 * 用 {@link SimpMessagingTemplate#convertAndSendToUser} 按会话投递，
 * <b>不使用广播目的地</b>——用户数据进广播会造成越权泄露（问题 34 的禁止事项）。
 * 会话标识为 userId 字符串，与 {@code WebSocketUserPrincipal#getName()} 口径一致。
 * </p>
 * <p>
 * 状态机依据批次 07 的双状态机（UPLOADED → PARSING → CHUNKING → INDEXED → EMBEDDING → READY/FAILED），
 * 推送点以<b>当前</b>事件链为准：{@code ChunksBatchCreatedEvent} 是批次 07 引入的统一触发点，
 * {@code EmbeddingCompletedEvent} 在向量化批次处理结束时发布（含 {@code rag.embedding.write-to-chromadb=false}
 * 关闭向量化后的 INDEXED 终态）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProgressNotifier {

    /** 用户专属目的地（客户端订阅 {@code /user/queue/documents/progress}） */
    public static final String USER_QUEUE = "/queue/documents/progress";

    /** 消息类型标识（前端据此分流） */
    public static final String TYPE_DOCUMENT_PROGRESS = "DOCUMENT_PROGRESS";

    private final SimpMessagingTemplate messagingTemplate;
    private final DocumentMapper documentMapper;

    /**
     * 文档已上传 → 推送"解析中"。
     *
     * @param event 上传事件（携带 userId）
     */
    @Async("statExecutor")
    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        if (event.getUserId() == null) {
            log.debug("文档上传事件缺少 userId，跳过 WebSocket 进度推送: documentId={}", event.getDocumentId());
            return;
        }
        push(event.getUserId(), event.getDocumentId(), DocumentStatus.PARSING.name(),
                event.getFileName(), null, null);
    }

    /**
     * 文档已解析 → 推送"切片中"（按文档归属用户投递）。
     *
     * @param event 解析完成事件
     */
    @Async("statExecutor")
    @EventListener
    public void onDocumentParsed(DocumentParsedEvent event) {
        pushToOwner(event.getDocumentId(), DocumentStatus.CHUNKING.name(), null, null);
    }

    /**
     * 切片批量创建 → 推送"部分就绪（INDEXED，关键词可搜、向量未写）"。
     * <p>数据同步链路（{@code documentId == null}）不推送：它没有归属用户，
     * 其运维可见性由运维告警频道负责。</p>
     *
     * @param event 批量创建事件
     */
    @Async("statExecutor")
    @EventListener
    public void onChunksBatchCreated(ChunksBatchCreatedEvent event) {
        if (event == null || event.getDocumentId() == null) {
            return;
        }
        pushToOwner(event.getDocumentId(), DocumentStatus.INDEXED.name(), null, null);
    }

    /**
     * 向量化完成 → <b>以数据库中的最终状态</b>推送（READY / FAILED / INDEXED）。
     * <p>
     * 不直接用事件本身推断状态：{@code ChunkEmbeddingBatchListener} 在发布该事件前
     * 已用切片状态聚合回写文档状态（批次 07 · 任务 7.0.4），此处读回即可拿到权威值；
     * 读取失败时退化为"按 successCount 推断"，仅影响展示、不影响主流程。
     * </p>
     *
     * @param event 向量化完成事件
     */
    @Async("statExecutor")
    @EventListener
    public void onEmbeddingCompleted(EmbeddingCompletedEvent event) {
        Document document = safeSelectDocument(event.getDocumentId());
        String status = document != null && document.getStatus() != null
                ? document.getStatus().name()
                : DocumentStatus.READY.name();
        pushToOwner(event.getDocumentId(), status, event.getChunkCount(),
                document != null ? document.getErrorMsg() : null);
    }

    /**
     * 按文档归属用户推送（userId 取自 {@code kb_document.upload_user_id}）。
     *
     * @param documentId 文档 ID
     * @param status     文档状态名
     * @param chunkCount 切片数（可空）
     * @param errorMsg   错误信息（可空，失败状态使用）
     */
    private void pushToOwner(Long documentId, String status, Integer chunkCount, String errorMsg) {
        Document document = safeSelectDocument(documentId);
        if (document == null) {
            log.debug("文档不存在，跳过 WebSocket 进度推送: documentId={}", documentId);
            return;
        }
        if (document.getUploadUserId() == null) {
            log.debug("文档无归属用户，跳过 WebSocket 进度推送: documentId={}", documentId);
            return;
        }
        push(document.getUploadUserId(), documentId, status, document.getTitle(), chunkCount, errorMsg);
    }

    /**
     * 构造进度消息并推送到用户专属目的地。
     *
     * @param userId     目标用户 ID
     * @param documentId 文档 ID
     * @param status     状态名（DocumentStatus）
     * @param title      文档标题（可空）
     * @param chunkCount 切片数（可空）
     * @param errorMsg   错误信息（可空）
     */
    private void push(Long userId, Long documentId, String status, String title,
                      Integer chunkCount, String errorMsg) {
        if (userId == null || documentId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE_DOCUMENT_PROGRESS);
        payload.put("documentId", documentId);
        payload.put("status", status);
        payload.put("statusText", statusText(status));
        payload.put("title", title);
        if (chunkCount != null) {
            payload.put("chunkCount", chunkCount);
        }
        if (errorMsg != null) {
            payload.put("errorMsg", errorMsg);
        }
        payload.put("timestamp", LocalDateTime.now().toString());

        // ★ 用户专属目的地：按会话投递，避免串号（禁止用广播目的地推送含用户数据的消息）
        messagingTemplate.convertAndSendToUser(String.valueOf(userId), USER_QUEUE, payload);
        log.debug("文档进度已推送: userId={}, documentId={}, status={}", userId, documentId, status);
    }

    /**
     * 查询文档（异常不向外扩散——推送失败不应影响文档处理主流程）。
     *
     * @param documentId 文档 ID
     * @return 文档；不存在或查询异常返回 null
     */
    private Document safeSelectDocument(Long documentId) {
        if (documentId == null) {
            return null;
        }
        try {
            return documentMapper.selectById(documentId);
        } catch (Exception e) {
            log.warn("查询文档失败，跳过 WebSocket 进度推送: documentId={}, error={}", documentId, e.getMessage());
            return null;
        }
    }

    /**
     * 状态中文描述（与 {@link DocumentStatus} 的口径一致，便于前端直接展示）。
     *
     * @param status 状态名
     * @return 描述；未知状态返回原状态名
     */
    private String statusText(String status) {
        if (status == null) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(status).getDescription();
        } catch (IllegalArgumentException e) {
            return status;
        }
    }
}
