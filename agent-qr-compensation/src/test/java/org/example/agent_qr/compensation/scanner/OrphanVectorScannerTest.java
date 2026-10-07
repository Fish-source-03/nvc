package org.example.agent_qr.compensation.scanner;

import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrphanVectorScanner} 单元测试（批次 08 · 任务 8.3，问题 29）。
 * <p>
 * 拦截的核心缺陷：扫描方向反了 + 输入查询排除了软删切片，
 * 使兜底<b>恰好失效在它唯一的应用场景</b>——"MySQL 删成功 + Chroma 删失败"。
 * 另外覆盖计数虚高（无条件 {@code cleaned++}）与调度周期不符设计要求两点。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrphanVectorScannerTest {

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private ChromaRetriever chromaRetriever;

    @InjectMocks
    private OrphanVectorScanner scanner;

    // ==================== 缺陷用例：MySQL 已软删 + Chroma 残留 ====================

    @Test
    @DisplayName("★ 切片已软删（不在存活集合）但 ChromaDB 仍有向量时：必须发现并清理该死向量")
    void scan_shouldDiscoverAndClean_whenChunkSoftDeletedButVectorRemains() {
        // ChromaDB 侧：有一条向量，元数据 chunk_id=11771
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(vector("uuid-11771", 11771L, 9L, null)));
        // MySQL 侧：该切片已被软删（存活集合为空）
        when(chunkMapper.selectLiveChunkIds(anyList())).thenReturn(List.of());
        when(chromaRetriever.deleteByIds(anyList())).thenReturn(1);

        scanner.scanAndCleanOrphanVectors();

        // 修复前：扫描输入是 selectAllReadyChunks()（带 deleted = 0），
        // 已软删切片进不了输入集合 → 该残留永远发现不了
        verify(chromaRetriever).deleteByIds(List.of("uuid-11771"));
        assertThat(scanner.lastDiscoveredOrphans()).isEqualTo(1);
        assertThat(scanner.lastCleanedOrphans()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 扫描不再使用 selectAllReadyChunks / selectByDocumentId（8.3 不得回退 8.1 的修复）")
    void scan_shouldNotReadThroughSoftDeleteFilteredQueries() {
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(vector("uuid-1", 100L, 9L, null)));
        when(chunkMapper.selectLiveChunkIds(anyList())).thenReturn(List.of(100L));

        scanner.scanAndCleanOrphanVectors();

        verify(chunkMapper, never()).selectAllReadyChunks();
        verify(chunkMapper, never()).selectByDocumentId(anyLong());
        verify(chunkMapper).selectLiveChunkIds(List.of(100L));
    }

    // ==================== 无误删 / 幂等 ====================

    @Test
    @DisplayName("★ 无残留时不产生误删：所有向量对应切片均存活")
    void scan_shouldNotDelete_whenNoOrphanExists() {
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(
                vector("uuid-1", 100L, 9L, null),
                vector("uuid-2", 101L, 9L, null)));
        when(chunkMapper.selectLiveChunkIds(anyList())).thenReturn(List.of(100L, 101L));

        scanner.scanAndCleanOrphanVectors();

        verify(chromaRetriever, never()).deleteByIds(anyList());
        assertThat(scanner.lastDiscoveredOrphans()).isZero();
        assertThat(scanner.lastCleanedOrphans()).isZero();
    }

    @Test
    @DisplayName("★ 已软删但 Chroma 已清理的记录不被重复处理（下一轮枚举不到即无动作）")
    void scan_shouldNotReprocess_whenVectorAlreadyCleaned() {
        // 第一轮：发现并清理
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(vector("uuid-11771", 11771L, 9L, null)));
        when(chunkMapper.selectLiveChunkIds(anyList())).thenReturn(List.of());
        when(chromaRetriever.deleteByIds(anyList())).thenReturn(1);
        scanner.scanAndCleanOrphanVectors();
        verify(chromaRetriever).deleteByIds(List.of("uuid-11771"));

        // 第二轮：该向量已不在 ChromaDB（枚举结果为空）→ 不产生任何删除
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of());
        scanner.scanAndCleanOrphanVectors();

        assertThat(scanner.lastDiscoveredOrphans()).isZero();
        assertThat(scanner.lastCleanedOrphans()).isZero();
    }

    @Test
    @DisplayName("★ ChromaDB 枚举为空（不可达）时不得误删：删除集合只来自枚举结果")
    void scan_shouldDeleteNothing_whenEnumerationIsEmpty() {
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of());

        scanner.scanAndCleanOrphanVectors();

        verify(chromaRetriever, never()).deleteByIds(anyList());
    }

    // ==================== 计数据实（任务 8.3.3） ====================

    @Test
    @DisplayName("★ 删除失败时 cleaned 计数不增加（修复前无条件 cleaned++ 导致计数虚高）")
    void scan_shouldNotCountCleaned_whenDeleteFails() {
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(
                vector("uuid-a", 200L, 9L, null),
                vector("uuid-b", 201L, 9L, null)));
        when(chunkMapper.selectLiveChunkIds(anyList())).thenReturn(List.of());
        when(chromaRetriever.deleteByIds(anyList())).thenThrow(new RuntimeException("ChromaDB 不可达"));

        scanner.scanAndCleanOrphanVectors();

        assertThat(scanner.lastDiscoveredOrphans())
                .as("发现数应如实反映（供运维判断残留规模）")
                .isEqualTo(2);
        assertThat(scanner.lastCleanedOrphans())
                .as("修复前：无条件 cleaned++ → 日志显示清理 2 条，实际一条未删")
                .isZero();
    }

    @Test
    @DisplayName("★ 清理计数使用删除方法的返回值（部分失败时按实际条数计）")
    void scan_shouldUseActualDeletedCount() {
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(
                vector("uuid-a", 200L, 9L, null),
                vector("uuid-b", 201L, 9L, null)));
        when(chunkMapper.selectLiveChunkIds(anyList())).thenReturn(List.of());
        // ChromaRetriever 如实返回实际删除条数
        when(chromaRetriever.deleteByIds(anyList())).thenReturn(2);

        scanner.scanAndCleanOrphanVectors();

        assertThat(scanner.lastCleanedOrphans()).isEqualTo(2);
    }

    // ==================== 其他归属形态 ====================

    @Test
    @DisplayName("★ 无 chunk_id 元数据时按 document_id 判定：文档已删即为孤儿")
    void scan_shouldCleanByDocumentId_whenChunkIdMetadataMissing() {
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(vector("uuid-doc", null, 77L, null)));
        when(documentMapper.selectById(77L)).thenReturn(null);
        when(chromaRetriever.deleteByIds(anyList())).thenReturn(1);

        scanner.scanAndCleanOrphanVectors();

        verify(chromaRetriever).deleteByIds(List.of("uuid-doc"));
    }

    @Test
    @DisplayName("★ 无 chunk_id 元数据时按 datasource_id 判定：非活跃数据源即为孤儿")
    void scan_shouldCleanByDatasourceId_whenDatasourceInactive() {
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(vector("uuid-ds", null, null, 55L)));
        when(dataSourceMapper.selectAllActive()).thenReturn(List.of());
        when(chromaRetriever.deleteByIds(anyList())).thenReturn(1);

        scanner.scanAndCleanOrphanVectors();

        verify(chromaRetriever).deleteByIds(List.of("uuid-ds"));
    }

    @Test
    @DisplayName("★ 三项元数据全无的向量无法判定归属 → 保留不删（宁可漏清理也不误删）")
    void scan_shouldKeepVectors_whenMetadataCannotIdentifyOwner() {
        when(chromaRetriever.enumerateAllVectors()).thenReturn(List.of(vector("uuid-unknown", null, null, null)));

        scanner.scanAndCleanOrphanVectors();

        verify(chromaRetriever, never()).deleteByIds(anyList());
        assertThat(scanner.lastDiscoveredOrphans()).isZero();
    }

    // ==================== 调度周期（任务 8.3.4） ====================

    @Test
    @DisplayName("★ 调度周期对齐设计 §10.1 决策 6：30 分钟（1800000ms），不是 5 分钟")
    void scheduledInterval_shouldBeThirtyMinutes() throws Exception {
        Method method = OrphanVectorScanner.class.getMethod("scanAndCleanOrphanVectors");
        org.springframework.scheduling.annotation.Scheduled scheduled =
                method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(1_800_000L);
        assertThat(OrphanVectorScanner.SCAN_INTERVAL_MS).isEqualTo(1_800_000L);
    }

    // ==================== 辅助 ====================

    private static ChromaRetriever.ChromaVectorRecord vector(String vectorId, Long chunkId,
                                                            Long documentId, Long datasourceId) {
        return new ChromaRetriever.ChromaVectorRecord(vectorId, chunkId, documentId, datasourceId, "标题");
    }
}
