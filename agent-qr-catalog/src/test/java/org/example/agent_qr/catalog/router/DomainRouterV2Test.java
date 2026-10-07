package org.example.agent_qr.catalog.router;

import org.example.agent_qr.catalog.entity.CatalogTree;
import org.example.agent_qr.catalog.entity.DomainNode;
import org.example.agent_qr.catalog.entity.EntityNode;
import org.example.agent_qr.catalog.entity.SourceNode;
import org.example.agent_qr.catalog.service.KnowledgeCatalogService;
import org.example.agent_qr.common.event.DataSyncCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DomainRouterV2}（Catalog 版域描述缓存）测试（批次 11 · 任务 11.2.2）。
 * <p>
 * 该类为 rag 侧语义路由提供"域 → 自然语言描述"的缓存，刷新策略为
 * 定时（5 分钟）+ 数据同步事件驱动的懒加载。用例锁定：
 * </p>
 * <ul>
 *   <li>目录树为空/异常时<b>不抛异常、不污染缓存</b>（rag 侧路由降级不应被拖垮）；</li>
 *   <li>描述内容包含域名、数据源名与实体名，且数量有上限（防止描述无限膨胀）；</li>
 *   <li>同步完成事件必须让缓存过期，下次读取重新拉取目录树（否则新接入的数据源
 *       在 5 分钟内对语义路由不可见）。</li>
 * </ul>
 *
 * @author agent-qr
 */
class DomainRouterV2Test {

    private KnowledgeCatalogService catalogService;
    private DomainRouterV2 router;

    @BeforeEach
    void setUp() {
        catalogService = mock(KnowledgeCatalogService.class);
        router = new DomainRouterV2();
        ReflectionTestUtils.setField(router, "catalogService", catalogService);
    }

    @Test
    @DisplayName("目录树为空时不抛异常且缓存保持为空（懒加载，不写入空描述）")
    void refresh_shouldTolerateEmptyTree() {
        when(catalogService.getCatalogTree()).thenReturn(CatalogTree.empty());

        assertThatCode(() -> router.refresh()).doesNotThrowAnyException();
        assertThat(router.getDomainDescriptions()).isEmpty();
    }

    @Test
    @DisplayName("目录查询异常不向外传播（rag 路由降级不应被拖垮）")
    void refresh_shouldSwallowCatalogFailure() {
        when(catalogService.getCatalogTree()).thenThrow(new IllegalStateException("目录不可用"));

        assertThatCode(() -> router.refresh()).doesNotThrowAnyException();
        assertThat(router.getDomainDescriptions()).isEmpty();
    }

    @Test
    @DisplayName("★ 域描述包含域名/数据源/实体，且返回不可修改视图")
    void refresh_shouldBuildDescriptionsFromTree() {
        when(catalogService.getCatalogTree()).thenReturn(tree(1));

        Map<String, String> descriptions = router.getDomainDescriptions();

        assertThat(descriptions).containsOnlyKeys("DOMAIN_0");
        assertThat(descriptions.get("DOMAIN_0"))
                .contains("DOMAIN_0")
                .contains("SRC_0_0")
                .contains("ENT_0_0_0");
        assertThatThrownBy(() -> descriptions.put("X", "Y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("描述构建有上限：每域最多 20 个数据源、50 个实体（防止描述无限膨胀）")
    void buildDescriptions_shouldCapSourcesAndEntities() {
        EntityNode entity = new EntityNode("ENT_SHARED", EntityNode.TYPE_TABLE, 1, null);
        List<SourceNode> sources = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            sources.add(new SourceNode((long) i, "SRC_" + i, "JDBC", null, 1, List.of(entity)));
        }
        when(catalogService.getCatalogTree())
                .thenReturn(new CatalogTree(List.of(new DomainNode("BIG", 25, 25, sources))));

        String description = router.getDomainDescriptions().get("BIG");

        assertThat(description).contains("SRC_19");   // 第 20 个数据源在内
        assertThat(description).doesNotContain("SRC_20"); // 第 21 个起被截断
        // 实体名来自前 20 个数据源 × 每源 1 个（上限 50 未触及，此处仅验证去重后仍存在）
        assertThat(description).contains("ENT_SHARED");
    }

    @Test
    @DisplayName("★ 数据同步完成事件使缓存过期，下次读取重新拉取目录")
    void syncEvent_shouldInvalidateCache() {
        when(catalogService.getCatalogTree()).thenReturn(tree(1));

        router.getDomainDescriptions();
        verify(catalogService, times(1)).getCatalogTree();

        router.onCatalogChanged(new DataSyncCompletedEvent(9L, "src-9", List.of(), "batch-1"));

        router.getDomainDescriptions();
        verify(catalogService, times(2)).getCatalogTree();
    }

    /**
     * 构造含 {@code domainCount} 个域的目录树，每域 1 数据源 / 1 实体。
     */
    private static CatalogTree tree(int domainCount) {
        List<DomainNode> domains = IntStream.range(0, domainCount)
                .mapToObj(d -> {
                    EntityNode entity = new EntityNode("ENT_" + d + "_0_0", EntityNode.TYPE_TABLE, 1, null);
                    SourceNode source = new SourceNode((long) d, "SRC_" + d + "_0", "JDBC", null, 1, List.of(entity));
                    return new DomainNode("DOMAIN_" + d, 1, 1, List.of(source));
                })
                .toList();
        return new CatalogTree(new ArrayList<>(domains));
    }
}
