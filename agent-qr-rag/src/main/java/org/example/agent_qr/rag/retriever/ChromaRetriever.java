package org.example.agent_qr.rag.retriever;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ChromaDB 向量检索器，封装 ChromaDB 的相似度搜索与删除操作。
 * <p>
 * 通过 LangChain4j 的 ChromaEmbeddingStore 与 ChromaDB 交互，
 * 支持基于向量相似度的 top-K 检索和按文档 ID 删除向量。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class ChromaRetriever {

    @Value("${langchain4j.chroma.collection-name:enterprise_knowledge}")
    private String collectionName;

    /**
     * ChromaDB v2 API 的租户名（须与 {@code ChromaConfig} / langchain4j 的默认值一致）。
     */
    @Value("${langchain4j.chroma.tenant:default}")
    private String tenant;

    /**
     * ChromaDB v2 API 的数据库名（约束同 {@link #tenant}）。
     */
    @Value("${langchain4j.chroma.database:default}")
    private String database;

    /**
     * ChromaDB 基础地址（向量枚举用 REST 直连，{@code ChromaEmbeddingStore} 未暴露枚举能力）。
     */
    @Value("${langchain4j.chroma.base-url:http://localhost:8000}")
    private String baseUrl;

    /**
     * REST 调用超时（秒）。
     */
    @Value("${langchain4j.chroma.timeout-seconds:30}")
    private long timeoutSeconds = 30;

    /**
     * 枚举响应体的最大缓冲字节数。
     * <p>⚠️ WebClient 默认仅 256 KB（同批次 05 在 Ollama 侧踩到的坑），必须显式放大。</p>
     */
    @Value("${langchain4j.chroma.max-response-bytes:16777216}")
    private int maxResponseBytes = 16 * 1024 * 1024;

    /** 惰性构建的 REST 客户端（枚举能力用） */
    private volatile WebClient chromaWebClient;

    /** collection 名称 → ID 的缓存（由 {@link #resolveCollectionId()} 维护） */
    private volatile String cachedCollectionId;
    private volatile String cachedCollectionName;

    @Autowired(required = false)
    private ChromaEmbeddingStore chromaEmbeddingStore;

    /**
     * 相似度搜索，返回与查询向量最相似的 topK 个文档。
     *
     * @param queryEmbedding 查询文本的向量表示
     * @param topK           返回的最大结果数
     * @return 检索结果列表
     */
    public List<RetrievedDocument> similaritySearch(float[] queryEmbedding, int topK) {
        if (chromaEmbeddingStore == null) {
            log.warn("ChromaEmbeddingStore 未初始化，返回空检索结果");
            return new ArrayList<>();
        }

        try {
            Embedding embedding = new Embedding(queryEmbedding);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embedding)
                    .maxResults(topK)
                    .minScore(0.0)
                    .build();
            EmbeddingSearchResult<TextSegment> result = chromaEmbeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = result.matches();

            List<RetrievedDocument> documents = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                RetrievedDocument doc = new RetrievedDocument();
                doc.setDocumentId(match.embeddingId());
                doc.setContent(match.embedded().text());
                doc.setSimilarity(match.score());

                // 从元数据中提取切片ID和文档标题
                if (match.embedded().metadata() != null) {
                    String chunkIdStr = match.embedded().metadata().getString("chunk_id");
                    if (chunkIdStr != null) {
                        try {
                            doc.setChunkId(Long.valueOf(chunkIdStr));
                        } catch (NumberFormatException e) {
                            log.debug("chunk_id 元数据解析失败: {}", chunkIdStr);
                        }
                    }
                    String title = match.embedded().metadata().getString("document_title");
                    doc.setDocumentTitle(title != null ? title : "未命名文档");
                } else {
                    doc.setDocumentTitle("未命名文档");
                }

                documents.add(doc);
            }

            log.debug("相似度搜索完成，查询 TopK={}, 返回结果数={}", topK, documents.size());
            return documents;
        } catch (Exception e) {
            log.error("ChromaDB 相似度搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 根据文档 ID 删除对应的向量记录。
     * <p>
     * P1 基础实现：如果 chromaEmbeddingStore 为 null 则仅记录警告日志。
     * </p>
     *
     * @param documentId 文档 ID
     */
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            log.warn("documentId 为 null，跳过删除向量记录");
            return;
        }
        if (chromaEmbeddingStore == null) {
            log.warn("ChromaEmbeddingStore 未初始化，无法删除文档 ID={} 的向量", documentId);
            return;
        }

        try {
            Filter filter = MetadataFilterBuilder.metadataKey("document_id").isEqualTo(documentId.toString());
            chromaEmbeddingStore.removeAll(filter);
            log.info("已删除文档 ID={} 的向量记录", documentId);
        } catch (Exception e) {
            log.error("删除文档 ID={} 的向量记录失败", documentId, e);
        }
    }

    // ==================== P2 新增方法 ====================

    /**
     * ★ P2: 按 ChromaDB 向量 ID 批量物理删除。
     * <p>
     * 由 compensation 模块的 {@code DocumentDeleteServiceV2} 调用，
     * 实现 ChromaDB 端向量的物理删除。
     * </p>
     *
     * @param ids ChromaDB 向量 ID 列表
     */
    public void deleteByIds(List<String> ids) {
        if (chromaEmbeddingStore == null) {
            log.warn("ChromaEmbeddingStore 未初始化，跳过向量批量删除");
            return;
        }
        if (ids == null || ids.isEmpty()) {
            return;
        }

        try {
            chromaEmbeddingStore.removeAll(ids);
            log.info("ChromaDB 批量删除向量完成: count={}", ids.size());
        } catch (Exception e) {
            log.error("ChromaDB 批量删除向量失败: count={}", ids.size(), e);
            throw new RuntimeException("ChromaDB 批量删除失败", e);
        }
    }

    /**
     * ★ P2: 按元数据键值对删除向量记录。
     * <p>
     * 由 compensation 模块的 {@code OrphanVectorScanner} 调用，
     * 用于清理孤儿向量（MySQL 中已删除但 ChromaDB 中残留的记录）。
     * </p>
     *
     * @param metadataKey   元数据键名（如 "document_id"）
     * @param metadataValue 元数据值
     */
    public void deleteByMetadata(String metadataKey, String metadataValue) {
        if (chromaEmbeddingStore == null) {
            log.warn("ChromaEmbeddingStore 未初始化，无法按元数据删除向量: {}={}", metadataKey, metadataValue);
            return;
        }

        try {
            Filter filter = MetadataFilterBuilder.metadataKey(metadataKey).isEqualTo(metadataValue);
            chromaEmbeddingStore.removeAll(filter);
            log.info("已删除元数据 {}={} 的向量记录", metadataKey, metadataValue);
        } catch (Exception e) {
            log.error("按元数据删除向量失败: {}={}", metadataKey, metadataValue, e);
        }
    }

    // ==================== 批次 07 · 任务 7.0.11：向量 id 枚举能力 ====================

    /**
     * ChromaDB 中的一条向量记录（id + 关键元数据）。
     * <p>
     * ⚠️ <b>向量的 id 是 UUID，切片 id（chunkId）存在元数据的 {@code chunk_id} 键里</b>——
     * 二者不能直接比对。存量核对、幂等删除、孤儿向量扫描都必须经元数据。
     * </p>
     * <p>
     * 该形态供批次 08（孤儿向量扫描）复用：按 {@code chunkId} / {@code documentId} /
     * {@code datasourceId} 判定"Chroma 有而 MySQL 没有"的残留。
     * </p>
     *
     * @param vectorId      ChromaDB 向量 ID（UUID）
     * @param chunkId       元数据 {@code chunk_id} 解析出的切片 ID；缺失或非法时为 null
     * @param documentId    元数据 {@code document_id}；缺失时为 null
     * @param datasourceId  元数据 {@code datasource_id}；缺失时为 null
     * @param documentTitle 元数据 {@code document_title}；缺失时为 null
     */
    public record ChromaVectorRecord(String vectorId, Long chunkId, Long documentId,
                                     Long datasourceId, String documentTitle) {
    }

    /** 单次 {@code get} 拉取的最大条数（分页步长） */
    static final int ENUMERATE_PAGE_SIZE = 500;

    /** {@code where $in} 单次携带的 chunkId 上限（避免请求体过大） */
    static final int CHUNK_ID_FILTER_BATCH = 100;

    /**
     * 分页枚举 collection 中的向量记录（批次 07 · 任务 7.0.11）。
     *
     * @param limit  本页最多返回条数
     * @param offset 偏移量
     * @return 向量记录列表；ChromaDB 不可达时返回空列表并记 WARN
     */
    public List<ChromaVectorRecord> enumerateVectors(int limit, int offset) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try {
            String collectionId = resolveCollectionId();
            if (collectionId == null) {
                return new ArrayList<>();
            }
            Map<String, Object> body = Map.of(
                    "include", List.of("metadatas"),
                    "limit", limit,
                    "offset", offset);
            Map response = webClient().post()
                    .uri(collectionPath(collectionId) + "/get")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(timeoutSeconds));
            return toRecords(response);
        } catch (Exception e) {
            log.warn("ChromaDB 向量枚举失败（limit={}, offset={}）: {}", limit, offset, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 全量枚举 collection 中的向量记录（内部自动分页）。
     * <p>用于存量核对与孤儿扫描；分页拉取，不依赖"数据量小"的假设。</p>
     *
     * @return 全部向量记录
     */
    public List<ChromaVectorRecord> enumerateAllVectors() {
        List<ChromaVectorRecord> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<ChromaVectorRecord> page = enumerateVectors(ENUMERATE_PAGE_SIZE, offset);
            all.addAll(page);
            if (page.size() < ENUMERATE_PAGE_SIZE) {
                break;
            }
            offset += page.size();
        }
        log.info("ChromaDB 向量枚举完成: count={}", all.size());
        return all;
    }

    /**
     * 查询一批切片在 ChromaDB 中对应的向量 ID（幂等写入的前置查询，任务 7.0.15）。
     * <p>
     * <b>为什么必须查而不能只算</b>：历史向量（DLQ 重放、旧版本写入的）id 是随机 UUID，
     * 与新写入路径的确定性 id 不同；只有通过元数据 {@code chunk_id} 反查才能全部覆盖。
     * 查询失败时返回空 Map（调用方仍会用确定性 id 兜底删除），不抛异常、不阻断主流程。
     * </p>
     *
     * @param chunkIds 切片 ID 集合
     * @return chunkId → ChromaDB 向量 ID
     */
    public Map<Long, String> findVectorIdsByChunkIds(Collection<Long> chunkIds) {
        Map<Long, String> result = new HashMap<>();
        if (chunkIds == null || chunkIds.isEmpty()) {
            return result;
        }
        String collectionId;
        try {
            collectionId = resolveCollectionId();
        } catch (Exception e) {
            log.warn("ChromaDB collection 解析失败，跳过既有向量查询: {}", e.getMessage());
            return result;
        }
        if (collectionId == null) {
            return result;
        }

        List<String> ids = chunkIds.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        for (int i = 0; i < ids.size(); i += CHUNK_ID_FILTER_BATCH) {
            List<String> slice = ids.subList(i, Math.min(i + CHUNK_ID_FILTER_BATCH, ids.size()));
            try {
                Map<String, Object> body = Map.of(
                        "include", List.of("metadatas"),
                        "where", Map.of("chunk_id", Map.of("$in", slice)));
                Map response = webClient().post()
                        .uri(collectionPath(collectionId) + "/get")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block(Duration.ofSeconds(timeoutSeconds));
                for (ChromaVectorRecord record : toRecords(response)) {
                    if (record.chunkId() != null) {
                        result.put(record.chunkId(), record.vectorId());
                    }
                }
            } catch (Exception e) {
                log.warn("按 chunkId 查询 ChromaDB 向量失败（size={}）: {}", slice.size(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 本模块写入 ChromaDB 时使用的确定性向量 ID（由 chunkId 派生）。
     * <p>
     * 用 UUIDv3（MD5 命名空间）而非随机 UUID：同一切片重复向量化得到<b>同一个 id</b>，
     * 存量核对与孤儿扫描无需额外映射表即可复算；配合"先 removeAll 再 addAll"，
     * 重跑 / DLQ 重放 / 失败重试都不会产生重复向量。
     * </p>
     *
     * @param chunkId 切片 ID
     * @return 向量 ID（UUID 字符串）
     */
    public static String vectorIdFor(Long chunkId) {
        return UUID.nameUUIDFromBytes(
                ("agent-qr-chunk-" + chunkId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ==================== 内部辅助 ====================

    /**
     * 由 ChromaDB 响应体解析向量记录（{@code ids} 与 {@code metadatas} 下标对齐）。
     *
     * @param response ChromaDB {@code get} 响应
     * @return 向量记录列表
     */
    @SuppressWarnings("unchecked")
    private static List<ChromaVectorRecord> toRecords(Map response) {
        List<ChromaVectorRecord> records = new ArrayList<>();
        if (response == null || !(response.get("ids") instanceof List<?> ids)) {
            return records;
        }
        List<Object> metadatas = response.get("metadatas") instanceof List<?> list
                ? (List<Object>) list : List.of();
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> metadata =
                    (i < metadatas.size() && metadatas.get(i) instanceof Map<?, ?> map)
                            ? (Map<String, Object>) map : Map.of();
            records.add(new ChromaVectorRecord(
                    Objects.toString(ids.get(i), null),
                    parseLong(metadata.get("chunk_id")),
                    parseLong(metadata.get("document_id")),
                    parseLong(metadata.get("datasource_id")),
                    metadata.get("document_title") == null
                            ? null : metadata.get("document_title").toString()));
        }
        return records;
    }

    private static Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析 collection 名称对应的 collection ID。
     * <p>
     * ⚠️ ChromaDB v2 的 {@code /get} 只接受 collection <b>ID</b>（传名称报
     * {@code Collection ID is not a valid UUIDv4}），因此必须先按名称解析出 ID。
     * 解析结果缓存，collection 名变化时自动失效。
     * </p>
     *
     * @return collection ID；不可用时返回 null
     */
    private String resolveCollectionId() {
        String cached = cachedCollectionId;
        if (cached != null && collectionName.equals(cachedCollectionName)) {
            return cached;
        }
        Map response = webClient().get()
                .uri(collectionsPath() + "/{name}", collectionName)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(timeoutSeconds));
        String id = response == null ? null : Objects.toString(response.get("id"), null);
        cachedCollectionId = id;
        cachedCollectionName = collectionName;
        return id;
    }

    /**
     * 构造 collection 集合资源路径（含 tenant/database 段，与 {@code ChromaConfig} 保持一致）。
     *
     * @return 形如 {@code /api/v2/tenants/default/databases/default/collections}
     */
    private String collectionsPath() {
        return String.format("/api/v2/tenants/%s/databases/%s/collections", tenant, database);
    }

    private String collectionPath(String collectionId) {
        return collectionsPath() + "/" + collectionId;
    }

    /**
     * 惰性构建 WebClient。
     * <p>
     * ⚠️ 必须显式放大缓冲上限：WebClient 默认仅 256 KB，与批次 05 在
     * {@code OllamaEmbeddingProvider} 上踩到的坑同源——collection 较大时
     * {@code get} 的元数据响应会超限抛 {@code DataBufferLimitException}。
     * </p>
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
                            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes))
                            .build();
                }
                client = chromaWebClient;
            }
        }
        return client;
    }
}
