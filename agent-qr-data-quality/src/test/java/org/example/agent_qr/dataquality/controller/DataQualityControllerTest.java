package org.example.agent_qr.dataquality.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.common.Result;
import org.example.agent_qr.dataquality.entity.QualityReport;
import org.example.agent_qr.dataquality.entity.QualityRuleConfig;
import org.example.agent_qr.dataquality.service.DataQualityService;
import org.example.agent_qr.dataquality.service.QualityRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 质检规则 CRUD 端点的 HTTP 层测试（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 用 standalone MockMvc 验证：路径/方法/参数绑定/统一 {@code Result} 封装，
 * 以及"非法规则配置被拒绝"在 HTTP 层的表现（业务码 400 + 明确文案）。
 * 业务异常处理器沿用 {@code GlobalExceptionHandler} 的口径（Result.error(400, msg)）。
 * </p>
 * <p>
 * 权限校验以注解断言的方式验证（方法级 {@code @PreAuthorize} 的运行时行为
 * 已由 agent-qr-web 的 ABAC 测试覆盖；data-quality 模块无 Spring 上下文测试）。
 * </p>
 *
 * @author agent-qr
 */
class DataQualityControllerTest {

    private DataQualityService dataQualityService;
    private QualityRuleService qualityRuleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dataQualityService = mock(DataQualityService.class);
        qualityRuleService = mock(QualityRuleService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DataQualityController(dataQualityService, qualityRuleService))
                .setControllerAdvice(new BusinessExceptionAdvice())
                .build();
    }

    @Test
    @DisplayName("查询规则列表：返回统一 Result 封装")
    void listRules_shouldReturnResultEnvelope() throws Exception {
        QualityRuleConfig rule = new QualityRuleConfig();
        rule.setId(1L);
        rule.setRuleName("字段非空检查");
        rule.setRuleType("completeness");
        rule.setEnabled(true);
        when(qualityRuleService.listRules()).thenReturn(List.of(rule));

        mockMvc.perform(get("/api/dataquality/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].ruleName").value("字段非空检查"))
                .andExpect(jsonPath("$.data[0].ruleType").value("completeness"));
    }

    @Test
    @DisplayName("新建规则：JSON 报文绑定到实体并透传 Service")
    void createRule_shouldBindBodyAndDelegate() throws Exception {
        QualityRuleConfig created = new QualityRuleConfig();
        created.setId(11L);
        created.setRuleName("邮箱非空");
        created.setRuleType("completeness");
        created.setEnabled(true);
        when(qualityRuleService.createRule(any(QualityRuleConfig.class))).thenReturn(created);

        mockMvc.perform(post("/api/dataquality/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleName":"邮箱非空","ruleType":"completeness",
                                 "targetFields":"email","params":{},"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(11));

        verify(qualityRuleService).createRule(any(QualityRuleConfig.class));
    }

    @Test
    @DisplayName("更新规则 / 启停规则 / 删除规则：路径与参数正确透传")
    void updateToggleDelete_shouldDelegateWithId() throws Exception {
        QualityRuleConfig updated = new QualityRuleConfig();
        updated.setId(5L);
        when(qualityRuleService.updateRule(eq(5L), any(QualityRuleConfig.class))).thenReturn(updated);
        when(qualityRuleService.setEnabled(eq(5L), eq(false))).thenReturn(updated);

        mockMvc.perform(put("/api/dataquality/rules/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleName\":\"x\",\"ruleType\":\"encoding\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put("/api/dataquality/rules/5/enabled").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/api/dataquality/rules/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(qualityRuleService).updateRule(eq(5L), any(QualityRuleConfig.class));
        verify(qualityRuleService).setEnabled(5L, false);
        verify(qualityRuleService).deleteRule(5L);
    }

    @Test
    @DisplayName("★ 非法规则配置被拒绝：业务码 400 + 明确文案，且不落库")
    void createRule_shouldRejectInvalidConfig() throws Exception {
        when(qualityRuleService.createRule(any(QualityRuleConfig.class)))
                .thenThrow(new BusinessException("不支持的规则类型: groovy-script"));

        mockMvc.perform(post("/api/dataquality/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleName\":\"x\",\"ruleType\":\"groovy-script\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不支持的规则类型: groovy-script"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(qualityRuleService).createRule(any(QualityRuleConfig.class));
    }

    @Test
    @DisplayName("★ 规则管理端点带权限校验（仅 admin），报告端点维持不变")
    void ruleEndpoints_shouldRequireAdminPermission() throws Exception {
        for (String methodName : List.of("listRules", "getRule", "createRule",
                "updateRule", "setRuleEnabled", "deleteRule")) {
            Method method = findMethod(methodName);
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("规则端点 %s 必须有 @PreAuthorize", methodName)
                    .isNotNull();
            assertThat(annotation.value()).contains("canManageDatasource");
        }

        assertThat(findMethod("listReports").getAnnotation(PreAuthorize.class))
                .as("既有报告列表端点权限行为不得改变")
                .isNull();
        assertThat(findMethod("getReport").getAnnotation(PreAuthorize.class))
                .as("既有报告详情端点权限行为不得改变")
                .isNull();
    }

    @Test
    @DisplayName("报告端点仍按原路径工作（回归：规则改造未影响报告查询）")
    void reportEndpoints_shouldKeepWorking() throws Exception {
        when(dataQualityService.listReports(1, 10, null))
                .thenReturn(new Page<QualityReport>(1, 10));

        mockMvc.perform(get("/api/dataquality/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());

        verify(dataQualityService).listReports(1, 10, null);
        verify(qualityRuleService, never()).listRules();
    }

    private static Method findMethod(String name) {
        for (Method method : DataQualityController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("未找到方法: " + name);
    }

    /**
     * 与 {@code GlobalExceptionHandler#handleBusinessException} 同口径的测试用处理器。
     */
    @RestControllerAdvice
    static class BusinessExceptionAdvice {

        @ExceptionHandler(BusinessException.class)
        public Result<Void> handleBusinessException(BusinessException e) {
            return Result.error(e.getCode(), e.getMessage());
        }
    }
}
