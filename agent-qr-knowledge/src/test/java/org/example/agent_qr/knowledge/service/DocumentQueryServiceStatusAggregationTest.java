package org.example.agent_qr.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 文档状态聚合推导测试（批次 07 · 任务 7.0.4）。
 * <p>
 * 拦截的缺陷：文档状态与切片状态脱节——切片还停在 INDEXED（向量未写），
 * 文档却显示 READY。改造后文档状态一律由切片聚合推导。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentQueryServiceStatusAggregationTest {

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
    }

    // ==================== 推导规则（纯函数） ====================

    @Test
    @DisplayName("★ 全部切片为 READY 时，文档聚合为 READY")
    void deriveStatus_allReady_shouldBeReady() {
        assertThat(DocumentQueryService.deriveStatus(Map.of(Chunk.STATUS_READY, 4)))
                .isEqualTo(DocumentStatus.READY);
    }

    @Test
    @DisplayName("★ 存在 INDEXED 时，文档聚合为 INDEXED（不得为 READY）")
    void deriveStatus_someIndexed_shouldBeIndexed() {
        Map<String, Integer> counts = Map.of(Chunk.STATUS_READY, 2, Chunk.STATUS_INDEXED, 3);

        assertThat(DocumentQueryService.deriveStatus(counts)).isEqualTo(DocumentStatus.INDEXED);
    }

    @Test
    @DisplayName("★ 存在向量化中的切片时聚合为 EMBEDDING（优先于 INDEXED，前端需继续轮询）")
    void deriveStatus_inFlightEmbedding_shouldBeEmbedding() {
        Map<String, Integer> counts = Map.of(Chunk.STATUS_INDEXED, 1, DocumentStatus.EMBEDDING.name(), 1);

        assertThat(DocumentQueryService.deriveStatus(counts)).isEqualTo(DocumentStatus.EMBEDDING);
    }

    @Test
    @DisplayName("PENDING 切片同样视为向量化中")
    void deriveStatus_pending_shouldBeEmbedding() {
        assertThat(DocumentQueryService.deriveStatus(Map.of(Chunk.STATUS_PENDING, 3)))
                .isEqualTo(DocumentStatus.EMBEDDING);
    }

    @Test
    @DisplayName("前置/异常/终态不被聚合覆盖（失败文档不能看起来像在处理中）")
    void needsAggregation_shouldSkipTerminalAndPreChunkStates() {
        assertThat(DocumentQueryService.needsAggregation(DocumentStatus.UPLOADED)).isFalse();
        assertThat(DocumentQueryService.needsAggregation(DocumentStatus.PARSING)).isFalse();
        assertThat(DocumentQueryService.needsAggregation(DocumentStatus.FAILED)).isFalse();
        assertThat(DocumentQueryService.needsAggregation(DocumentStatus.DELETING)).isFalse();
        assertThat(DocumentQueryService.needsAggregation(null)).isFalse();

        assertThat(DocumentQueryService.needsAggregation(DocumentStatus.CHUNKING)).isTrue();
        assertThat(DocumentQueryService.needsAggregation(DocumentStatus.INDEXED)).isTrue();
        assertThat(DocumentQueryService.needsAggregation(DocumentStatus.EMBEDDING)).isTrue();
        assertThat(DocumentQueryService.needsAggregation(DocumentStatus.READY)).isTrue();
    }

    // ==================== 接入查询路径 ====================

    @Test
    @DisplayName("★ 列表查询：一次分组查询覆盖整页，INDEXED 的文档被改写为 INDEXED 而非库内 READY")
    void listDocuments_shouldOverrideStoredStatusByAggregation() {
        Document indexedDoc = document(1L, DocumentStatus.READY);
        Document readyDoc = document(2L, DocumentStatus.READY);
        IPage<Document> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(indexedDoc, readyDoc)));
        when(documentMapper.selectPage(any(), any())).thenReturn(page);

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(1L, Chunk.STATUS_INDEXED, 3));
        rows.add(row(2L, Chunk.STATUS_READY, 3));
        when(chunkMapper.countByDocumentIdsGroupByStatus(anyList())).thenReturn(rows);

        service.listDocuments(1, 10);

        assertThat(indexedDoc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(readyDoc.getStatus()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    @DisplayName("文档无切片时保留库内状态（不把刚上传的文档误判为 INDEXED）")
    void getDocument_shouldKeepStoredStatus_whenNoChunks() {
        Document doc = document(7L, DocumentStatus.CHUNKING);
        when(documentMapper.selectById(7L)).thenReturn(doc);
        when(chunkMapper.countByDocumentIdsGroupByStatus(anyList())).thenReturn(List.of());

        service.getDocument(7L);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.CHUNKING);
    }

    @Test
    @DisplayName("FAILED 文档不参与聚合（错误状态优先）")
    void getDocument_shouldKeepFailedStatus() {
        Document doc = document(8L, DocumentStatus.FAILED);
        when(documentMapper.selectById(8L)).thenReturn(doc);

        service.getDocument(8L);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    // ==================== 辅助 ====================

    private static Document document(Long id, DocumentStatus status) {
        Document doc = new Document();
        doc.setId(id);
        doc.setTitle("doc-" + id);
        doc.setStatus(status);
        return doc;
    }

    private static Map<String, Object> row(Long documentId, String status, int cnt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("documentId", documentId);
        row.put("status", status);
        row.put("cnt", cnt);
        return row;
    }
}
