package org.example.agent_qr.auth.handler;

import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.common.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AbacAccessDeniedHandler} 单元测试（批次 03 · 任务 3.1，问题 41）。
 * <p>
 * 拦截的核心缺陷：
 * <ol>
 *   <li>两个 {@code @RestControllerAdvice} 争抢 {@code AccessDeniedException}，行为不确定；</li>
 *   <li>ABAC 拒绝返回裸 {@code Map}，与全站统一 {@code Result} 结构不一致；</li>
 *   <li>审计字段缺 {@code uri} 与 {@code method}，无法回答"哪个接口被越权访问"。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
class AbacAccessDeniedHandlerTest {

    private final AbacAccessDeniedHandler handler = new AbacAccessDeniedHandler();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @DisplayName("拒绝响应必须是统一 Result 结构且 HTTP 状态码为 403（前端拦截器依赖该结构）")
    void handleAccessDenied_shouldReturnUnifiedResultWith403() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");

        ResponseEntity<Result<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("Access Denied"), request);

        assertThat(response.getStatusCode().value())
                .as("设计 §3.2.14 要求 HTTP 403；两条路径（注解/路由规则）必须一致")
                .isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(403);
        assertThat(response.getBody().getMessage()).startsWith("权限不足");
        assertThat(response.getBody().getMessage()).contains("Access Denied");
    }

    @Test
    @DisplayName("审计字段完整：user/department/uri/method/traceId/timestamp/reason（设计 §3.2.14）")
    void buildAuditLog_shouldContainAllFieldsRequiredByDesign() {
        UserPrincipal principal = new UserPrincipal();
        principal.setUserId(42L);
        principal.setUsername("zhaoba");
        principal.setRole("user");
        principal.setDepartment("HR");
        principal.setAllowedDomains(List.of("HR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        MDC.put("traceId", "trace-abc-123");

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/admin/users/7");
        Map<String, Object> audit = handler.buildAuditLog(request, new AccessDeniedException("Access Denied"));

        assertThat(audit).containsKeys("user", "department", "uri", "method", "traceId", "timestamp", "reason");
        assertThat(audit.get("user")).isEqualTo("zhaoba");
        assertThat(audit.get("department")).isEqualTo("HR");
        assertThat(audit.get("uri")).isEqualTo("/api/admin/users/7");
        assertThat(audit.get("method")).isEqualTo("PUT");
        assertThat(audit.get("traceId")).isEqualTo("trace-abc-123");
        assertThat(audit.get("reason")).isEqualTo("Access Denied");
        assertThat(audit.get("timestamp")).isNotNull();
        // 额外的审计线索（保留原有能力，不弱于修复前）
        assertThat(audit.get("userId")).isEqualTo(42L);
        assertThat(audit.get("role")).isEqualTo("user");
    }

    @Test
    @DisplayName("未认证时审计字段不得为 null，使用 anonymous/N/A 占位")
    void buildAuditLog_shouldUsePlaceholdersWhenAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/ask");

        Map<String, Object> audit = handler.buildAuditLog(request, new AccessDeniedException("denied"));

        assertThat(audit.get("user")).isEqualTo("anonymous");
        assertThat(audit.get("department")).isEqualTo("N/A");
        assertThat(audit.get("uri")).isEqualTo("/api/chat/ask");
        assertThat(audit.get("method")).isEqualTo("POST");
    }

    @Test
    @DisplayName("异常无 message 时仍返回可读的错误信息，不得为空")
    void buildDeniedResult_shouldFallbackWhenMessageMissing() {
        Result<Void> result = handler.buildDeniedResult(new AccessDeniedException(null));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("权限不足: 访问被拒绝");
    }
}
