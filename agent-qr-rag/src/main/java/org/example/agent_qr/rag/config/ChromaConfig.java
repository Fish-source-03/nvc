package org.example.agent_qr.rag.config;

import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

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

    @Value("${langchain4j.chroma.collection-name:enterprise_knowledge}")
    private String collectionName;

    @Value("${langchain4j.chroma.timeout-seconds:30}")
    private long timeoutSeconds;

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
     * 在 ChromaEmbeddingStore Bean 创建之前，确保 collection
     * 使用 cosine 距离度量。如果 collection 已存在，不做修改
     * （ChromaDB 的 distance metric 在创建后不可更改）。
     */
    @PostConstruct
    public void ensureCosineDistance() {
        WebClient client = WebClient.create(baseUrl);
        String collectionsPath = buildCollectionsPath(tenant, database);
        try {
            // 检查 collection 是否已存在。
            // 注意：仅 404 才表示"不存在"；其余 4xx（如路径不合法返回 400）必须向上抛出，
            // 否则会被误判为不存在并静默掩盖 API 不兼容问题。
            String collectionId = client.get()
                    .uri(collectionsPath + "/{name}", collectionName)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, resp -> {
                        log.info("ChromaDB collection '{}' 不存在，将创建为 cosine 距离度量", collectionName);
                        return Mono.empty();
                    })
                    .bodyToMono(Map.class)
                    .map(body -> Objects.toString(body.get("id"), null))
                    .onErrorReturn("")
                    .block(Duration.ofSeconds(10));

            if (collectionId == null || collectionId.isEmpty()) {
                // Collection 不存在 → 创建时指定 cosine
                Map<String, Object> requestBody = Map.of(
                        "name", collectionName,
                        "metadata", Map.of("hnsw:space", "cosine")
                );
                Map<String, Object> response = client.post()
                        .uri(collectionsPath)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block(Duration.ofSeconds(10));

                log.info("ChromaDB collection '{}' 已创建，distance metric=cosine, response={}",
                        collectionName, response);
            } else {
                log.info("ChromaDB collection '{}' 已存在 (id={})，跳过创建。"
                                + "注意：如果现有 collection 使用 L2 距离，"
                                + "需手动删除后重建以获得更好的语义检索效果。",
                        collectionName, collectionId);
            }
        } catch (Exception e) {
            log.warn("ChromaDB collection 初始化失败 (baseUrl={}, tenant={}, database={}, collection={}): {}。"
                            + "将回退到 langchain4j 默认行为（collection 若不存在将由 langchain4j "
                            + "以默认 L2 距离创建，检索效果可能下降）。",
                    baseUrl, tenant, database, collectionName, e.getMessage());
        }
    }

    @Bean
    public ChromaEmbeddingStore chromaEmbeddingStore() {
        log.info("初始化 ChromaEmbeddingStore: baseUrl={}, collectionName={}, timeout={}s",
                baseUrl, collectionName, timeoutSeconds);
        return ChromaEmbeddingStore.builder()
                .apiVersion(ChromaApiVersion.V2)
                .baseUrl(baseUrl)
                .collectionName(collectionName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
