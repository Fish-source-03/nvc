package org.example.agent_qr.web.security;

import org.example.agent_qr.auth.controller.AuthController;
import org.example.agent_qr.auth.filter.JwtAuthenticationFilter;
import org.example.agent_qr.auth.handler.AbacAccessDeniedHandler;
import org.example.agent_qr.auth.service.AuthService;
import org.example.agent_qr.auth.service.RefreshTokenService;
import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.user.entity.SysUser;
import org.example.agent_qr.web.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/auth/revoke} 端点测试（批次 03 · 任务 3.4，问题 08 + 33 断裂 4）。
 * <p>
 * 拦截的核心缺陷：前端 logout 一直调用 {@code POST /api/auth/revoke}，后端不存在该映射（404），
 * {@code RefreshTokenService.revoke(userId)} 已实现却无任何调用方——
 * 登出只清了浏览器本地 Token，服务端 Refresh Token 仍有效 7 天。
 * </p>
 *
 * @author agent-qr
 */
@WebMvcTest
@ContextConfiguration(classes = AuthRevokeEndpointTest.TestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "jwt.secret=agent-qr-test-secret-key-0123456789-abcdefghij",
        "jwt.access-expiration=1800",
        "jwt.refresh-expiration=604800"
})
class AuthRevokeEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    /** 用 @MockitoBean 而非 @Bean 注册：Mockito mock 若经 @Bean 返回，Spring 仍会对其实例字段做 @Autowired 注入。 */
    @MockitoBean
    private RefreshTokenService refreshTokenService;

    private String tokenOf(Long userId, String username, String role) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(1);
        user.setDepartment("HR");
        return jwtUtil.generateAccessToken(user);
    }

    @Test
    @DisplayName("★ 已认证用户调用 /api/auth/revoke 后，其 Refresh Token 被标记为已撤销")
    void revoke_shouldRevokeCurrentUserTokens() throws Exception {
        mockMvc.perform(post("/api/auth/revoke")
                        .header("Authorization", "Bearer " + tokenOf(7L, "chenming", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 撤销的是"当前登录用户"的令牌，而不是请求体传入的任意用户
        verify(refreshTokenService).revoke(eq(7L));
    }

    @Test
    @DisplayName("★ 未携带 Token 调用 /api/auth/revoke 被拒绝（端点不得加入白名单）")
    void revoke_shouldReject_whenAnonymous() throws Exception {
        mockMvc.perform(post("/api/auth/revoke"))
                .andExpect(status().is4xxClientError());

        verify(refreshTokenService, Mockito.never()).revoke(Mockito.anyLong());
    }

    /**
     * 最小切片上下文：真实安全过滤器链 + 被测控制器（依赖以 mock 提供）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(SecurityConfig.class)
    static class TestApplication {

        @Bean
        JwtUtil jwtUtil() {
            return new JwtUtil();
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter();
        }

        @Bean
        AbacAccessDeniedHandler abacAccessDeniedHandler() {
            return new AbacAccessDeniedHandler();
        }

        @Bean
        AuthService authService() {
            return Mockito.mock(AuthService.class);
        }

        @Bean
        AuthController authController() {
            return new AuthController();
        }
    }
}
