package org.example.agent_qr.web.security;

import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.auth.filter.JwtAuthenticationFilter;
import org.example.agent_qr.auth.handler.AbacAccessDeniedHandler;
import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.user.entity.SysUser;
import org.example.agent_qr.web.config.GlobalExceptionHandler;
import org.example.agent_qr.web.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebSocket 握手鉴权测试（批次 10 · 任务 10.2.1 / 10.2.2，问题 34）。
 * <p>
 * 拦截的核心缺陷：{@code SecurityConfig} 的 {@code /ws/** → permitAll()} 是一条
 * <b>悬空放行</b>——端点当时根本不存在，一旦补上端点就是未鉴权入口。
 * 本测试固化收敛后的行为：
 * </p>
 * <ol>
 *   <li>无令牌的握手请求被安全链<b>拒绝</b>（不再是 permitAll）；</li>
 *   <li>无效令牌同样被拒绝；</li>
 *   <li>携带有效 JWT（{@code ?token=} 查询参数，浏览器唯一可行的传递方式）的握手
 *       能通过安全层（到达应用层）；</li>
 *   <li>非浏览器客户端可用 {@code Authorization} 头完成同一校验。</li>
 * </ol>
 *
 * @author agent-qr
 */
@WebMvcTest
@ContextConfiguration(classes = WebSocketHandshakeSecurityTest.TestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "jwt.secret=agent-qr-test-secret-key-0123456789-abcdefghij",
        "jwt.access-expiration=1800",
        "jwt.refresh-expiration=604800"
})
class WebSocketHandshakeSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    @DisplayName("★ 无令牌的 /ws/** 握手被拒绝（/ws/** 不再是 permitAll）")
    void handshake_shouldBeRejected_whenNoToken() throws Exception {
        mockMvc.perform(get("/ws/info"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★ 无效令牌的握手被拒绝")
    void handshake_shouldBeRejected_whenTokenInvalid() throws Exception {
        mockMvc.perform(get("/ws/info").param("token", "not-a-valid-jwt"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★ 携带有效 JWT（?token= 查询参数）的握手通过安全层")
    void handshake_shouldPass_whenQueryTokenValid() throws Exception {
        mockMvc.perform(get("/ws/info").param("token", userToken(7L, "chenming", "user")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("有效 JWT 走 Authorization 头同样通过（非浏览器客户端）")
    void handshake_shouldPass_whenHeaderTokenValid() throws Exception {
        mockMvc.perform(get("/ws/info").header("Authorization", "Bearer " + userToken(1L, "admin", "admin")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("非 /ws 路径不受该过滤器影响（查询参数不会变成鉴权旁路）")
    void nonWebSocketPath_shouldNotBeAuthenticatedByQueryToken() throws Exception {
        mockMvc.perform(get("/api/probe").param("token", userToken(7L, "chenming", "user")))
                .andExpect(status().isForbidden());
    }

    private String userToken(Long userId, String username, String role) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(1);
        user.setDepartment("HR");
        user.setClearanceLevel(2);
        user.setAllowedDomains("HR");
        user.setTitle("manager");
        return jwtUtil.generateAccessToken(user);
    }

    /**
     * 最小切片上下文：真实安全过滤器链 + 两个探针端点。
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

        @Bean(name = "abac")
        AbacEvaluator abacEvaluator() {
            return new AbacEvaluator();
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        StubEndpoints stubEndpoints() {
            return new StubEndpoints();
        }
    }

    /** 探针端点：模拟 SockJS 的 /ws/info 与一个普通受保护接口。 */
    @RestController
    static class StubEndpoints {

        @GetMapping("/ws/info")
        public String wsInfo() {
            return "{\"websocket\":true}";
        }

        @GetMapping("/api/probe")
        public String probe() {
            return "ok";
        }
    }
}
