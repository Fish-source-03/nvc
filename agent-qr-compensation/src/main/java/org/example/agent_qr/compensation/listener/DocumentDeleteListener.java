package org.example.agent_qr.compensation.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DocumentDeleteRequestedEvent;
import org.example.agent_qr.compensation.service.DocumentDeleteServiceV2;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 文档删除事件监听器 — 事件驱动核心。
 * <p>
 * 监听 {@link DocumentDeleteRequestedEvent}（knowledge 模块发布），
 * 通过 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 确保
 * 在 knowledge 事务提交后才执行补偿操作。
 * </p>
 * <p>
 * ★ 单向依赖 knowledge，knowledge 不依赖此模块。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentDeleteListener {

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final DocumentDeleteServiceV2 documentDeleteServiceV2;
    private final DeadLetterQueue deadLetterQueue;

    /**
     * 处理文档删除请求事件。
     * <p>
     * 流程：
     * <ol>
     *   <li>MySQL 逻辑删除：chunkMapper.softDeleteByDocumentId + documentMapper.softDelete</li>
     *   <li>ChromaDB 物理删除：documentDeleteServiceV2.asyncPhysicalDelete</li>
     *   <li>异常：deadLetterQueue.enqueue("DELETE", ...)</li>
     * </ol>
     * </p>
     *
     * @param event 文档删除请求事件
     */
    @Async("deleteExecutor")
    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void handleDocumentDeleteRequested(DocumentDeleteRequestedEvent event) {
        Long documentId = event.getDocumentId();
        log.info("收到文档删除请求事件: documentId={}, chunkIds={}, chromaIds={}",
                documentId, event.getChunkIds() != null ? event.getChunkIds().size() : 0,
                event.getChromaIds() != null ? event.getChromaIds().size() : 0);

        try {
            // 1. MySQL 逻辑删除
            chunkMapper.softDeleteByDocumentId(documentId);
            documentMapper.softDelete(documentId);
            log.info("MySQL 逻辑删除完成: documentId={}", documentId);

            // 2. ChromaDB 物理删除
            documentDeleteServiceV2.asyncPhysicalDelete(documentId, event.getChromaIds());

        } catch (Exception e) {
            log.error("文档删除补偿处理失败: documentId={}, error={}", documentId, e.getMessage(), e);
            String payload = String.format("{\"documentId\":%d,\"chunkIds\":%s,\"chromaIds\":%s}",
                    documentId,
                    event.getChunkIds() != null ? event.getChunkIds().toString() : "[]",
                    event.getChromaIds() != null ? event.getChromaIds().toString() : "[]");
            deadLetterQueue.enqueue(DlqMessage.EVENT_DELETE, documentId, payload, e);
        }
    }
}
