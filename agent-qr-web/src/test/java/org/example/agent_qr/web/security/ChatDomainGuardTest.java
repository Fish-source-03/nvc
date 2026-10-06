package org.example.agent_qr.web.security;

import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.auth.filter.JwtAuthenticationFilter;
import org.example.agent_qr.auth.handler.AbacAccessDeniedHandler;
import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.rag.controller.ChatController;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.rag.service.ChatQueryService;
import org.example.agent_qr.rag.service.ConversationService;
import org.example.agent_qr.user.entity.SysUser;
import org.example.agent_qr.web.config.GlobalExceptionHandler;
import org.example.agent_qr.web.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问答接口域鉴权测试（批次 03 · 任务 3.5，问题 09 + 33 断裂 3）。
 * <p>
 * 拦截的核心缺陷：
 * <ol>
 *   <li>问答接口没有任何 ABAC 判定，任何登录用户可对全部业务域提问；</li>
 *   <li>前端已传 {@code domain}，后端只取 {@code query}/{@code conversationId}，域选择器形同虚设；</li>
 *   <li>{@code canQueryDomain} 对 {@code domain == null} 放行 ——
 *       "不传域即全局检索"是一条必须显式堵住的绕过路径（本测试固化的策略：缺失 → 400）。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
@WebMvcTest
@ContextConfiguration(classes = ChatDomainGuardTest.TestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "jwt.secret=agent-qr-test-secret-key-0123456789-abcdefghij",
        "jwt.access-expiration=1800",
        "jwt.refresh-expiration=604800"
})
class ChatDomainGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private ChatQueryService chatQueryService;

    @Test
    @DisplayName("★ 用户携带无权限的 domain 提问返回 403（越域提问被拒绝）")
    void ask_shouldReturn403_whenDomainNotAllowed() throws Exception {
        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + hrUserToken())
                        .content("{\"query\":\"薪资结构是怎样的\",\"domain\":\"FINANCE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(chatQueryService, never()).ask(any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 用户携带自身有权限的 domain 提问正常返回，且 domain 被下传到服务层")
    void ask_shouldSucceed_whenDomainAllowed() throws Exception {
        Mockito.when(chatQueryService.ask(any(), any(), any(), any()))
                .thenReturn(Map.of("answer", "ok"));

        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + hrUserToken())
                        .content("{\"query\":\"请假流程\",\"domain\":\"HR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(chatQueryService).ask(eq("请假流程"), any(), eq(7L), eq("HR"));
    }

    @Test
    @DisplayName("★ 未携带 domain 的请求被强制拒绝（code=400），不得退化为全局检索")
    void ask_shouldReturn400_whenDomainMissing() throws Exception {
        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + hrUserToken())
                        .content("{\"query\":\"全局搜索\"}"))
                .andExpect(jsonPath("$.code").value(400));

        verify(chatQueryService, never()).ask(any(), any(), any(), any());
    }

    @Test
    @DisplayName("空白 domain 同样被拒绝，不得退化为全局检索（@PreAuthorize 先于方法体执行，按越域拒绝）")
    void ask_shouldReject_whenDomainBlank() throws Exception {
        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + hrUserToken())
                        .content("{\"query\":\"全局搜索\",\"domain\":\"   \"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(chatQueryService, never()).ask(any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ SSE 端点 /ask/stream 同样校验 domain：无权限返回 403")
    void askStream_shouldReturn403_whenDomainNotAllowed() throws Exception {
        mockMvc.perform(post("/api/chat/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + hrUserToken())
                        .content("{\"query\":\"财务数据\",\"domain\":\"FINANCE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(chatQueryService, never()).askStream(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SSE 端点未携带 domain 同样被拒绝（code=400）")
    void askStream_shouldReturn400_whenDomainMissing() throws Exception {
        mockMvc.perform(post("/api/chat/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + hrUserToken())
                        .content("{\"query\":\"无域提问\"}"))
                .andExpect(jsonPath("$.code").value(400));

        verify(chatQueryService, never()).askStream(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ SSE 端点：有权限的 domain 被下传到服务层")
    void askStream_shouldPassDomainToService_whenAllowed() throws Exception {
        doAnswer(invocation -> {
            SseEmitter emitter = invocation.getArgument(4);
            emitter.complete();
            return null;
        }).when(chatQueryService).askStream(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/chat/ask/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + hrUserToken())
                .content("{\"query\":\"请假流程\",\"domain\":\"HR\"}"));

        ArgumentCaptor<String> domainCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatQueryService).askStream(eq("请假流程"), any(), eq(7L), domainCaptor.capture(), any());
        assertThat(domainCaptor.getValue()).isEqualTo("HR");
    }

    @Test
    @DisplayName("admin 不受域限制（设计 §3.2.9：admin 全权限）")
    void ask_shouldAllowAnyDomain_whenAdmin() throws Exception {
        Mockito.when(chatQueryService.ask(any(), any(), any(), any()))
                .thenReturn(Map.of("answer", "ok"));

        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken())
                        .content("{\"query\":\"财务数据\",\"domain\":\"FINANCE\"}"))
                .andExpect(status().isOk());

        verify(chatQueryService).ask(eq("财务数据"), any(), any(), eq("FINANCE"));
    }

    /** HR 部门普通用户（allowedDomains=HR，department=HR）。 */
    private String hrUserToken() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("chenming");
        user.setRole("user");
        user.setStatus(1);
        user.setDepartment("HR");
        user.setClearanceLevel(2);
        user.setAllowedDomains("HR");
        user.setTitle("manager");
        return jwtUtil.generateAccessToken(user);
    }

    /** 管理员。 */
    private String adminToken() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("admin");
        user.setStatus(1);
        user.setDepartment("RD");
        user.setClearanceLevel(3);
        user.setAllowedDomains("RD");
        user.setTitle("director");
        return jwtUtil.generateAccessToken(user);
    }

    /**
     * 最小切片上下文：真实安全过滤器链 + 被测控制器（依赖以 mock 提供）。
     * <p>
     * 注意 {@code AbacEvaluator} 的 Bean 名必须为 {@code abac}——{@code @PreAuthorize}
     * 的 SpEL 以 {@code @abac} 引用它（与 {@code @Component("abac")} 一致）。
     * </p>
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
        ConversationService conversationService() {
            return Mockito.mock(ConversationService.class);
        }

        @Bean
        MessageMapper messageMapper() {
            return Mockito.mock(MessageMapper.class);
        }

        @Bean
        ChatController chatController(ChatQueryService chatQueryService,
                                      ConversationService conversationService,
                                      MessageMapper messageMapper) {
            return new ChatController(chatQueryService, conversationService, messageMapper);
        }
    }
}
