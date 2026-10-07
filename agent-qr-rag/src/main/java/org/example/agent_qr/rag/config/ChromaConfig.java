package org.example.agent_qr.rag.config;

import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.embedding.EmbeddingDimensionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * ChromaDB 向量存储配置，手动创建 {@link ChromaEmbeddingStore} Bean。
 * <p>
 * langchain4j-chroma 目前没有 Spring Boot 自动配置，
 * 因此需要手动通过 Builder 构造并注册为 Spring Bean。
 * </p>
 * <p>
 * 在 Bean 初始化之前，通过 ChromaDB REST API 确保 collection
 * 使用余弦相似度（cosine）而非默认的 L2 距离度量。
 * </p>
 * <p>
 * ⚠️ 路径约束：ChromaDB v2 API 的资源路径必须包含 tenant/database 段
 * （{@code /api/v2/tenants/{tenant}/databases/{database}/collections}），
 * 且 {@code tenant}/{@code database} 必须与 langchain4j {@code ChromaClientV2}
 * 的默认值一致（均为 {@code default}）。二者不一致会导致
 * "本类检查的 collection" 与 "实际读写的 collection" 分裂。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Configuration
public class ChromaConfig {

    @Value("${langchain4j.chroma.base-url:http://localhost:8000}")
    private String baseUrl;

    /**
     * 既有（历史）Collection 名称 —— 隔离关闭或既有数据保护时使用。
     * <p>批次 07 · 任务 7.1：实际写入的 Collection 以
     * {@link EmbeddingDimensionManager#getEffectiveCollectionName()} 为准。</p>
     */
    @Value("${langchain4j.chroma.collection-name:enterprise_knowledge}")
    private String collectionName;

    @Value("${langchain4j.chroma.timeout-seconds:30}")
    private long timeoutSeconds;

    /**
     * Collection 名称与维度的唯一口径（批次 07 · 任务 7.1.1 的接通点）。
     * <p>改造前该类直接使用固定配置名，隔离命名（{@code kb_{provider}_{model}}）完全没有消费方。</p>
     */
    @Autowired
    private EmbeddingDimensionManager dimensionManager;

    /**
     * ChromaDB v2 API 的租户名。
     * <p>
     * ⚠️ 必须与 langchain4j {@code ChromaClientV2} 的默认值保持一致
     * （1.16.3-beta26 的默认 tenant/database 均为 {@code "default"}）。
     * 若此处改用 {@code default_tenant}，将与 langchain4j 的读写命名空间分裂——
     * 本类会"看不到"实际使用的 collection 而重复创建。
     * </p>
     */
    @Value("${langchain4j.chroma.tenant:default}")
    private String tenant;

    /**
     * ChromaDB v2 API 的数据库名。约束同 {@link #tenant}。
     */
    @Value("${langchain4j.chroma.database:default}")
    private String database;

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

    /**
     * 在 ChromaEmbeddingStore Bean 创建之前，确保<b>生效 Collection</b>
     * 使用 cosine 距离度量。如果 collection 已存在，不做修改
     * （ChromaDB 的 distance metric 在创建后不可更改）。
     * <p>
     * 批次 07 · 任务 7.1：名称与存在性检查统一委托给 {@link EmbeddingDimensionManager}
     * （真实查询 ChromaDB；404 才表示不存在，其余错误只降级告警，不误判为"不存在"而误建）。
     * </p>
     */
    @PostConstruct
    public void ensureCosineDistance() {
        String effective = effectiveCollectionName();
        log.info("ChromaDB collection 准备: 生效名称={}（配置的既有 Collection={}, tenant={}, database={}）",
                effective, collectionName, tenant, database);
        try {
            dimensionManager.ensureCosineCollection(effective);
        } catch (Exception e) {
            log.warn("ChromaDB collection 初始化失败 (baseUrl={}, tenant={}, database={}, collection={}): {}。"
                            + "将回退到 langchain4j 默认行为（collection 若不存在将由 langchain4j "
                            + "以默认 L2 距离创建，检索效果可能下降）。",
                    baseUrl, tenant, database, effective, e.getMessage());
        }
    }

    @Bean
    public ChromaEmbeddingStore chromaEmbeddingStore() {
        String effective = effectiveCollectionName();
        log.info("初始化 ChromaEmbeddingStore: baseUrl={}, collectionName={}（生效名称，隔离命名={}）, timeout={}s",
                baseUrl, effective, dimensionManager.getCollectionName(), timeoutSeconds);
        return ChromaEmbeddingStore.builder()
                .apiVersion(ChromaApiVersion.V2)
                .baseUrl(baseUrl)
                .collectionName(effective)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 解析生效的 Collection 名称；解析失败时回退到配置值，保证不影响 Bean 装配。
     *
     * @return 生效的 Collection 名称
     */
    private String effectiveCollectionName() {
        try {
            String effective = dimensionManager.getEffectiveCollectionName();
            if (effective != null && !effective.isBlank()) {
                return effective;
            }
        } catch (Exception e) {
            log.warn("解析生效 Collection 名称失败，回退到配置值 {}: {}", collectionName, e.getMessage());
        }
        return collectionName;
    }
}
