package org.example.agent_qr.rag.embedding;

import org.example.agent_qr.rag.provider.EmbeddingProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link EmbeddingDimensionManager} 测试（批次 07 · 任务 7.1 / 问题 16）。
 * <p>
 * 拦截的核心缺陷：整个 Collection 隔离链是死代码——
 * <ol>
 *   <li>{@code getEffectiveCollectionName()} 零调用方，实际 Collection 名来自固定配置；</li>
 *   <li>{@code ensureCollection()} <b>恒返回 true</b>，从不查询 ChromaDB；</li>
 *   <li>{@code collection-prefix} / {@code auto-dimension-check} 两个配置键无读取点。</li>
 * </ol>
 * 同时锁定"历史数据边界"：既有 Collection 有数据而隔离 Collection 未建立时，
 * <b>必须继续使用既有 Collection</b>（否则历史向量立即不可检索）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingDimensionManagerTest {

    /** ChromaDB 中"已存在"的 Collection */
    private final Set<String> existingCollections = new LinkedHashSet<>();

    /** Collection 名称 → 维度（写入 collection 元信息响应） */
    private final Map<String, Integer> collectionDimensions = new LinkedHashMap<>();

    /** collection 元信息响应中不带 dimension 字段时，用 embeddings 长度兜底的假数据 */
    private final Map<String, List<Double>> firstEmbeddings = new LinkedHashMap<>();

    /** 全部请求路径（验证"真实查询"与"跳过检测"） */
    private final List<String> requestUrls = new ArrayList<>();

    /** 模拟 ChromaDB 不可达 */
    private boolean offline;

    private int probeDimension = 3;

    @Mock
    private ProviderFactory providerFactory;

    @Mock
    private EmbeddingProvider embeddingProvider;

    private EmbeddingDimensionManager manager;

    @BeforeEach
    void setUp() {
        manager = new EmbeddingDimensionManager() {
            @Override
            WebClient webClient() {
                return WebClient.builder().exchangeFunction(exchangeFunction()).build();
            }
        };
        ReflectionTestUtils.setField(manager, "providerFactory", providerFactory);
        ReflectionTestUtils.setField(manager, "baseUrl", "http://localhost:8000");
        ReflectionTestUtils.setField(manager, "tenant", "default");
        ReflectionTestUtils.setField(manager, "database", "default");
        when(providerFactory.getEmbeddingProviderType()).thenReturn("ollama");
        when(providerFactory.getEmbeddingModelName()).thenReturn("qwen3-embedding:4b");
        when(providerFactory.getEmbeddingProvider()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new float[probeDimension]);
    }

    // ==================== 命名规则 ====================

    @Test
    @DisplayName("★ 不同 Embedding 模型产生不同的 Collection 名（隔离的真正含义）")
    void getCollectionName_shouldDifferPerModel() {
        assertThat(manager.getCollectionName()).isEqualTo("kb_ollama_qwen3-embedding-4b");

        when(providerFactory.getEmbeddingModelName()).thenReturn("nomic-embed-text");
        assertThat(manager.getCollectionName()).isEqualTo("kb_ollama_nomic-embed-text");

        when(providerFactory.getEmbeddingProviderType()).thenReturn("deepseek");
        when(providerFactory.getEmbeddingModelName()).thenReturn("deepseek-embedding");
        assertThat(manager.getCollectionName()).isEqualTo("kb_deepseek_deepseek-embedding");
    }

    @Test
    @DisplayName("★ collection-prefix 配置生效（改造前该键零 Java 读取点）")
    void getCollectionName_shouldHonorConfiguredPrefix() {
        ReflectionTestUtils.setField(manager, "collectionPrefix", "mykb");

        assertThat(manager.getCollectionName()).isEqualTo("mykb_ollama_qwen3-embedding-4b");
    }

    @Test
    @DisplayName("模型名中的冒号被替换为连字符（ChromaDB 名称不允许冒号）")
    void getCollectionName_shouldSanitizeModelName() {
        when(providerFactory.getEmbeddingModelName()).thenReturn("qwen3:4b");

        assertThat(manager.getCollectionName()).doesNotContain(":").isEqualTo("kb_ollama_qwen3-4b");
    }

    // ==================== ensureCollection 真实查询 ====================

    @Test
    @DisplayName("★ ensureCollection 在 Collection 不存在时返回 false（而非恒 true）")
    void ensureCollection_shouldReturnFalse_whenMissing() {
        existingCollections.clear();

        boolean ready = manager.ensureCollection("kb_ollama_qwen3-embedding-4b");

        assertThat(ready).as("恒返回 true 的旧实现会让本断言失败").isFalse();
        assertThat(requestUrls)
                .as("必须真实查询 ChromaDB")
                .contains("/api/v2/tenants/default/databases/default/collections/kb_ollama_qwen3-embedding-4b");
    }

    @Test
    @DisplayName("Collection 存在时 ensureCollection 返回 true，且结果被缓存不重复查询")
    void ensureCollection_shouldReturnTrueAndCache() {
        existingCollections.add("kb_ollama_qwen3-embedding-4b");

        assertThat(manager.ensureCollection("kb_ollama_qwen3-embedding-4b")).isTrue();
        int urlsAfterFirst = requestUrls.size();
        assertThat(manager.ensureCollection("kb_ollama_qwen3-embedding-4b")).isTrue();

        assertThat(requestUrls.size())
                .as("存在性结果应缓存，第二次不再发请求")
                .isEqualTo(urlsAfterFirst);
    }

    @Test
    @DisplayName("ChromaDB 不可达时 ensureCollection 返回 false（按不存在处理）而非抛异常")
    void ensureCollection_shouldReturnFalse_whenUnreachable() {
        offline = true;

        assertThat(manager.ensureCollection("kb_ollama_qwen3-embedding-4b")).isFalse();
        assertThat(manager.collectionPresence("kb_ollama_qwen3-embedding-4b"))
                .as("不可达属'无法判定'，不得缓存为 false")
                .isNull();
    }

    // ==================== 生效名称解析（历史数据边界） ====================

    @Test
    @DisplayName("★ 既有 Collection 有数据、隔离 Collection 未建立 → 继续用既有（历史向量不得失检索）")
    void effectiveName_shouldKeepLegacyCollection_whenItExists() {
        existingCollections.add("enterprise_knowledge");

        String effective = manager.getEffectiveCollectionName();

        assertThat(effective)
                .as("改名会让存量 19 条向量立即读不到，必须保守使用既有 Collection")
                .isEqualTo("enterprise_knowledge");
    }

    @Test
    @DisplayName("隔离 Collection 已建立 → 使用隔离命名（模型隔离生效）")
    void effectiveName_shouldUseDerived_whenDerivedExists() {
        existingCollections.add("enterprise_knowledge");
        existingCollections.add("kb_ollama_qwen3-embedding-4b");

        assertThat(manager.getEffectiveCollectionName()).isEqualTo("kb_ollama_qwen3-embedding-4b");
    }

    @Test
    @DisplayName("全新环境（两个 Collection 都不存在）→ 使用隔离命名")
    void effectiveName_shouldUseDerived_whenNothingExists() {
        assertThat(manager.getEffectiveCollectionName()).isEqualTo("kb_ollama_qwen3-embedding-4b");
    }

    @Test
    @DisplayName("★ ChromaDB 不可达 → 保守使用既有配置名（绝不因查询失败切走到未知 Collection）")
    void effectiveName_shouldFallBackToBase_whenUnreachable() {
        offline = true;

        assertThat(manager.getEffectiveCollectionName()).isEqualTo("enterprise_knowledge");
    }

    @Test
    @DisplayName("★ auto-dimension-check=false → 跳过检测、固定使用既有 Collection（配置真正生效）")
    void effectiveName_shouldUseBase_whenAutoDimensionCheckDisabled() {
        ReflectionTestUtils.setField(manager, "autoDimensionCheck", false);

        assertThat(manager.getEffectiveCollectionName()).isEqualTo("enterprise_knowledge");
        assertThat(requestUrls).as("关闭检测后不得再探测 ChromaDB").isEmpty();

        manager.checkDimension();

        assertThat(manager.isDimensionCheckSkipped()).isTrue();
        assertThat(requestUrls).isEmpty();
    }

    // ==================== 维度一致性检测 ====================

    @Test
    @DisplayName("★ auto-dimension-check=true：维度一致时判定通过（含探针 embed 调用）")
    void runDimensionCheck_shouldDetectConsistentDimension() {
        existingCollections.add("enterprise_knowledge");
        collectionDimensions.put("enterprise_knowledge", 3);
        probeDimension = 3;

        manager.runDimensionCheck();

        assertThat(manager.isDimensionConsistent()).isTrue();
    }

    @Test
    @DisplayName("★ auto-dimension-check=true：维度不一致时判定失败（写入会报错，必须可见）")
    void runDimensionCheck_shouldDetectInconsistentDimension() {
        existingCollections.add("enterprise_knowledge");
        collectionDimensions.put("enterprise_knowledge", 2560);
        probeDimension = 1024;

        manager.runDimensionCheck();

        assertThat(manager.isDimensionConsistent()).isFalse();
    }

    @Test
    @DisplayName("collection 响应无 dimension 字段时退化为量取一条向量的长度")
    void fetchCollectionDimension_shouldFallBackToEmbeddingLength() {
        existingCollections.add("enterprise_knowledge");
        firstEmbeddings.put("enterprise_knowledge", List.of(0.1, 0.2, 0.3, 0.4));

        assertThat(manager.fetchCollectionDimension("enterprise_knowledge")).isEqualTo(4);
    }

    @Test
    @DisplayName("探针调用失败时不误判为不一致（保持无法判定）")
    void runDimensionCheck_shouldRemainUnknown_whenProbeFails() {
        existingCollections.add("enterprise_knowledge");
        collectionDimensions.put("enterprise_knowledge", 2560);
        when(embeddingProvider.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("Ollama 不可达"));

        manager.runDimensionCheck();

        assertThat(manager.isDimensionConsistent()).isNull();
    }

    // ==================== collecton 创建（cosine） ====================

    @Test
    @DisplayName("cosine 创建只针对不存在的 Collection；已存在时跳过（distance metric 不可改）")
    void ensureCosineCollection_shouldOnlyCreateWhenMissing() {
        existingCollections.add("enterprise_knowledge");
        boolean created = manager.ensureCosineCollection("enterprise_knowledge");

        assertThat(created).isTrue();
        assertThat(requestUrls)
                .as("已存在时不得发起创建请求")
                .noneMatch(url -> url.endsWith("/collections"));
    }

    @Test
    @DisplayName("Collection 不存在时以 cosine 创建")
    void ensureCosineCollection_shouldCreateWithCosine() {
        boolean created = manager.ensureCosineCollection("kb_ollama_qwen3-embedding-4b");

        assertThat(created).isTrue();
        assertThat(requestUrls).contains("/api/v2/tenants/default/databases/default/collections");
    }

    // ==================== 假 ChromaDB ====================

    private ExchangeFunction exchangeFunction() {
        return request -> {
            String path = request.url().getPath();
            String method = request.method().name();
            requestUrls.add(path);
            if (offline) {
                return Mono.error(new RuntimeException("Connection refused"));
            }
            if (path.endsWith("/collections") && "POST".equals(method)) {
                return Mono.just(json(HttpStatus.OK, "{\"id\":\"new-collection-id\",\"name\":\"kb_x\"}"));
            }
            if (path.endsWith("/get")) {
                for (List<Double> vector : firstEmbeddings.values()) {
                    return Mono.just(json(HttpStatus.OK, embeddings(vector)));
                }
                return Mono.just(json(HttpStatus.OK, "{\"ids\":[],\"embeddings\":[]}"));
            }
            // GET /collections/{name}
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!existingCollections.contains(name)) {
                return Mono.just(json(HttpStatus.NOT_FOUND, "{\"detail\":\"Collection not found\"}"));
            }
            Integer dimension = collectionDimensions.get(name);
            String body = "{\"id\":\"7fbaddfc-4cd8-4651-b987-827e81e31257\",\"name\":\"" + name + "\""
                    + (dimension == null ? "" : ",\"dimension\":" + dimension) + "}";
            return Mono.just(json(HttpStatus.OK, body));
        };
    }

    private static String embeddings(List<Double> vector) {
        StringBuilder sb = new StringBuilder("{\"ids\":[\"v1\"],\"embeddings\":[[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector.get(i));
        }
        return sb.append("]]}").toString();
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
