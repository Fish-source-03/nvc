package org.example.agent_qr.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档查询服务（P2 扩展：ABAC 权限检查）。
 * <p>
 * 按设计 §8.13.3 约定：查询侧方法一律标注 {@code @Transactional(readOnly = true)}，
 * 由 {@code ReadWriteDataSourceAspect} 读取该标志后路由到读库（CQRS）。
 * </p>
 * <p>
 * <b>批次 07 · 任务 7.0.4 —— 文档状态由切片聚合推导</b>：
 * 触发时机选择<b>查询时实时计算</b>（而非"chunk 状态变更时回调回写"）。
 * 理由：
 * <ol>
 *   <li>{@code progress.md} 的既有决策 #11 对同类问题（聚合路径）已定为"实时聚合"，
 *       本次沿用同一取向，避免两套口径；</li>
 *   <li>回调回写需要每条 chunk 状态变更路径都记得触发文档聚合，漏一处即产生
 *       "已就绪但仍显示处理中"的静默不一致；实时计算天然与切片状态一致；</li>
 *   <li>开销可控——列表页用一次 {@code GROUP BY document_id, status} 批量查询覆盖整页，
 *       详情页一次单文档查询，均为索引命中（{@code idx_document_id}/{@code idx_status}）。</li>
 * </ol>
 * 库内的 {@code kb_document.status} 仍会被写入链路更新（作为"最近一次已知状态"），
 * 但对外展示一律以聚合推导结果为准。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentQueryService {

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final AbacEvaluator abacEvaluator;

    @Transactional(readOnly = true)
    public IPage<Document> listDocuments(int page, int size) {
        IPage<Document> pageResult = documentMapper.selectPage(new Page<>(page, size), null);
        applyAggregatedStatus(pageResult.getRecords());
        return pageResult;
    }

    /**
     * 根据 ID 获取文档详情（无 ABAC 检查，供内部调用）。
     */
    @Transactional(readOnly = true)
    public Document getDocument(Long id) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException(404, "文档不存在");
        }
        applyAggregatedStatus(List.of(document));
        return document;
    }

    /**
     * 根据 ID 获取文档详情（带 ABAC 检查，供 Controller 调用）。
     */
    @Transactional(readOnly = true)
    public Document getDocumentWithAbac(Long id) {
        Document document = getDocument(id);

        // ABAC 文档级检查
        UserPrincipal user = getCurrentUser();
        if (!abacEvaluator.canAccessDocument(user, document.getDomain(), document.getSensitivityLevel())) {
            throw new AccessDeniedException("无权访问该文档");
        }

        return document;
    }

    @Transactional(readOnly = true)
    public String getStatus(Long id) {
        Document document = getDocument(id);
        DocumentStatus status = document.getStatus();
        return status != null ? status.name() : null;
    }

    @Transactional(readOnly = true)
    public List<Chunk> getChunks(Long documentId) {
        return chunkMapper.selectByDocumentId(documentId);
    }

    // ==================== 批次 07 · 任务 7.0.4：文档状态聚合推导 ====================

    /**
     * 批量用切片聚合状态覆盖文档的展示状态（库内状态不变）。
     * <p>列表页一次 {@code GROUP BY document_id, status} 查询覆盖整页，避免 N+1。</p>
     *
     * @param documents 待处理的文档列表（可为空）
     */
    private void applyAggregatedStatus(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        List<Long> ids = documents.stream()
                .filter(doc -> needsAggregation(doc.getStatus()))
                .map(Document::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }

        Map<Long, Map<String, Integer>> countsByDocument = new HashMap<>();
        for (Map<String, Object> row : chunkMapper.countByDocumentIdsGroupByStatus(ids)) {
            Long documentId = toLong(row.get("documentId"));
            String status = row.get("status") == null ? null : row.get("status").toString();
            Integer cnt = toInt(row.get("cnt"));
            if (documentId == null || status == null || cnt == null) {
                continue;
            }
            countsByDocument.computeIfAbsent(documentId, k -> new HashMap<>()).put(status, cnt);
        }

        for (Document document : documents) {
            if (!needsAggregation(document.getStatus())) {
                continue;
            }
            Map<String, Integer> counts = countsByDocument.get(document.getId());
            if (counts == null || counts.isEmpty()) {
                continue;   // 尚无切片：保留库内状态
            }
            document.setStatus(deriveStatus(counts));
        }
    }

    /**
     * 判断该状态是否需要被聚合结果覆盖。
     * <p>
     * 前置阶段（{@code UPLOADED}/{@code PARSING}）与异常/终态（{@code FAILED}/{@code DELETING}）
     * 保留库内值——它们描述的不是"切片进度"，覆盖会让失败文档看起来像在正常处理。
     * </p>
     *
     * @param stored 库内状态
     * @return true 表示应尝试用切片聚合结果覆盖
     */
    static boolean needsAggregation(DocumentStatus stored) {
        return stored != null
                && stored != DocumentStatus.UPLOADED
                && stored != DocumentStatus.PARSING
                && stored != DocumentStatus.FAILED
                && stored != DocumentStatus.DELETING;
    }

    /**
     * 按切片状态计数推导文档状态（批次 07 · 任务 7.0.4 的推导规则）。
     * <p>
     * 规则（<b>先判在途、再判已入库</b>）：
     * <ul>
     *   <li>全部切片为 {@code READY} → {@code READY}（关键词与语义都可搜）；</li>
     *   <li>存在 {@code EMBEDDING}/{@code PENDING} → {@code EMBEDDING}（有在途向量化）；</li>
     *   <li>其余（存在 {@code INDEXED}）→ {@code INDEXED}（部分就绪，关键词可搜）。</li>
     * </ul>
     * ⚠️ 与任务书表格的一处顺序调整：表格把"存在 INDEXED"排在"存在 EMBEDDING"之前，
     * 但混合状态（部分切片在途、部分待处理）下应取<b>在途</b>——否则前端会停止轮询，
     * 用户会一直看到"部分就绪"。此为按意图修正，已记入批次报告。
     * </p>
     *
     * @param statusCounts 状态 → 切片数
     * @return 推导出的文档状态
     */
    public static DocumentStatus deriveStatus(Map<String, Integer> statusCounts) {
        int total = statusCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return DocumentStatus.INDEXED;
        }
        int ready = statusCounts.getOrDefault(Chunk.STATUS_READY, 0);
        if (ready == total) {
            return DocumentStatus.READY;
        }
        int inFlight = statusCounts.getOrDefault(DocumentStatus.EMBEDDING.name(), 0)
                + statusCounts.getOrDefault(Chunk.STATUS_PENDING, 0);
        if (inFlight > 0) {
            return DocumentStatus.EMBEDDING;
        }
        return DocumentStatus.INDEXED;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(value.toString());
    }

    /**
     * 从 SecurityContext 获取当前 UserPrincipal。
     */
    private UserPrincipal getCurrentUser() {
        Object principal = SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        throw new AccessDeniedException("无法获取当前用户信息");
    }
}
