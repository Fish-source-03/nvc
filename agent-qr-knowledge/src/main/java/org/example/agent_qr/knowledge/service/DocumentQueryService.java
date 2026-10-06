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

import java.util.List;

/**
 * 文档查询服务（P2 扩展：ABAC 权限检查）。
 * <p>
 * 按设计 §8.13.3 约定：查询侧方法一律标注 {@code @Transactional(readOnly = true)}，
 * 由 {@code ReadWriteDataSourceAspect} 读取该标志后路由到读库（CQRS）。
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
        return documentMapper.selectPage(new Page<>(page, size), null);
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
