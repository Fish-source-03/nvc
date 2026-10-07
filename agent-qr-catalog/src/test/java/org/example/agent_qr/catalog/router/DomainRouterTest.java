package org.example.agent_qr.catalog.router;

import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.catalog.entity.CatalogTree;
import org.example.agent_qr.catalog.entity.DomainNode;
import org.example.agent_qr.catalog.entity.EntityNode;
import org.example.agent_qr.catalog.entity.SourceNode;
import org.example.agent_qr.catalog.service.KnowledgeCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DomainRouter}（P2 关键词域路由）测试（批次 11 · 任务 11.2.2）。
 * <p>
 * 该路由器是问答链路三级降级链（P3 语义 → <b>P2 关键词</b> → 全局检索）的中间层，
 * 也决定检索过滤条件 {@code domain} 的取值。它一旦出错，后果是
 * "查错域"或"静默跨域"，因此用例锁定三组契约：
 * </p>
 * <ul>
 *   <li><b>降级契约</b>：空查询 / 无关键词 / 无匹配 → 必须 {@code fallbackToGlobal=true}
 *       且过滤条件为空 Map（无过滤 = 全局检索，不得拼出空串 domain）；</li>
 *   <li><b>打分契约</b>：域名 +0.5 / 数据源 +0.3 / 实体 +0.4，分数与来源可见；</li>
 *   <li><b>隔离契约</b>：未命中的域不得进入结果（否则会把用户引导到无关域的数据上）。</li>
 * </ul>
 * <p>
 * 关键词统一使用 ASCII（SmartChineseAnalyzer 对 ASCII 词元不切分），
 * 避免中文分词版本差异导致的用例不稳定。
 * </p>
 *
 * @author agent-qr
 */
class DomainRouterTest {

    private KnowledgeCatalogService catalogService;
    private DomainRouter router;

    @BeforeEach
    void setUp() {
        catalogService = mock(KnowledgeCatalogService.class);
        when(catalogService.getCatalogTree()).thenReturn(fixtureTree());
        router = new DomainRouter();
        org.springframework.test.util.ReflectionTestUtils.setField(router, "catalogService", catalogService);
    }

    /**
     * 两域目录树：SALES 与 FINANCE，各有 1 数据源 / 1 实体。
     */
    private static CatalogTree fixtureTree() {
        EntityNode salesOrder = new EntityNode("sales_order", EntityNode.TYPE_TABLE, 100, null);
        SourceNode salesDb = new SourceNode(1L, "sales_db", "JDBC", null, 100, List.of(salesOrder));
        DomainNode sales = new DomainNode("SALES", 1, 1, List.of(salesDb));

        EntityNode financeLedger = new EntityNode("finance_ledger", EntityNode.TYPE_TABLE, 200, null);
        SourceNode financeDb = new SourceNode(2L, "finance_db", "JDBC", null, 200, List.of(financeLedger));
        DomainNode finance = new DomainNode("FINANCE", 1, 1, List.of(financeDb));

        return new CatalogTree(new ArrayList<>(List.of(sales, finance)));
    }

    // ==================== 降级契约 ====================

    @Test
    @DisplayName("★ 空查询降级到全局检索")
    void route_shouldFallback_whenQueryIsNullOrBlank() {
        assertThat(router.route(null).isFallbackToGlobal()).isTrue();
        assertThat(router.route("   ").isFallbackToGlobal()).isTrue();
        assertThat(router.route(null).getMatchedDomains()).isEmpty();
    }

    @Test
    @DisplayName("无匹配域时降级到全局检索（不得返回空串域过滤）")
    void route_shouldFallback_whenNoDomainMatches() {
        DomainRoutingResult result = router.route("zzzznomatch");

        assertThat(result.isFallbackToGlobal()).isTrue();
        assertThat(result.getMatchedDomains()).isEmpty();
        assertThat(router.buildRetrievalFilter(result)).isEmpty();
    }

    // ==================== 打分契约 ====================

    @Test
    @DisplayName("域名命中的域被打分并成为主域，且排除未命中域")
    void route_shouldScoreDomainNameMatch_andExcludeUnmatchedDomains() {
        DomainRoutingResult result = router.route("finance");

        assertThat(result.isFallbackToGlobal()).isFalse();
        assertThat(result.getMatchedDomains()).containsOnlyKeys("FINANCE");
        assertThat(result.getPrimaryDomain()).isEqualTo("FINANCE");
        // 域名 0.5 + 数据源 finance_db 0.3 + 实体 finance_ledger 0.4（double 累加，按容差比较）
        assertThat(result.getMatchedDomains().get("FINANCE")).isCloseTo(1.2, org.assertj.core.data.Offset.offset(1e-9));
        // 未命中的 SALES 不得出现在结果中
        assertThat(result.getMatchedDomains()).doesNotContainKey("SALES");
    }

    @Test
    @DisplayName("仅实体名命中时得 0.4 分，且实体进入 matchedEntities")
    void route_shouldScoreEntityMatch_andCollectMatchedEntities() {
        DomainRoutingResult result = router.route("ledger");

        assertThat(result.isFallbackToGlobal()).isFalse();
        assertThat(result.getMatchedDomains()).containsOnlyKeys("FINANCE");
        assertThat(result.getMatchedDomains().get("FINANCE")).isEqualTo(0.4);
        assertThat(result.getMatchedEntities()).containsExactly("finance_ledger");
    }

    @Test
    @DisplayName("仅数据源名命中时得 0.3 分")
    void route_shouldScoreSourceMatch() {
        DomainRoutingResult result = router.route("db");

        assertThat(result.isFallbackToGlobal()).isFalse();
        assertThat(result.getMatchedDomains()).containsKeys("SALES", "FINANCE");
        assertThat(result.getMatchedDomains().get("SALES")).isEqualTo(0.3);
        assertThat(result.getMatchedDomains().get("FINANCE")).isEqualTo(0.3);
        assertThat(result.getMatchedEntities()).isEmpty();
    }

    // ==================== 过滤条件契约 ====================

    @Test
    @DisplayName("命中域 → domain 过滤条件为逗号串；降级 → 空过滤（不过滤即全局）")
    void buildRetrievalFilter_shouldReflectRoutingResult() {
        Map<String, String> matched = router.buildRetrievalFilter(router.route("db"));
        assertThat(matched).containsEntry("domain", "SALES,FINANCE");

        Map<String, String> fallback = router.buildRetrievalFilter(router.route(null));
        assertThat(fallback).isEmpty();
    }
}
