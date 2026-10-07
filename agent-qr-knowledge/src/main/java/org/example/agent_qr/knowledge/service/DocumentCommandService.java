package org.example.agent_qr.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.common.event.DocumentDeleteRequestedEvent;
import org.example.agent_qr.common.event.DocumentUploadedEvent;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * 文档命令服务（P2 扩展：ABAC 权限检查 + 软删除）。
 *
 * @author agent-qr
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCommandService {

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final AbacEvaluator abacEvaluator;

    private static final Set<String> ALLOWED_TYPES = Set.of("pdf", "docx", "txt", "md");
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    @Transactional
    public Document uploadDocument(MultipartFile file, String title, Long userId,
                                    String domain, Integer sensitivityLevel) {
        String originalFilename = file.getOriginalFilename();
        String ext = getFileExtension(originalFilename);

        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BusinessException("不支持的文件类型: " + ext + "，仅支持 pdf、docx、txt、md");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过50MB");
        }

        String filePath = fileStorageService.store(file);
        String docTitle = (title == null || title.isBlank()) ? originalFilename : title;

        Document doc = new Document();
        doc.setTitle(docTitle);
        doc.setFileName(originalFilename);
        doc.setFilePath(filePath);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStatus(DocumentStatus.UPLOADED);
        doc.setUploadUserId(userId);
        doc.setDomain(domain);
        doc.setSensitivityLevel(sensitivityLevel);
        doc.setSensitivityLabel(mapSensitivityLabel(sensitivityLevel));
        doc.setDeleted(0);

        documentMapper.insert(doc);

        eventPublisher.publishEvent(new DocumentUploadedEvent(
                this, doc.getId(), filePath, originalFilename, ext, userId));

        log.info("文档上传成功: id={}, title={}, domain={}, sensitivityLevel={}",
                doc.getId(), docTitle, domain, sensitivityLevel);
        return doc;
    }

    /**
     * 请求软删除文档 — 含 ABAC 检查 + 状态校验 + 并发抢占。
     * <p>
     * <b>批次 09 · 任务 9.6（问题 42）</b>：原实现只有"文档存在"与"ABAC 权限"两项校验，
     * 没有状态校验——前端重复点击、请求重试或并发请求时，同一文档会被<b>重复发布删除事件</b>，
     * 下游（compensation 模块）每收到一次事件就 {@code deleteTaskMapper.insert} 一次，
     * 于是产生重复的 {@code delete_task} 与重复的 ChromaDB 删除调用。
     * </p>
     * <p>
     * 现在的四道关卡（<b>ABAC 检查保留，与状态检查并存</b>）：
     * </p>
     * <ol>
     *   <li>存在性：{@code selectById} 为 null → 404。
     *       经 {@code @TableLogic} 过滤，已软删（DELETED）的文档同样落在这里 → 404；</li>
     *   <li>软删标记：显式复核 {@code deleted} 字段（防御绕过逻辑删除过滤的取值路径）→ 409；</li>
     *   <li>状态校验：{@link DocumentStatus#DELETING} → 409
     *       （设计 §5.2.1 要求"状态为 DELETING 时报错"）。这一步给出<b>明确错误</b>，
     *       避免并发时都走到"抢占失败"而丢失可读原因；</li>
     *   <li>条件更新抢占（{@link DocumentMapper#claimDeleting}）：把"检查 + 置位"合并成
     *       一条原子 SQL，按影响行数判定谁抢占成功——读-判断-写之间的竞态窗口由此关闭，
     *       {@code @Transactional} 只能保证单次执行的原子性，挡不住两个并发请求。</li>
     * </ol>
     *
     * @param documentId 文档 ID
     * @throws BusinessException       404 文档不存在（含已软删）；409 正在删除中 / 已被删除 / 抢占失败
     * @throws AccessDeniedException   无删除权限（ABAC）
     */
    @Transactional
    public void requestDeleteDocument(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }

        // ABAC 文档级检查（保留：与状态检查并存，两者不可互相替代）
        UserPrincipal user = getCurrentUser();
        if (!abacEvaluator.canDeleteDocument(user, doc.getDomain(), doc.getSensitivityLevel())) {
            throw new AccessDeniedException("无权删除该文档");
        }

        // 9.6.1-a：已删除（DELETED）状态拒绝——软删文档不应再走删除流程
        if (doc.getDeleted() != null && doc.getDeleted() != 0) {
            throw new BusinessException(409, "文档已删除，请勿重复操作");
        }

        // 9.6.1-b：DELETING 状态拒绝（设计 §5.2.1）
        if (DocumentStatus.DELETING == doc.getStatus()) {
            throw new BusinessException(409, "文档正在删除中，请勿重复操作");
        }

        // 9.6.2：条件更新抢占——并发下只有一个请求能拿到影响行数 1
        int affected = documentMapper.claimDeleting(documentId);
        if (affected == 0) {
            throw new BusinessException(409, "文档正在删除中，请勿重复操作");
        }

        // 收集关联信息
        List<Long> chunkIds = chunkMapper.selectByDocumentId(documentId).stream()
                .map(org.example.agent_qr.knowledge.entity.Chunk::getId)
                .toList();
        List<String> chromaIds = chunkMapper.selectChromaIdsByDocumentId(documentId);

        // 发布事件（仅在抢占成功后才发布 —— 保证一个文档最多一次删除事件）
        DocumentDeleteRequestedEvent event = new DocumentDeleteRequestedEvent(
                documentId, chunkIds, chromaIds, doc.getFilePath());
        eventPublisher.publishEvent(event);

        log.info("文档软删除请求已发布: documentId={}, chunkCount={}, chromaIdCount={}",
                documentId, chunkIds.size(), chromaIds.size());
    }

    @Deprecated
    @Transactional
    public void deleteDocument(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        fileStorageService.delete(doc.getFilePath());
        chunkMapper.deleteByDocumentId(documentId);
        documentMapper.deleteById(documentId);
        log.info("文档已物理删除: id={}, fileName={}", documentId, doc.getFileName());
    }

    private String mapSensitivityLabel(Integer level) {
        if (level == null) return "公开";
        return switch (level) {
            case 1 -> "内部";
            case 2 -> "机密";
            case 3 -> "绝密";
            default -> "公开";
        };
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

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
