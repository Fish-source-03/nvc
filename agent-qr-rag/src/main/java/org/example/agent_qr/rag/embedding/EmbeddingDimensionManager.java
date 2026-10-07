package org.example.agent_qr.rag.embedding;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 向量维度管理与 ChromaDB Collection 自动隔离（P3）。
 * <p>
 * 核心设计：ChromaDB Collection 名称嵌入 Provider 类型和模型名，
 * 不同 Embedding 模型产生的向量自动存储到不同 Collection，
 * 确保不同维度的向量不会混在同一个 Collection 中导致检索失败。
 * </p>
 *
 * <p><b>Collection 命名规则：</b></p>
 * <pre>{@code {agent-qr.embedding.collection-prefix}_{providerType}_{modelName}}</pre>
 * 示例（前缀默认 {@code kb}）：
 * <ul>
 *   <li>{@code kb_ollama_qwen3-embedding-4b}</code></li>
 *   <li>{@code kb_ollama_nomic-embed-text}</code></li>
 * </ul>
 * 模型名中的 {@code :} 替换为 {@code -}（ChromaDB 名称不允许冒号）。
 *
 * <h3>批次 07 · 任务 7.1：让隔离真正生效（问题 16）</h3>
 * <p>
 * 改造前 {@link #getEffectiveCollectionName()} 零调用方、{@link #ensureCollection} 恒返回 true，
 * 整条隔离链是死代码。现在：
 * </p>
 * <ol>
 *   <li>{@link #ensureCollection} <b>真实查询 ChromaDB</b>（存在性结果缓存；查询失败时缓存不写入，
 *       下次可重试）；</li>
 *   <li>{@link #getEffectiveCollectionName()} 是"实际写入/检索使用哪个 Collection"的<b>唯一口径</b>，
 *       由 {@code ChromaConfig}（ChromaEmbeddingStore Bean）与 {@code ChromaRetriever}（REST 枚举）消费；</li>
 *   <li>{@code agent-qr.embedding.collection-prefix} 与 {@code agent-qr.embedding.auto-dimension-check}
 *       均通过 {@code @Value} 实际生效。</li>
 * </ol>
 *
 * <h3>⚠️ 历史数据边界（不得破坏）</h3>
 * <p>
 * 存量向量在既有 Collection（{@code langchain4j.chroma.collection-name}，即
 * {@code enterprise_knowledge}）中。若直接把写入路径切到隔离命名，历史向量会<b>立即不可检索</b>。
 * 因此解析规则是"<b>保守优先既有数据</b>"：
 * </p>
 * <ol>
 *   <li>{@code auto-dimension-check=false} → 使用既有配置的 Collection（隔离关闭）；</li>
 *   <li>隔离命名与既有配置同名 → 直接使用；</li>
 *   <li>隔离命名的 Collection <b>已存在</b> → 使用它（隔离已就绪）；</li>
 *   <li>既有 Collection <b>已存在</b> → 继续使用既有（历史向量可读；日志 WARN 说明如何启用隔离）；</li>
 *   <li>ChromaDB 查询失败（不可达）→ 保守使用既有配置，<b>绝不</b>因一次查询失败切走到未知 Collection；</li>
 *   <li>两者都不存在（全新环境）→ 使用隔离命名，首次写入前按 cosine 创建。</li>
 * </ol>
 * <p>
 * 即：模型隔离在"没有历史包袱"或"隔离 Collection 已建立"时生效；
 * 已有数据的场景需先按差集迁移/重建再切换（见 {@code doc/问题清单/16-*.md}）。
 * </p>
 *
 * @see ProviderFactory
 */
@Slf4j
@Component
public class EmbeddingDimensionManager {

    @Autowired
    private ProviderFactory providerFactory;

    /** Collection 前缀（批次 07 · 任务 7.1.3 接通；P3 配置项，原为无读取点的预留配置） */
    @Value("${agent-qr.embedding.collection-prefix:kb}")
    private String collectionPrefix = "kb";

    /**
     * 是否启用维度检测与模型隔离（批次 07 · 任务 7.1.3）。
     * <p>该配置的注释声称"启动时自动检测向量维度一致性"，改造前无任何读取点（误导性配置）。
     * 现真正生效：{@code false} 时跳过启动检测，并固定使用既有配置的 Collection。</p>
     */
    @Value("${agent-qr.embedding.auto-dimension-check:true}")
    private boolean autoDimensionCheck = true;

    /**
     * 既有（历史）Collection 名称 —— 存量向量所在地，也是隔离关闭时的目标。
     */
    @Value("${langchain4j.chroma.collection-name:enterprise_knowledge}")
    private String baseCollectionName = "enterprise_knowledge";

    @Value("${langchain4j.chroma.base-url:http://localhost:8000}")
    private String baseUrl;

    @Value("${langchain4j.chroma.tenant:default}")
    private String tenant = "default";

    @Value("${langchain4j.chroma.database:default}")
    private String database = "default";

    /**
     * ChromaDB 探测超时（秒）。
     * <p>刻意短于 {@code langchain4j.chroma.timeout-seconds}（30s）：Collection 存在性检查发生在
     * Bean 装配阶段（{@code ChromaEmbeddingStore} 需要生效名称），ChromaDB 不可达时必须快速降级，
     * 不能把启动拖住。</p>
     */
    @Value("${agent-qr.embedding.collection-check-timeout-seconds:3}")
    private long checkTimeoutSeconds = 3;

    /** Collection 存在性缓存（仅缓存"确定"结果：true/false；查询失败不缓存） */
    private final Map<String, Boolean> collectionCache = new ConcurrentHashMap<>();

    /** 隔离命名的 Collection（由 Provider 类型 + 模型名派生） */
    private volatile String currentCollectionName;

    /** 解析后的生效 Collection 名称（实际写入/检索使用） */
    private volatile String effectiveCollectionName;

    /** 是否因配置关闭而跳过了维度检测（可观测性用） */
    private volatile boolean dimensionCheckSkipped;

    /** 维度一致性检测结果：{@code null} 表示无法判定（无向量 / 探针失败 / 检测关闭） */
    private volatile Boolean dimensionConsistent;

    /** 维度探针文本（一次短文本 embed，用于获取当前模型的向量维度） */
    static final String DIMENSION_PROBE_TEXT = "dimension-probe";

    /** 惰性构建的 REST 客户端 */
    private volatile WebClient chromaWebClient;

    /**
     * 初始化：计算隔离命名的 Collection 名称。
     */
    @PostConstruct
    public void init() {
        this.currentCollectionName = getCollectionName();
        log.info("EmbeddingDimensionManager 初始化: 隔离命名={}, 既有 Collection={}, prefix={}, auto-dimension-check={}",
                currentCollectionName, baseCollectionName, collectionPrefix, autoDimensionCheck);
    }

    /**
     * 根据当前 Embedding Provider 类型和模型名生成<b>隔离命名</b>的 Collection 名称。
     * <p>格式：{@code {prefix}_{providerType}_{modelName}}。模型名中的 {@code :} 替换为 {@code -}。</p>
     *
     * @return ChromaDB Collection 名称（隔离命名）
     */
    public String getCollectionName() {
        String providerType = providerFactory.getEmbeddingProviderType();
        String modelName = providerFactory.getEmbeddingModelName();
        // ChromaDB Collection 名称不允许冒号
        String safeName = (modelName != null && !modelName.isBlank() ? modelName : "default").replace(":", "-");
        String prefix = (collectionPrefix == null || collectionPrefix.isBlank()) ? "kb" : collectionPrefix;
        return prefix + "_" + providerType + "_" + safeName;
    }

    /**
     * 获取<b>实际生效</b>的 Collection 名称（批次 07 · 任务 7.1.1 的接通点）。
     * <p>
     * 写入（{@code ChromaConfig} 构造的 {@code ChromaEmbeddingStore}）与检索/枚举
     * （{@code ChromaRetriever}）都以此为准，保证"写的"与"读的"是同一个 Collection。
     * 解析结果缓存（首次调用发生在 Bean 装配阶段）。
     * </p>
     *
     * @return 生效的 Collection 名称
     */
    public String getEffectiveCollectionName() {
        String cached = effectiveCollectionName;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (effectiveCollectionName == null) {
                effectiveCollectionName = resolveEffectiveCollectionName();
                log.info("Collection 生效名称已确定: {}（隔离命名={}）", effectiveCollectionName, getCollectionName());
            }
            return effectiveCollectionName;
        }
    }

    /**
     * 解析生效的 Collection 名称（规则见类注释「历史数据边界」）。
     *
     * @return 生效的 Collection 名称
     */
    String resolveEffectiveCollectionName() {
        String derived = getCollectionName();
        if (!autoDimensionCheck) {
            dimensionCheckSkipped = true;
            log.info("auto-dimension-check=false：跳过维度检测与模型隔离，使用既有 Collection: {}", baseCollectionName);
            return baseCollectionName;
        }
        if (derived.equals(baseCollectionName)) {
            return derived;
        }

        Boolean derivedExists = collectionPresence(derived);
        if (Boolean.TRUE.equals(derivedExists)) {
            log.info("模型隔离 Collection 已存在，使用隔离命名: {}", derived);
            return derived;
        }
        Boolean baseExists = collectionPresence(baseCollectionName);
        if (Boolean.TRUE.equals(baseExists)) {
            log.warn("既有 Collection {} 存在且隔离 Collection {} 尚未建立：为避免历史向量不可检索，"
                            + "继续使用既有 Collection。如需启用模型隔离，请先按差集迁移/重建向量后再切换"
                            + "（见问题 16 修复说明；不要重建既有 Collection）。",
                    baseCollectionName, derived);
            return baseCollectionName;
        }
        if (derivedExists == null || baseExists == null) {
            log.warn("ChromaDB 不可达或查询失败，保守使用既有配置的 Collection: {}（不切换到隔离命名，避免历史向量不可检索）",
                    baseCollectionName);
            return baseCollectionName;
        }
        log.info("未发现既有 Collection，启用模型隔离命名: {}", derived);
        return derived;
    }

    /**
     * 确保目标 Collection 可用（批次 07 · 任务 7.1.2：<b>真实查询 ChromaDB</b>）。
     * <p>
     * 改造前恒返回 {@code true}（从不查询），使"Collection 不存在"这类问题静默通过。
     * 现在：2xx → {@code true}；404 → {@code false}；查询失败（不可达/超时）→ {@code false} 并记 WARN。
     * </p>
     * <p>不负责创建：创建由 {@link #ensureCosineCollection} 或 ChromaDB 客户端首次写入完成。</p>
     *
     * @param collectionName Collection 名称
     * @return {@code true} 表示 Collection 已存在
     */
    public boolean ensureCollection(String collectionName) {
        Boolean exists = collectionPresence(collectionName);
        if (Boolean.TRUE.equals(exists)) {
            log.debug("Collection 已确认存在: {}", collectionName);
            return true;
        }
        if (Boolean.FALSE.equals(exists)) {
            log.info("Collection 不存在: {}", collectionName);
        } else {
            log.warn("Collection 存在性查询失败（按不存在处理）: {}", collectionName);
        }
        return false;
    }

    /**
     * 查询 Collection 是否存在（三态）。
     * <p>已确认的结果写入缓存；查询失败返回 {@code null}（不缓存，允许后续重试）。</p>
     *
     * @param collectionName Collection 名称
     * @return {@code TRUE} 存在 / {@code FALSE} 不存在 / {@code null} 无法判定
     */
    Boolean collectionPresence(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return Boolean.FALSE;
        }
        Boolean cached = collectionCache.get(collectionName);
        if (cached != null) {
            return cached;
        }
        try {
            Boolean exists = webClient().get()
                    .uri(collectionsPath() + "/{name}", collectionName)
                    .exchangeToMono(response -> {
                        if (response.statusCode().is2xxSuccessful()) {
                            return Mono.just(Boolean.TRUE);
                        }
                        if (response.statusCode().value() == 404) {
                            return Mono.just(Boolean.FALSE);
                        }
                        return Mono.error(new IllegalStateException(
                                "ChromaDB 查询 Collection 返回 " + response.statusCode()));
                    })
                    .block(Duration.ofSeconds(checkTimeoutSeconds));
            if (exists != null) {
                collectionCache.put(collectionName, exists);
            }
            return exists;
        } catch (Exception e) {
            log.warn("ChromaDB Collection 存在性查询异常: collection={}, error={}", collectionName, e.getMessage());
            return null;
        }
    }

    /**
     * 确保 Collection 存在且距离度量为 cosine（不存在则创建）。
     * <p>
     * 由 {@code ChromaConfig} 在装配 {@code ChromaEmbeddingStore} 之前调用：langchain4j 首次写入时
     * 自动创建的 Collection 使用默认 L2 距离，会拖低语义检索效果。
     * </p>
     * <p>⚠️ 只创建、不修改：既有 Collection 的 distance metric 创建后不可更改（与改造前行为一致）。</p>
     *
     * @param collectionName 目标 Collection 名称
     * @return {@code true} 表示 Collection 已就绪（已存在或已创建）
     */
    public boolean ensureCosineCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return false;
        }
        Boolean exists = collectionPresence(collectionName);
        if (Boolean.TRUE.equals(exists)) {
            log.info("ChromaDB collection '{}' 已存在，跳过创建", collectionName);
            return true;
        }
        if (exists == null) {
            log.warn("ChromaDB 不可达，跳过 collection '{}' 的创建（回退到 langchain4j 默认行为）", collectionName);
            return false;
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "name", collectionName,
                    "metadata", Map.of("hnsw:space", "cosine")
            );
            Map response = webClient().post()
                    .uri(collectionsPath())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(checkTimeoutSeconds));
            log.info("ChromaDB collection '{}' 已创建，distance metric=cosine, id={}",
                    collectionName, response == null ? null : Objects.toString(response.get("id"), null));
            collectionCache.put(collectionName, Boolean.TRUE);
            return true;
        } catch (Exception e) {
            log.warn("ChromaDB collection '{}' 创建失败: {}", collectionName, e.getMessage());
            return false;
        }
    }

    /**
     * 向量维度一致性检测（批次 07 · 任务 7.1.2/7.1.3）。
     * <p>
     * {@code agent-qr.embedding.auto-dimension-check=false} 时<b>直接跳过</b>：不查询 ChromaDB、
     * 不做 Embedding 探针（配置的注释声称"启动时自动检测"，此处让其真正可关可开）。
     * </p>
     * <p>
     * 检查放到守护线程执行：探针需要一次 embed 调用，本地模型冷加载可达数十秒，
     * 同步执行会阻塞启动——与 7.3 的"索引异步构建"同一考量。
     * </p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkDimension() {
        if (!autoDimensionCheck) {
            dimensionCheckSkipped = true;
            log.info("已跳过 Embedding 维度一致性检测（agent-qr.embedding.auto-dimension-check=false）");
            return;
        }
        Thread worker = new Thread(this::runDimensionCheck, "dimension-check");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 执行维度与 Collection 检查（同步实现，供 {@link #checkDimension()} 的守护线程与测试直接调用）。
     */
    void runDimensionCheck() {
        try {
            String effective = getEffectiveCollectionName();
            Boolean exists = collectionPresence(effective);
            if (Boolean.TRUE.equals(exists)) {
                log.info("Embedding Collection 检查通过: collection={}（隔离命名={}）", effective, getCollectionName());
                checkDimensionConsistency(effective);
            } else if (Boolean.FALSE.equals(exists)) {
                log.warn("目标 Collection 不存在: {}。首次写入时创建；若历史向量在其它 Collection，需全量重建。", effective);
            } else {
                log.warn("ChromaDB 不可达，跳过 Embedding 维度一致性检测: collection={}", effective);
            }
        } catch (Exception e) {
            log.warn("Embedding 维度一致性检测异常（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 比较"Collection 中已有向量的维度"与"当前 Embedding 模型产出的维度"。
     * <p>
     * 这是 {@code auto-dimension-check} 注释所承诺的能力：维度不一致时写入会失败、
     * 检索会错乱，必须<b>显式可见</b>（ERROR 日志 + {@link #isDimensionConsistent()} 暴露状态），
     * 而不是等到写入报错才发现。
     * </p>
     *
     * @param collectionName 待检查的 Collection
     */
    void checkDimensionConsistency(String collectionName) {
        Integer collectionDim = fetchCollectionDimension(collectionName);
        if (collectionDim == null) {
            log.info("维度一致性检测跳过：Collection {} 中暂无可读向量（首次写入后即可检测）", collectionName);
            dimensionConsistent = null;
            return;
        }
        int modelDim;
        try {
            modelDim = providerFactory.getEmbeddingProvider().embed(DIMENSION_PROBE_TEXT).length;
        } catch (Exception e) {
            log.warn("维度一致性检测跳过：Embedding 探针调用失败（模型={}）: {}",
                    providerFactory.getEmbeddingModelName(), e.getMessage());
            dimensionConsistent = null;
            return;
        }
        boolean consistent = collectionDim == modelDim;
        dimensionConsistent = consistent;
        if (consistent) {
            log.info("Embedding 维度一致性检测通过: collection={}, dimension={}", collectionName, modelDim);
        } else {
            log.error("Embedding 维度不一致！collection={} 维度={}，模型={} 维度={}。"
                            + "写入该 Collection 会失败、检索结果会错乱；请迁移或重建 Collection（不要直接删除既有数据）。",
                    collectionName, collectionDim, providerFactory.getEmbeddingModelName(), modelDim);
        }
    }

    /**
     * 读取 Collection 中向量的维度。
     * <p>优先取 collection 元信息里的 {@code dimension}；ChromaDB 未返回该字段时，
     * 退化为拉取 1 条向量（{@code include=["embeddings"]}，limit=1）量取其长度。</p>
     *
     * @param collectionName Collection 名称
     * @return 维度；无法判定（无向量 / 不可达）时返回 {@code null}
     */
    Integer fetchCollectionDimension(String collectionName) {
        try {
            Map collection = webClient().get()
                    .uri(collectionsPath() + "/{name}", collectionName)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(checkTimeoutSeconds));
            if (collection == null) {
                return null;
            }
            if (collection.get("dimension") instanceof Number dimension && dimension.intValue() > 0) {
                return dimension.intValue();
            }
            String collectionId = Objects.toString(collection.get("id"), null);
            if (collectionId == null) {
                return null;
            }
            Map response = webClient().post()
                    .uri(collectionsPath() + "/" + collectionId + "/get")
                    .bodyValue(Map.of("include", List.of("embeddings"), "limit", 1))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(checkTimeoutSeconds));
            if (response != null && response.get("embeddings") instanceof List<?> embeddings
                    && !embeddings.isEmpty() && embeddings.get(0) instanceof List<?> vector) {
                return vector.size();
            }
        } catch (Exception e) {
            log.debug("读取 Collection 维度失败: collection={}, error={}", collectionName, e.getMessage());
        }
        return null;
    }

    /**
     * 获取隔离命名的 Collection 名称（兼容旧接口；等价于"当前模型应属的 Collection"）。
     *
     * @return 隔离命名的 Collection 名称
     */
    public String getCurrentCollectionName() {
        return currentCollectionName != null ? currentCollectionName : getCollectionName();
    }

    /**
     * 是否因配置关闭而跳过了维度检测。
     *
     * @return {@code true} 表示 {@code auto-dimension-check=false}
     */
    public boolean isDimensionCheckSkipped() {
        return dimensionCheckSkipped;
    }

    /**
     * 维度一致性检测结果。
     *
     * @return {@code TRUE}/{@code FALSE}；{@code null} 表示无法判定或未检测
     */
    public Boolean isDimensionConsistent() {
        return dimensionConsistent;
    }

    /**
     * 构造 collection 集合资源路径（ChromaDB v2 API 必须包含 tenant/database 段）。
     *
     * @param tenant   租户名
     * @param database 数据库名
     * @return 形如 {@code /api/v2/tenants/default/databases/default/collections}
     */
    static String buildCollectionsPath(String tenant, String database) {
        return String.format("/api/v2/tenants/%s/databases/%s/collections", tenant, database);
    }

    private String collectionsPath() {
        return buildCollectionsPath(tenant, database);
    }

    /**
     * 惰性构建 WebClient（与 {@code ChromaRetriever} 同源：显式放大缓冲上限，避免响应超限）。
     *
     * @return WebClient
     */
    WebClient webClient() {
        WebClient client = chromaWebClient;
        if (client == null) {
            synchronized (this) {
                if (chromaWebClient == null) {
                    chromaWebClient = WebClient.builder()
                            .baseUrl(baseUrl)
                            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                            .build();
                }
                client = chromaWebClient;
            }
        }
        return client;
    }
}
