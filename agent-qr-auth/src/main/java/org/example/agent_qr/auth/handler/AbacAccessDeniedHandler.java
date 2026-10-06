package org.example.agent_qr.auth.handler;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.common.Result;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ABAC 访问拒绝处理器 —— {@link AccessDeniedException} 的<b>唯一</b>处理者（问题 41）。
 * <p>
 * 修复前 {@code GlobalExceptionHandler}（agent-qr-web）与本类都声明了
 * {@code @ExceptionHandler(AccessDeniedException.class)} 且均无 {@code @Order}，
 * 实际生效者取决于 Bean 注册顺序——响应结构（裸 {@code Map} vs {@code Result}）
 * 与 HTTP 状态码（200 + body code=403 vs 403）都不确定。
 * </p>
 * <p>
 * 现约定：
 * <ol>
 *   <li>本类是 {@code AccessDeniedException} 的唯一处理者（另一处已移除），
 *       并以 {@link Ordered#HIGHEST_PRECEDENCE} 保证先于
 *       {@code GlobalExceptionHandler} 的兜底 {@code Exception} 处理器被解析；</li>
 *   <li>响应体统一为 {@link Result}（前端拦截器依赖该结构），HTTP 状态码统一为 <b>403</b>
 *       （设计 §3.2.14）；</li>
 *   <li>审计字段对齐设计 §3.2.14：{@code user} / {@code department} / {@code uri} /
 *       {@code method} / {@code traceId} / {@code timestamp} / {@code reason}，
 *       并额外保留 {@code userId} / {@code role} 以丰富审计线索；</li>
 *   <li>过滤器链层面的拒绝（如 {@code hasRole("ADMIN")} 路由规则）也复用
 *       {@link #buildAuditLog(HttpServletRequest, AccessDeniedException)} 与
 *       {@link #buildDeniedResult(AccessDeniedException)}，保证两条 403 路径的响应完全一致。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AbacAccessDeniedHandler {

    /** 未认证时的用户占位值（与设计 §3.2.14 的约定一致）。 */
    private static final String ANONYMOUS = "anonymous";

    /** 属性缺失时的占位值（与设计 §3.2.14 的约定一致）。 */
    private static final String NOT_APPLICABLE = "N/A";

    /** 无异常明细时的兜底原因。 */
    private static final String DEFAULT_REASON = "访问被拒绝";

    /**
     * 处理 {@link AccessDeniedException}（方法级 {@code @PreAuthorize} 拒绝等）。
     *
     * @param ex      访问拒绝异常
     * @param request 当前请求（用于提取 uri / method 审计字段）
     * @return HTTP 403 + 统一 {@link Result} 结构
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex,
                                                           HttpServletRequest request) {
        Map<String, Object> auditLog = buildAuditLog(request, ex);
        log.warn("ABAC 访问拒绝: {}", auditLog);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(buildDeniedResult(ex));
    }

    /**
     * 构建结构化审计日志载荷。
     * <p>
     * 供 {@code @ExceptionHandler} 与过滤器链层面的拒绝处理共用，
     * 保证两条 403 路径的审计信息一致。
     * </p>
     *
     * @param request 当前请求
     * @param ex      访问拒绝异常
     * @return 审计字段（含 user / department / uri / method / traceId / timestamp / reason）
     */
    public Map<String, Object> buildAuditLog(HttpServletRequest request, AccessDeniedException ex) {
        Map<String, Object> auditLog = new LinkedHashMap<>();
        auditLog.put("timestamp", LocalDateTime.now().toString());
        auditLog.put("status", HttpStatus.FORBIDDEN.value());
        auditLog.put("error", HttpStatus.FORBIDDEN.getReasonPhrase());
        auditLog.put("traceId", MDC.get("traceId"));
        auditLog.put("uri", request != null ? request.getRequestURI() : NOT_APPLICABLE);
        auditLog.put("method", request != null ? request.getMethod() : NOT_APPLICABLE);
        auditLog.put("reason", ex != null ? ex.getMessage() : null);

        // 提取当前用户信息（未认证时为占位值）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            auditLog.put("userId", principal.getUserId());
            auditLog.put("user", principal.getUsername());
            auditLog.put("role", principal.getRole());
            auditLog.put("department", principal.getDepartment());
        } else {
            auditLog.put("user", ANONYMOUS);
            auditLog.put("department", NOT_APPLICABLE);
        }
        return auditLog;
    }

    /**
     * 构建统一的 403 响应体（前端拦截器依赖 {@link Result} 结构）。
     *
     * @param ex 访问拒绝异常
     * @return 统一错误结果，{@code code=403}
     */
    public Result<Void> buildDeniedResult(AccessDeniedException ex) {
        String reason = (ex != null && ex.getMessage() != null) ? ex.getMessage() : DEFAULT_REASON;
        return Result.error(HttpStatus.FORBIDDEN.value(), "权限不足: " + reason);
    }
}
