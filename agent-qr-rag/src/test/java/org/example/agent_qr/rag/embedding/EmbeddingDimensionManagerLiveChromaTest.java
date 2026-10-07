package org.example.agent_qr.rag.embedding;

import org.example.agent_qr.rag.provider.ProviderFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 向量数防线的<b>实库</b>验证（批次 11 · 任务 11.3，R28）。
 * <p>
 * 单元测试用的是假 ChromaDB；本类用<b>真实 ChromaDB</b>（REST，非 langchain4j 客户端）
 * 验证两件事：
 * </p>
 * <ol>
 *   <li>{@link EmbeddingDimensionManager#collectionCount(String)} 能读到真实条数——
 *       真实端点是 {@code GET /collections/{collection_id}/count}（<b>只接受 id</b>，
 *       传名称报 {@code Collection ID is not a valid UUIDv4}，单元测试的假实现无法暴露这一差异）；</li>
 *   <li>当前环境解析结果仍为既有 Collection（隔离名尚不存在，历史向量 19 条不得丢失）。</li>
 * </ol>
 * <p>
 * ⚠️ <b>只读</b>：不写入、不创建、不删除、不重建任何 Collection——
 * {@code enterprise_knowledge} 的 19 条历史向量是检索链路的基线（见任务 11.3 禁止事项）。
 * ChromaDB 不可达时用 {@link Assumptions} 跳过（不误报失败）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingDimensionManagerLiveChromaTest {

    /** 历史向量基线（2026-10-07 实测；只增不减——丢失即为回归） */
    private static final long LEGACY_VECTOR_BASELINE = 19L;

    @Mock
    private ProviderFactory providerFactory;

    private EmbeddingDimensionManager manager;

    private Long legacyCount;

    @BeforeEach
    void setUp() {
        manager = new EmbeddingDimensionManager();
        ReflectionTestUtils.setField(manager, "providerFactory", providerFactory);
        ReflectionTestUtils.setField(manager, "baseUrl", System.getProperty(
                "agent-qr.live.chroma-url", "http://localhost:8000"));
        ReflectionTestUtils.setField(manager, "tenant", "default");
        ReflectionTestUtils.setField(manager, "database", "default");
        ReflectionTestUtils.setField(manager, "baseCollectionName", "enterprise_knowledge");
        ReflectionTestUtils.setField(manager, "collectionPrefix", "kb");
        ReflectionTestUtils.setField(manager, "autoDimensionCheck", true);
        when(providerFactory.getEmbeddingProviderType()).thenReturn("ollama");
        when(providerFactory.getEmbeddingModelName()).thenReturn("qwen3-embedding:4b");

        legacyCount = manager.collectionCount("enterprise_knowledge");
    }

    @Test
    @DisplayName("★ 实库：真实 ChromaDB 的 count 端点可读（先按名称解析 id 再计数）")
    void collectionCount_shouldReadRealChromaDb() {
        Assumptions.assumeTrue(legacyCount != null, "ChromaDB 不可达，跳过实库验证");

        assertThat(legacyCount)
                .as("历史向量基线为 %d 条（只增不减）；count 读不到说明端点形态已变（须先解析 collection id）",
                        LEGACY_VECTOR_BASELINE)
                .isGreaterThanOrEqualTo(LEGACY_VECTOR_BASELINE);
    }

    @Test
    @DisplayName("★ 实库：隔离名尚不存在 + 既有有 19 条 → 解析结果仍是既有 Collection")
    void effectiveName_shouldKeepLegacyCollection_inRealEnvironment() {
        Assumptions.assumeTrue(legacyCount != null, "ChromaDB 不可达，跳过实库验证");

        Boolean derivedExists = manager.collectionPresence("kb_ollama_qwen3-embedding-4b");
        String effective = manager.getEffectiveCollectionName();

        if (Boolean.TRUE.equals(derivedExists)) {
            // 隔离 Collection 已被（人工）建立：此时必须由条数决定，不能一律切走
            Long derivedCount = manager.collectionCount("kb_ollama_qwen3-embedding-4b");
            assertThat(derivedCount).as("隔离名已存在但条数不可判定 → 保守用既有").isNotNull();
            assertThat(effective)
                    .as("隔离名有向量 → 用隔离名；隔离名仍为空 → 继续用既有（R28 的原风险场景）")
                    .isEqualTo(derivedCount > 0 ? "kb_ollama_qwen3-embedding-4b" : "enterprise_knowledge");
        } else {
            assertThat(effective)
                    .as("当前环境隔离名尚不存在：既有 19 条向量必须保持可检索（不得因本次改动切走）")
                    .isEqualTo("enterprise_knowledge");
        }
    }

    @Test
    @DisplayName("★ 实库：不存在的 Collection 返回 null（'无法判定'而非'0 条'，供保守分支使用）")
    void collectionCount_shouldReturnNull_whenCollectionMissing() {
        Assumptions.assumeTrue(legacyCount != null, "ChromaDB 不可达，跳过实库验证");

        assertThat(manager.collectionCount("kb_collection_that_does_not_exist_probe"))
                .as("不存在的 Collection 必须判为'不可判定'（null），否则会与'存在但为空'混淆")
                .isNull();
    }
}
