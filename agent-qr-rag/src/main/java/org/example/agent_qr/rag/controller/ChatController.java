package org.example.agent_qr.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.common.Result;
import org.example.agent_qr.rag.entity.Conversation;
import org.example.agent_qr.rag.entity.Message;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.rag.service.ChatQueryService;
import org.example.agent_qr.rag.service.ConversationService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 问答控制器，提供会话管理和知识库问答的 REST API。
 * <p>
 * P1 原有：同步问答 + 会话管理。
 * P2 扩展：SSE 流式问答端点 + 满意度反馈端点。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatQueryService chatQueryService;
    private final ConversationService conversationService;
    private final MessageMapper messageMapper;

    /**
     * 同步知识库问答接口（P1 保留，改造为使用混合检索）。
     * <p>
     * ★ 问题 09 + 33 断裂 3：
     * <ul>
     *   <li>{@code domain} 必须由请求体传入并强制校验，缺失时返回 400——
     *       {@code canQueryDomain} 对 {@code domain == null} 是放行的，
     *       若不强制要求，就等于留下"不传域即全局检索"的绕过路径；</li>
     *   <li>越域提问经 {@code @PreAuthorize} 拒绝，响应为统一 403（问题 41 的处理器）。</li>
     * </ul>
     * </p>
     */
    @PostMapping("/ask")
    @PreAuthorize("@abac.canQueryDomain(principal, #request['domain'])")
    public Result<Map<String, Object>> ask(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        if (query == null || query.isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        String domain = requireDomain(request);
        Long conversationId = request.get("conversationId") != null
                ? Long.valueOf(request.get("conversationId").toString())
                : null;
        Long userId = getCurrentUserId();
        Map<String, Object> result = chatQueryService.ask(query, conversationId, userId, domain);
        return Result.success(result);
    }

    /**
     * SSE 流式问答接口（P2 新增）。
     * <p>
     * 返回 SseEmitter（超时 5 分钟），逐 token 推送 AI 生成内容。
     * 事件类型：token（内容片段）、done（完成 + 元数据）、error（错误）。
     * </p>
     * <p>
     * ★ 问题 09 + 33 断裂 3：与 {@code /ask} 一致，必须携带 {@code domain} 并通过 ABAC 校验。
     * </p>
     *
     * @param request 请求体，包含 query、conversationId 和 domain
     * @return SseEmitter 实例
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@abac.canQueryDomain(principal, #request['domain'])")
    public SseEmitter askStream(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        if (query == null || query.isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        String domain = requireDomain(request);
        Long conversationId = request.get("conversationId") != null
                ? Long.valueOf(request.get("conversationId").toString())
                : null;
        Long userId = getCurrentUserId();

        SseEmitter emitter = new SseEmitter(300000L); // 5 分钟超时
        chatQueryService.askStream(query, conversationId, userId, domain, emitter);
        return emitter;
    }

    /**
     * 解析并强制校验请求体中的 {@code domain}（问题 09 策略：问答必须指定业务域）。
     * <p>
     * {@code canQueryDomain} 对 {@code domain == null} 放行，因此"不传域"不能依赖 ABAC 拦截，
     * 必须在此显式拒绝，否则等于开放全局检索。
     * </p>
     *
     * @param request 请求体
     * @return 非空的业务域
     * @throws BusinessException 缺失或空白时返回 400
     */
    private String requireDomain(Map<String, Object> request) {
        Object raw = request.get("domain");
        String domain = raw != null ? raw.toString().trim() : null;
        if (domain == null || domain.isEmpty()) {
            throw new BusinessException(400, "缺少 domain 参数：问答必须指定业务域");
        }
        return domain;
    }

    /**
     * 满意度反馈接口（P2 新增）。
     * <p>
     * 对 AI 回答进行点赞/点踩，记录反馈和原因。
     * </p>
     *
     * @param messageId 消息 ID
     * @param request   反馈请求体（feedback: positive/negative, reason: 可选原因）
     * @return 操作结果
     */
    @PostMapping("/messages/{messageId}/feedback")
    public Result<Void> submitFeedback(@PathVariable Long messageId,
                                        @RequestBody Map<String, String> request) {
        String feedback = request.get("feedback");
        String reason = request.get("reason");

        if (feedback == null || feedback.isBlank()) {
            throw new BusinessException("反馈类型不能为空");
        }

        // 校验消息存在且为 AI 回复
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BusinessException("消息不存在");
        }
        if (!"assistant".equals(message.getRole())) {
            throw new BusinessException("只能对 AI 回答进行反馈");
        }

        messageMapper.updateFeedback(messageId, feedback, reason);
        log.info("满意度反馈: messageId={}, feedback={}, reason={}", messageId, feedback, reason);
        return Result.success("反馈提交成功");
    }

    @GetMapping("/conversations")
    public Result<List<Conversation>> listConversations() {
        Long userId = getCurrentUserId();
        List<Conversation> conversations = conversationService.listConversations(userId);
        return Result.success(conversations);
    }

    @GetMapping("/conversations/{id}/messages")
    public Result<List<Message>> getMessages(@PathVariable Long id) {
        List<Message> messages = messageMapper.selectByConversationId(id);
        return Result.success(messages);
    }

    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable Long id) {
        conversationService.deleteConversation(id);
        return Result.success();
    }

    /**
     * 从 Spring Security 上下文中获取当前登录用户 ID。
     * P2 改造：支持 UserPrincipal 和 SysUser 两种主体类型。
     */
    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(401, "用户未登录");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getUserId();
        }
        if (principal instanceof org.example.agent_qr.user.entity.SysUser sysUser) {
            return sysUser.getId();
        }
        if (principal instanceof Long userId) {
            return userId;
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            throw new BusinessException(401, "无法获取用户信息");
        }
    }
}
