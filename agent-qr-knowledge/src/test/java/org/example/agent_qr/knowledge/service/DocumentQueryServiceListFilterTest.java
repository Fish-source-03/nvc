package org.example.agent_qr.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档列表筛选参数透传测试（批次 09 · 任务 9.3，问题 33 断裂 1）。
 * <p>
 * <b>拦截的缺陷</b>：前端一直在传 {@code domain} / {@code sensitivityLevel}，
 * 后端控制器未声明这两个参数 → Spring 静默忽略 → 界面上的"业务域/密级"筛选点了没有反应，
 * 也不报错（静默失效是最难被发现的一类缺陷）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentQueryServiceListFilterTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private AbacEvaluator abacEvaluator;

    private DocumentQueryService service;

    @BeforeEach
    void setUp() {
        service = new DocumentQueryService(documentMapper, chunkMapper, abacEvaluator);
        when(chunkMapper.countByDocumentIdsGroupByStatus(anyList())).thenReturn(List.of());
    }

    @Test
    @DisplayName("★ 传入 domain 时被透传到查询条件（修复前参数被静默丢弃）")
    void listDocuments_shouldPassDomainToMapper() {
        when(documentMapper.selectPageByFilter(any(), eq("HR"), isNull()))
                .thenReturn(pageOf(document(1L, "HR")));

        IPage<Document> result = service.listDocuments(1, 10, "HR", null);

        assertThat(result.getRecords()).extracting(Document::getDomain).containsExactly("HR");
        ArgumentCaptor<Page<Document>> pageCaptor = pageCaptor();
        verify(documentMapper).selectPageByFilter(pageCaptor.capture(), eq("HR"), isNull());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
        verify(documentMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("★ 传入 sensitivityLevel 时被透传到查询条件")
    void listDocuments_shouldPassSensitivityLevelToMapper() {
        when(documentMapper.selectPageByFilter(any(), isNull(), eq(2)))
                .thenReturn(pageOf(document(2L, "FINANCE")));

        IPage<Document> result = service.listDocuments(2, 20, null, 2);

        assertThat(result.getRecords()).hasSize(1);
        verify(documentMapper).selectPageByFilter(any(), isNull(), eq(2));
    }

    @Test
    @DisplayName("两个筛选参数同时传入时一并生效")
    void listDocuments_shouldPassBothFilters() {
        when(documentMapper.selectPageByFilter(any(), eq("RD"), eq(3)))
                .thenReturn(pageOf(document(3L, "RD")));

        service.listDocuments(1, 10, "RD", 3);

        verify(documentMapper).selectPageByFilter(any(), eq("RD"), eq(3));
        verify(documentMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("★ 两个参数都不传 → 走既有查询路径，行为与修复前一致（回归）")
    void listDocuments_shouldKeepLegacyQuery_whenNoFilterProvided() {
        when(documentMapper.selectPage(any(), any())).thenReturn(pageOf(document(9L, "HR")));

        IPage<Document> result = service.listDocuments(1, 10);

        assertThat(result.getRecords()).hasSize(1);
        verify(documentMapper).selectPage(any(), any());
        verify(documentMapper, never()).selectPageByFilter(any(), any(), any());
    }

    @Test
    @DisplayName("domain 为空白串时视为不筛（不产生 domain = '' 的查询）")
    void listDocuments_shouldTreatBlankDomainAsAbsent() {
        when(documentMapper.selectPage(any(), any())).thenReturn(pageOf(document(9L, "HR")));

        service.listDocuments(1, 10, "   ", null);

        verify(documentMapper).selectPage(any(), any());
        verify(documentMapper, never()).selectPageByFilter(any(), any(), any());
    }

    @Test
    @DisplayName("未知 domain 无匹配 → 返回空列表（域是开放取值，不是错误）")
    void listDocuments_shouldReturnEmpty_forUnknownDomain() {
        when(documentMapper.selectPageByFilter(any(), eq("NOT_EXIST"), isNull()))
                .thenReturn(pageOf());

        IPage<Document> result = service.listDocuments(1, 10, "NOT_EXIST", null);

        assertThat(result.getRecords()).isEmpty();
    }

    // ==================== 辅助 ====================

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Page<Document>> pageCaptor() {
        return (ArgumentCaptor<Page<Document>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Page.class);
    }

    private static IPage<Document> pageOf(Document... documents) {
        Page<Document> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(documents)));
        return page;
    }

    private static Document document(Long id, String domain) {
        Document doc = new Document();
        doc.setId(id);
        doc.setTitle("doc-" + id);
        doc.setDomain(domain);
        doc.setSensitivityLevel(1);
        doc.setStatus(DocumentStatus.READY);
        doc.setDeleted(0);
        return doc;
    }
}
