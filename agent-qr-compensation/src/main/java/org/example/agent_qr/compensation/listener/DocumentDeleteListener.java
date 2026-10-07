package org.example.agent_qr.compensation.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DocumentDeleteRequestedEvent;
import org.example.agent_qr.compensation.service.DocumentDeleteServiceV2;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <h3>批次 08 · 任务 8.4（问题 32）：接上物理文件清理</h3>
 * <p>
 * 事件字段 {@code filePath} 的用途在设计 §7.2.6 中写明为"清理上传文件"，
 * 但消费方此前<b>从未读取</b>——于是文档删除后 {@code uploads/} 下的物理文件永久残留。
 * 现按如下顺序处理（失败语义见下）：
 * </p>
 * <ol>
 *   <li>MySQL 逻辑删除（切片 + 文档）；</li>
 *   <li>ChromaDB 物理删除（由 {@link DocumentDeleteServiceV2} 负责，失败自身入 DLQ）；</li>
 *   <li>物理文件清理 —— 与 ChromaDB 删除<b>并列</b>（互不阻断），
 *       失败时单独入 DLQ（{@code DELETE} 类型，payload 带 {@code filePath}）。</li>
 * </ol>
 * <p>
 * 之所以文件清理失败<b>不</b>抛出中断 ChromaDB 删除：向量删除是检索一致性的关键路径，
 * 不应被磁盘问题阻断；而文件失败的可见性由 DLQ 记录保证
 * （{@code DlqRetryScheduler#retryDelete} 会按 payload 中的 {@code filePath} 重试文件删除）。
 * </p>
 * <p>
 * 关于 {@code chunkIds} 字段（任务 8.4.4）：保留并<b>明确用途</b>——
 * 它随失败时的 DLQ payload 一并落库，供人工审计"该文档删除了哪些切片"；
 * 从事件中移除需要改动 agent-qr-common 的事件类与发布方，超出本批次可改文件范围。
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
     * 物理文件清理服务（knowledge 模块，compensation 单向依赖 knowledge）。
     * <p>可选注入：compensation 的单元测试与被裁剪部署可缺少该 Bean，
     * 缺失时只告警并跳过文件清理，不影响删除主链路。</p>
     */
    @Autowired(required = false)
    private FileStorageService fileStorageService;

    /**
     * 处理文档删除请求事件。
     * <p>
     * 流程：
     * <ol>
     *   <li>MySQL 逻辑删除：chunkMapper.softDeleteByDocumentId + documentMapper.softDelete</li>
     *   <li>ChromaDB 物理删除：documentDeleteServiceV2.asyncPhysicalDelete</li>
     *   <li>物理文件清理：fileStorageService.delete(filePath)（幂等；失败入 DLQ）</li>
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
        log.info("收到文档删除请求事件: documentId={}, chunkIds={}, chromaIds={}, filePath={}",
                documentId, event.getChunkIds() != null ? event.getChunkIds().size() : 0,
                event.getChromaIds() != null ? event.getChromaIds().size() : 0,
                event.getFilePath());

        try {
            // 1. MySQL 逻辑删除
            chunkMapper.softDeleteByDocumentId(documentId);
            documentMapper.softDelete(documentId);
            log.info("MySQL 逻辑删除完成: documentId={}", documentId);

            // 2. ChromaDB 物理删除
            documentDeleteServiceV2.asyncPhysicalDelete(documentId, event.getChromaIds());

            // 3. 物理文件清理（任务 8.4；与 ChromaDB 删除并列，失败不阻断前者）
            cleanPhysicalFile(documentId, event.getFilePath());

        } catch (Exception e) {
            log.error("文档删除补偿处理失败: documentId={}, error={}", documentId, e.getMessage(), e);
            String payload = String.format("{\"documentId\":%d,\"chunkIds\":%s,\"chromaIds\":%s}",
                    documentId,
                    event.getChunkIds() != null ? event.getChunkIds().toString() : "[]",
                    event.getChromaIds() != null ? event.getChromaIds().toString() : "[]");
            deadLetterQueue.enqueue(DlqMessage.EVENT_DELETE, documentId, payload, e);
        }
    }

    /**
     * 清理文档对应的上传物理文件（任务 8.4.1 / 8.4.3）。
     * <p>
     * {@code filePath} 为空时不调用删除（无需清理）；文件本就不存在时
     * {@link FileStorageService#delete(String)} 静默返回 true（幂等）。
     * 删除失败时入 DLQ——payload 携带 {@code filePath}，供
     * {@code DlqRetryScheduler#retryDelete} 重试文件删除。
     * </p>
     *
     * @param documentId 文档 ID
     * @param filePath   文件相对路径（可为空）
     */
    private void cleanPhysicalFile(Long documentId, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            log.info("文档无关联物理文件，跳过清理: documentId={}", documentId);
            return;
        }
        if (fileStorageService == null) {
            log.warn("FileStorageService 未装配，跳过物理文件清理: documentId={}, filePath={}",
                    documentId, filePath);
            return;
        }

        boolean deleted = fileStorageService.delete(filePath);
        if (deleted) {
            log.info("物理文件清理完成: documentId={}, filePath={}", documentId, filePath);
            return;
        }

        log.error("物理文件清理失败，进入 DLQ 待重试: documentId={}, filePath={}", documentId, filePath);
        String payload = String.format("{\"documentId\":%d,\"filePath\":\"%s\"}", documentId, filePath);
        deadLetterQueue.enqueue(DlqMessage.EVENT_DELETE, documentId, payload,
                new IllegalStateException("物理文件删除失败: " + filePath));
    }
}
