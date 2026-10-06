package org.example.agent_qr.compensation.service;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.compensation.entity.DeleteTask;
import org.example.agent_qr.compensation.mapper.DeleteTaskMapper;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档删除服务 V2 — ChromaDB 物理删除。
 * <p>
 * 独立于 knowledge 模块，负责异步物理删除 ChromaDB 中的向量记录。
 * 通过 {@link ChromaRetriever} 封装类操作 ChromaDB，失败时通过 DLQ 重试。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class DocumentDeleteServiceV2 {

    @Autowired(required = false)
    private ChromaRetriever chromaRetriever;

    @Autowired
    private DeleteTaskMapper deleteTaskMapper;

    @Autowired
    private DeadLetterQueue deadLetterQueue;

    /**
     * 异步物理删除 ChromaDB 向量记录。
     * <p>
     * 流程：创建 DeleteTask(PENDING) → chromaRetriever.deleteByIds →
     * 成功 → updateStatus(DONE) / 失败 → incrementRetryCount + updateStatus(FAILED) + DLQ 入队。
     * </p>
     *
     * @param documentId 文档 ID
     * @param chromaIds  ChromaDB 向量 ID 列表
     */
    @Async("deleteExecutor")
    public void asyncPhysicalDelete(Long documentId, List<String> chromaIds) {
        // 1. 创建删除任务记录
        DeleteTask task = new DeleteTask();
        task.setDocumentId(documentId);
        task.setChromaIds(String.join(",", chromaIds != null ? chromaIds : List.of()));
        task.setStatus(DeleteTask.STATUS_PENDING);
        task.setRetryCount(0);
        task.setCreateTime(LocalDateTime.now());
        deleteTaskMapper.insert(task);

        if (chromaIds == null || chromaIds.isEmpty()) {
            deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_DONE);
            log.info("ChromaDB 物理删除无需操作（无向量 ID）: documentId={}", documentId);
            return;
        }

        // 2. ChromaDB 物理删除（通过 ChromaRetriever 封装）
        try {
            if (chromaRetriever != null) {
                chromaRetriever.deleteByIds(chromaIds);
            } else {
                log.warn("ChromaRetriever 未初始化，跳过物理删除: documentId={}", documentId);
            }

            // 3. 标记完成
            deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_DONE);
            log.info("ChromaDB 物理删除完成: documentId={}, chromaIdCount={}", documentId, chromaIds.size());
        } catch (Exception e) {
            log.error("ChromaDB 物理删除失败: documentId={}, error={}", documentId, e.getMessage(), e);
            deleteTaskMapper.incrementRetryCount(task.getId());

            // 失败状态落库：否则任务永久停留 PENDING，运维无法区分"执行中"与"已失败"（设计 §10.4）
            deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_FAILED);

            // DLQ 入队
            String payload = String.format("{\"documentId\":%d,\"chromaIds\":\"%s\"}",
                    documentId, String.join(",", chromaIds));
            deadLetterQueue.enqueue("DELETE", documentId, payload, e);
        }
    }
}
