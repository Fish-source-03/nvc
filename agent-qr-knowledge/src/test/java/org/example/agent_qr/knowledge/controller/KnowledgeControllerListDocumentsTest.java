package org.example.agent_qr.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.service.DocumentCommandService;
import org.example.agent_qr.knowledge.service.DocumentQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档列表接口的查询参数绑定测试（批次 09 · 任务 9.3，问题 33 断裂 1）。
 * <p>
 * <b>拦截的缺陷</b>：控制器此前只声明 {@code page}/{@code size}，Spring 对多余的查询参数
 * <b>静默忽略</b>——请求 {@code GET /api/knowledge/documents?domain=HR} 返回全库数据且 HTTP 200，
 * 前端无法察觉筛选未生效。本测试直接在 HTTP 层断言"参数被接收并透传"，
 * 这是"静默忽略"类缺陷唯一的拦截方式（服务层单测覆盖不到参数绑定）。
 * </p>
 * <p>
 * 非法值策略：{@code sensitivityLevel} 非整数 → Spring 绑定层类型转换失败 → <b>明确报错</b>
 * （不会被静默忽略）；未知的 {@code domain} 取值不报错，由查询返回空列表（域是开放取值）。
 * </p>
 * <p>
 * ⚠️ 关于"400 还是 500"：本测试用 standalone MockMvc（不加载 {@code @RestControllerAdvice}），
 * 因此看到的是 Spring 的默认行为 HTTP 400。真实应用中
 * {@code GlobalExceptionHandler} 只声明了 BusinessException/校验/缺参等处理器、
 * <b>没有</b> {@code MethodArgumentTypeMismatchException} 的处理器，异常落入其兜底分支 →
 * HTTP 200 + {@code Result.error(500, "服务器内部错误，请稍后重试")}——
 * 即"报错但错误码语义不准（客户端错误被报成 500）"。
 * 该处理器不在本批次可改文件范围内，已作为发现项上报，此处不擅自修改。
 * </p>
 *
 * @author agent-qr
 */
class KnowledgeControllerListDocumentsTest {

    private DocumentQueryService documentQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentQueryService = mock(DocumentQueryService.class);
        DocumentCommandService documentCommandService = mock(DocumentCommandService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new KnowledgeController(documentCommandService, documentQueryService))
                .build();
        when(documentQueryService.listDocuments(anyInt(), anyInt(), any(), any()))
                .thenReturn(emptyPage());
    }

    @Test
    @DisplayName("★ domain + sensitivityLevel 被控制器接收并透传到服务层（修复前被 Spring 静默忽略）")
    void listDocuments_shouldBindFilterParams() throws Exception {
        mockMvc.perform(get("/api/knowledge/documents")
                        .param("page", "2")
                        .param("size", "20")
                        .param("domain", "HR")
                        .param("sensitivityLevel", "1"))
                .andExpect(status().isOk());

        verify(documentQueryService).listDocuments(2, 20, "HR", 1);
    }

    @Test
    @DisplayName("仅传 domain 时另一参数为 null（不得误填默认密级）")
    void listDocuments_shouldBindDomainOnly() throws Exception {
        mockMvc.perform(get("/api/knowledge/documents").param("domain", "FINANCE"))
                .andExpect(status().isOk());

        verify(documentQueryService).listDocuments(1, 10, "FINANCE", null);
    }

    @Test
    @DisplayName("仅传 sensitivityLevel 时 domain 为 null")
    void listDocuments_shouldBindSensitivityLevelOnly() throws Exception {
        mockMvc.perform(get("/api/knowledge/documents").param("sensitivityLevel", "3"))
                .andExpect(status().isOk());

        verify(documentQueryService).listDocuments(1, 10, null, 3);
    }

    @Test
    @DisplayName("★ 两个参数都不传 → 行为与修复前一致（默认页参数，回归）")
    void listDocuments_shouldUseDefaults_whenNoParams() throws Exception {
        mockMvc.perform(get("/api/knowledge/documents"))
                .andExpect(status().isOk());

        verify(documentQueryService).listDocuments(1, 10, null, null);
    }

    @Test
    @DisplayName("★ sensitivityLevel 为非整数 → 绑定层拒绝（明确报错，不静默忽略）")
    void listDocuments_shouldRejectNonNumericSensitivityLevel() throws Exception {
        mockMvc.perform(get("/api/knowledge/documents").param("sensitivityLevel", "abc"))
                .andExpect(status().isBadRequest());

        verify(documentQueryService, never()).listDocuments(anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("未知 domain 取值不报错（域是开放取值，由查询返回空列表）")
    void listDocuments_shouldAcceptUnknownDomain() throws Exception {
        mockMvc.perform(get("/api/knowledge/documents").param("domain", "NOT_EXIST"))
                .andExpect(status().isOk());

        verify(documentQueryService).listDocuments(1, 10, "NOT_EXIST", null);
    }

    @Test
    @DisplayName("空字符串参数按'未提供'处理（不产生 domain = '' 查询）")
    void listDocuments_shouldTreatEmptyDomainAsAbsent() throws Exception {
        mockMvc.perform(get("/api/knowledge/documents").param("domain", ""))
                .andExpect(status().isOk());

        verify(documentQueryService).listDocuments(1, 10, "", null);
    }

    private static IPage<Document> emptyPage() {
        return new Page<>(1, 10);
    }
}
