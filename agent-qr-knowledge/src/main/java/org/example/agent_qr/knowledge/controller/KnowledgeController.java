package org.example.agent_qr.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.common.Result;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.service.DocumentCommandService;
import org.example.agent_qr.knowledge.service.DocumentQueryService;
import org.example.agent_qr.user.entity.SysUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库管理控制器。
 * <p>
 * P1 原有：上传/查询/删除。
 * P2 扩展：ABAC 注解权限控制、domain+sensitivityLevel 上传参数、软删除端点。
 * 文档级 ABAC 检查（canAccessDocument/canDeleteDocument）在 Service 层执行，
 * 因为需要先查询文档属性后再调用 AbacEvaluator。
 * </p>
 *
 * @author agent-qr
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final DocumentCommandService documentCommandService;
    private final DocumentQueryService documentQueryService;

    /**
     * 上传文档到知识库（P2 扩展：新增 domain 和 sensitivityLevel 参数）。
     * canUploadToDomain 基于用户属性即可判断，无需查询文档。
     */
    @PostMapping("/upload")
    @PreAuthorize("@abac.canUploadToDomain(principal, #domain)")
    public Result<Document> upload(
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false, defaultValue = "0") Integer sensitivityLevel) {
        Long userId = getCurrentUserId();
        Document document = documentCommandService.uploadDocument(file, title, userId, domain, sensitivityLevel);
        return Result.success("文档上传成功", document);
    }

    /**
     * 分页查询文档列表，支持按业务域 / 密级筛选。
     * <p>
     * <b>批次 09 · 任务 9.3（问题 33 断裂 1）</b>：前端（{@code api/knowledge.ts} +
     * {@code KnowledgeView.vue}）一直在传 {@code domain} 与 {@code sensitivityLevel}，
     * 而本方法此前只声明了 {@code page}/{@code size}——Spring <b>静默忽略</b>多余的查询参数，
     * 于是"业务域/密级"筛选控件点了没用、也不报错。现补齐两个参数并透传到查询条件。
     * </p>
     * <p>
     * 语义：两个参数均为"用户主动缩小范围"，可选；缺省表示不筛。
     * 越权范围不由这里控制（入口鉴权 + 检索侧 ABAC 兜底属批次 03），
     * 本处只做筛选，不引入权限判定。
     * </p>
     *
     * @param page             页码（默认 1）
     * @param size             每页条数（默认 10）
     * @param domain           业务域（可空；未知域返回空列表——域是开放取值，不是错误）
     * @param sensitivityLevel 密级（可空；非整数由 Spring 绑定层拒绝并报错，不会被静默忽略）
     * @return 分页结果
     */
    @GetMapping("/documents")
    public Result<IPage<Document>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) Integer sensitivityLevel) {
        IPage<Document> pageResult = documentQueryService.listDocuments(page, size, domain, sensitivityLevel);
        return Result.success(pageResult);
    }

    /**
     * 查询单个文档详情 — ABAC 检查在 DocumentQueryService 中执行。
     */
    @GetMapping("/documents/{id}")
    public Result<Document> getDocument(@PathVariable Long id) {
        Document document = documentQueryService.getDocumentWithAbac(id);
        return Result.success(document);
    }

    /**
     * 软删除文档 — ABAC 检查在 DocumentCommandService 中执行。
     */
    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        documentCommandService.requestDeleteDocument(id);
        return Result.success("文档删除请求已提交", null);
    }

    @GetMapping("/documents/{id}/status")
    public Result<String> getDocumentStatus(@PathVariable Long id) {
        String status = documentQueryService.getStatus(id);
        return Result.success(status);
    }

    @GetMapping("/documents/{id}/chunks")
    public Result<List<Chunk>> getDocumentChunks(@PathVariable Long id) {
        List<Chunk> chunks = documentQueryService.getChunks(id);
        return Result.success(chunks);
    }

    private Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getUserId();
        }
        if (principal instanceof SysUser user) {
            return user.getId();
        }
        throw new RuntimeException("无法获取当前用户信息");
    }
}
