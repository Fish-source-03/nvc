package org.example.agent_qr.web.config;

import org.example.agent_qr.knowledge.controller.KnowledgeController;
import org.example.agent_qr.knowledge.service.DocumentCommandService;
import org.example.agent_qr.knowledge.service.DocumentQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GlobalExceptionHandler} 的请求绑定类异常处理器测试（批次 11 · R35）。
 * <p>
 * <b>拦截的缺陷</b>：{@code ?sensitivityLevel=abc} / {@code ?page=abc} 这类"客户端把参数写错"
 * 的请求，此前没有任何处理器，落入兜底 {@code Exception} 分支 →
 * <b>{@code code=500}「服务器内部错误」</b>——客户端参数错误被报成服务器故障，
 * 前端提示与排障都被误导。
 * </p>
 * <p>
 * 本测试通过"最小探针控制器 + 真实 {@link GlobalExceptionHandler}"在 HTTP 层断言：
 * 绑定失败必须返回业务码 <b>400</b>（HTTP 200 + 统一 Result，与
 * {@code MethodArgumentNotValidException}/{@code MissingServletRequestPartException}
 * 处理器保持同一约定——前端 axios 拦截器按业务码分支提示）。
 * 修复前此断言会失败（实际得到 500）。
 * </p>
 *
 * @author agent-qr
 */
class GlobalExceptionHandlerBindingTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("★ 查询参数类型不匹配 → code=400（修复前落入兜底分支报 code=500）")
    void typeMismatch_shouldReturn400BusinessCode() throws Exception {
        mockMvc.perform(get("/probe/typed").param("sensitivityLevel", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("sensitivityLevel")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Integer")));
    }

    @Test
    @DisplayName("★ 缺少必传参数（同族绑定异常）→ code=400，不再报成服务器故障")
    void missingParameter_shouldReturn400BusinessCode() throws Exception {
        mockMvc.perform(get("/probe/required"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("q")));
    }

    @Test
    @DisplayName("回归：合法参数正常通过，不受新处理器影响")
    void validRequest_shouldStillSucceed() throws Exception {
        mockMvc.perform(get("/probe/typed").param("sensitivityLevel", "2"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("ok:2"));
    }

    @Test
    @DisplayName("★ R35 原始症状：文档列表 ?sensitivityLevel=abc → 业务码 400（此前 500）")
    void knowledgeListDocuments_withNonNumericSensitivityLevel_shouldReturn400() throws Exception {
        DocumentQueryService documentQueryService =
                org.mockito.Mockito.mock(DocumentQueryService.class);
        DocumentCommandService documentCommandService =
                org.mockito.Mockito.mock(DocumentCommandService.class);
        MockMvc knowledgeMockMvc = MockMvcBuilders
                .standaloneSetup(new KnowledgeController(documentCommandService, documentQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        knowledgeMockMvc.perform(get("/api/knowledge/documents").param("sensitivityLevel", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("sensitivityLevel")));

        org.mockito.Mockito.verify(documentQueryService, org.mockito.Mockito.never())
                .listDocuments(org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    /** 最小探针控制器：仅用于触发参数绑定失败，不依赖任何业务 Bean。 */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/typed")
        public String typed(@RequestParam Integer sensitivityLevel) {
            return "ok:" + sensitivityLevel;
        }

        @GetMapping("/probe/required")
        public String required(@RequestParam String q) {
            return "ok:" + q;
        }
    }
}
