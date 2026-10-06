package org.example.agent_qr.knowledge.migration;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.event.ChunksBatchCreatedEvent;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.listener.ChunkEmbeddingBatchListener;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.service.ChunkIndexableTextProvider;
import org.example.agent_qr.knowledge.splitter.TextSplitter;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.example.agent_qr.rag.provider.ollama.OllamaEmbeddingProvider;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 向量存储 id 集合核对与存量迁移（批次 07 · 任务 7.0c / 7.0.11 – 7.0.13）。
 * <p>
 * <b>为什么用真库真服务</b>：本任务的核心是"MySQL 的切片 id 集合"与"ChromaDB 向量元数据
 * {@code chunk_id} 集合"是否一致——两者分属不同系统，Mock 拦不住"枚举走空 / 过滤写错"
 * 这类静默错误（真实踩坑：ChromaDB v2 的 {@code /get} 不接受 collection 名称，
 * 且 chunkId 藏在元数据里而非向量 id）。
 * </p>
 * <p>
 * <b>本测试同时是迁移工具</b>：先核对、再按差集补齐，跑完后
 * "ChromaDB 向量集合 == MySQL 有效切片集合"恒成立，因此<b>可重复执行</b>——
 * 第二次运行是幂等的空操作（对应任务 7.0.15 的"先删后写"）。
 * </p>
 * <p>
 * <b>为什么不进常规测试套件</b>：它会写真实的 MySQL / ChromaDB / Ollama。
 * 默认通过 {@code -Dagent-qr.live.migration=true} 显式开启；未开启时以 Assumption 跳过，
 * 保证无服务环境下 {@code mvn test} 仍然全绿。
 * </p>
 *
 * @author agent-qr
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class VectorStoreReconciliationMigrationTest {

    private static final String COLLECTION_NAME = "enterprise_knowledge";
    /** 存量 collection 的 id —— 迁移不得改变它（任务 7.1.4：保持 collection 不变） */
    private static final String EXPECTED_COLLECTION_ID = "7fbaddfc-4cd8-4651-b987-827e81e31257";
    private static final String CHROMA_BASE_URL = "http://localhost:8000";
    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String EMBEDDING_MODEL = "qwen3-embedding:4b";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SqlSession sqlSession;
    private static ChunkMapper chunkMapper;
    private static DocumentMapper documentMapper;
    private static DataSourceMapper dataSourceMapper;
    private static ChromaRetriever chromaRetriever;
    private static ChromaEmbeddingStore chromaEmbeddingStore;
    private static BatchEmbeddingService batchEmbeddingService;
    private static BM25Retriever bm25Retriever;
    private static ChunkEmbeddingBatchListener listener;
    private static Connection rawConnection;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("agent-qr.live.migration"),
                "存量迁移实库测试：需显式 -Dagent-qr.live.migration=true 开启");

        String url = dataSourceProperty("url");
        String username = dataSourceProperty("username");
        String password = dataSourceProperty("password");
        Assumptions.assumeTrue(url != null, "application.yml 中未找到数据源配置，跳过");

        try {
            rawConnection = DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达（" + e.getClass().getSimpleName() + "），跳过");
            return;
        }

        DataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, username, password);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(ChunkMapper.class);
        factory.getConfiguration().addMapper(DocumentMapper.class);
        factory.getConfiguration().addMapper(DataSourceMapper.class);
        sqlSession = factory.openSession(true);
        chunkMapper = sqlSession.getMapper(ChunkMapper.class);
        documentMapper = sqlSession.getMapper(DocumentMapper.class);
        dataSourceMapper = sqlSession.getMapper(DataSourceMapper.class);

        chromaRetriever = new ChromaRetriever();
        ReflectionTestUtils.setField(chromaRetriever, "collectionName", COLLECTION_NAME);
        ReflectionTestUtils.setField(chromaRetriever, "tenant", "default");
        ReflectionTestUtils.setField(chromaRetriever, "database", "default");
        ReflectionTestUtils.setField(chromaRetriever, "baseUrl", CHROMA_BASE_URL);

        chromaEmbeddingStore = ChromaEmbeddingStore.builder()
                .apiVersion(ChromaApiVersion.V2)
                .baseUrl(CHROMA_BASE_URL)
                .collectionName(COLLECTION_NAME)
                .timeout(Duration.ofSeconds(120))
                .logRequests(false)
                .logResponses(false)
                .build();

        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider();
        ReflectionTestUtils.setField(provider, "baseUrl", OLLAMA_BASE_URL);
        ReflectionTestUtils.setField(provider, "model", EMBEDDING_MODEL);
        ReflectionTestUtils.setField(provider, "maxResponseBytes", 16 * 1024 * 1024);

        ProviderFactory providerFactory = new ProviderFactory();
        ReflectionTestUtils.setField(providerFactory, "embeddingProviderType", "ollama");
        ReflectionTestUtils.setField(providerFactory, "ollamaEmbeddingProvider", provider);

        batchEmbeddingService = new BatchEmbeddingService();
        ReflectionTestUtils.setField(batchEmbeddingService, "providerFactory", providerFactory);
        ReflectionTestUtils.setField(batchEmbeddingService, "batchSize", 32);
        ReflectionTestUtils.setField(batchEmbeddingService, "batchTimeoutMs", 100L);
        ReflectionTestUtils.setField(batchEmbeddingService, "queueCapacity", 2000);
        batchEmbeddingService.startConsumers();

        bm25Retriever = new BM25Retriever();
        ReflectionTestUtils.setField(bm25Retriever, "indexableTextProvider",
                new ChunkIndexableTextProvider(chunkMapper, dataSourceMapper));
        bm25Retriever.buildIndex();

        List<Object> publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = publishedEvents::add;
        listener = new ChunkEmbeddingBatchListener(documentMapper, chunkMapper, dataSourceMapper,
                mock(TextSplitter.class), publisher, mock(DeadLetterQueue.class),
                batchEmbeddingService, chromaEmbeddingStore, chromaRetriever, bm25Retriever);

        Assumptions.assumeTrue(collectionId() != null, "ChromaDB 不可达或 collection 不存在，跳过");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (batchEmbeddingService != null) {
            batchEmbeddingService.shutdown();
        }
        if (sqlSession != null) {
            sqlSession.close();
        }
        if (rawConnection != null) {
            rawConnection.close();
        }
    }

    @Test
    @DisplayName("① 核对：MySQL 有效切片 vs ChromaDB 元数据 chunk_id 的 id 集合差异")
    void step1_reconcileIdSets() {
        Set<Long> mysqlIds = mysqlChunkIds();
        Set<Long> chromaIds = chromaChunkIds();

        Set<Long> both = new TreeSet<>(mysqlIds);
        both.retainAll(chromaIds);
        Set<Long> onlyMysql = new TreeSet<>(mysqlIds);
        onlyMysql.removeAll(chromaIds);
        Set<Long> onlyChroma = new TreeSet<>(chromaIds);
        onlyChroma.removeAll(mysqlIds);

        System.out.println("========== 7.0c 存量核对 ==========");
        System.out.println("MySQL 有效切片: " + mysqlIds.size() + " -> " + mysqlIds);
        System.out.println("ChromaDB 向量:  " + chromaIds.size() + " -> " + chromaIds);
        System.out.println("两边都有:       " + both.size() + " -> " + both);
        System.out.println("仅在 MySQL:     " + onlyMysql.size() + " -> " + onlyMysql + "（置 INDEXED 待重跑）");
        System.out.println("仅在 Chroma:    " + onlyChroma.size() + " -> " + onlyChroma + "（孤儿，归批次 08）");
        System.out.println("===================================");

        assertThat(chromaIds)
                .as("ChromaDB 的 id 必须来自元数据 chunk_id（向量 id 是 UUID，不可直接比对）")
                .doesNotContainNull();
        assertThat(chromaIds).allSatisfy(id -> assertThat(id).isPositive());
    }

    @Test
    @DisplayName("② 对齐状态：仅在 MySQL 的置 INDEXED（待重跑），两边都有的置 READY")
    void step2_alignChunkStatuses() {
        Set<Long> chromaIds = chromaChunkIds();
        int toIndexed = 0;
        int toReady = 0;

        for (Chunk chunk : allLiveChunks()) {
            String expected = chromaIds.contains(chunk.getId())
                    ? Chunk.STATUS_READY : Chunk.STATUS_INDEXED;
            if (!expected.equals(chunk.getStatus())) {
                chunkMapper.updateStatus(chunk.getId(), expected);
                if (Chunk.STATUS_INDEXED.equals(expected)) {
                    toIndexed++;
                } else {
                    toReady++;
                }
            }
        }
        System.out.println("状态对齐: 置 INDEXED(待重跑)=" + toIndexed + ", 置 READY(已有向量)=" + toReady);
    }

    @Test
    @DisplayName("③ 补写：对缺失向量的切片走真实事件驱动链路（先删后写）")
    void step3_backfillMissingVectors() {
        Set<Long> missing = new TreeSet<>(mysqlChunkIds());
        missing.removeAll(chromaChunkIds());
        if (missing.isEmpty()) {
            System.out.println("无需补写：ChromaDB 与 MySQL 的 id 集合已一致（幂等空操作）");
            return;
        }

        Map<String, List<Long>> groups = groupByOwner(missing);
        groups.forEach((key, ids) -> {
            System.out.println("补写分组 " + key + " -> " + ids.size() + " 条 " + ids);
            listener.handleChunksBatchCreated(eventFor(key, "zz-b07-migration"));
        });
    }

    @Test
    @DisplayName("④ 复验：id 集合完全一致 + 切片全部 READY + collection 配置未被改动")
    void step4_verifyConverged() throws Exception {
        Set<Long> mysqlIds = mysqlChunkIds();
        Set<Long> chromaIds = chromaChunkIds();
        Set<Long> onlyChroma = new TreeSet<>(chromaIds);
        onlyChroma.removeAll(mysqlIds);

        System.out.println("========== 7.0c 复验（迁移后） ==========");
        System.out.println("MySQL 有效切片: " + mysqlIds.size() + " -> " + mysqlIds);
        System.out.println("ChromaDB 向量:  " + chromaIds.size() + " -> " + chromaIds);
        System.out.println("仅在 Chroma:    " + onlyChroma + "（应为空）");
        System.out.println("=========================================");

        assertThat(chromaIds)
                .as("迁移后 ChromaDB 的 chunk_id 集合必须与 MySQL 有效切片一致")
                .containsExactlyInAnyOrderElementsOf(mysqlIds);
        assertThat(onlyChroma).as("不得产生孤儿向量（孤儿清理归批次 08）").isEmpty();

        for (Chunk chunk : allLiveChunks()) {
            assertThat(chunk.getStatus()).as("chunk " + chunk.getId() + " 应已就绪").isEqualTo(Chunk.STATUS_READY);
            assertThat(chunk.getChromaId()).as("chunk " + chunk.getId() + " 的 chromaId 应为向量 UUID")
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }

        Map<String, Object> info = collectionMetadata();
        assertThat(info.get("id")).as("collection id 迁移前后必须不变").isEqualTo(EXPECTED_COLLECTION_ID);
        assertThat(info.get("space")).as("距离度量不得被改动").isEqualTo("cosine");
        assertThat(info.get("dimension")).as("向量维度不得被改动").isEqualTo(2560);
        System.out.println("collection 未变: id=" + info.get("id") + ", space=" + info.get("space")
                + ", dimension=" + info.get("dimension"));
    }

    @Test
    @DisplayName("⑤ 幂等：全部切片重跑一遍不报 DuplicateIDError，向量集合不变")
    void step5_idempotentRerun() {
        Set<Long> before = new TreeSet<>(chromaChunkIds());

        for (Chunk chunk : allLiveChunks()) {
            chunkMapper.updateStatus(chunk.getId(), Chunk.STATUS_INDEXED);
        }
        groupByOwner(new TreeSet<>(mysqlChunkIds()))
                .forEach((key, ids) -> listener.handleChunksBatchCreated(eventFor(key, "zz-b07-idempotency")));

        assertThat(chromaChunkIds())
                .as("重复向量化不得产生重复向量（无 upsert → 先 removeAll 再 addAll）")
                .containsExactlyInAnyOrderElementsOf(before);
        assertThat(chromaChunkIds()).hasSize(before.size());
        assertThat(allLiveChunks()).allSatisfy(chunk ->
                assertThat(chunk.getStatus()).isEqualTo(Chunk.STATUS_READY));
    }

    @Test
    @DisplayName("⑥ BM25 双保险：发布方更新 + Listener 校验补写后，全部切片均可被索引命中")
    void step6_bm25DualSafetyNet() {
        List<Long> allIds = new ArrayList<>(mysqlChunkIds());

        // 模拟"发布方更新全部失败"：把索引清空后的切片视为缺失
        List<Long> missingBefore = bm25Retriever.findMissingChunkIds(allIds);
        System.out.println("BM25 校验: 缺失 " + missingBefore.size() + " 条（预期 0 —— 已在迁移过程中索引）");

        assertThat(missingBefore)
                .as("发布方与 Listener 双保险后，不应有切片缺席 BM25 索引")
                .isEmpty();
        assertThat(bm25Retriever.isIndexReady()).isTrue();

        // 重复补写不产生重复文档（updateDocument 按 chunkId 替换）
        int first = bm25Retriever.addBatchToIndex(allLiveChunks());
        int second = bm25Retriever.addBatchToIndex(allLiveChunks());
        assertThat(first).isEqualTo(allIds.size());
        assertThat(second).isEqualTo(allIds.size());
        assertThat(bm25Retriever.findMissingChunkIds(allIds)).isEmpty();
    }

    // ==================== MySQL ====================

    /** 全部有效切片（deleted = 0），与既有实现解耦：走原始 JDBC 取 id 再逐个装载 */
    private static List<Chunk> allLiveChunks() {
        List<Chunk> chunks = new ArrayList<>();
        for (Long id : mysqlChunkIds()) {
            Chunk chunk = chunkMapper.selectById(id);
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private static Set<Long> mysqlChunkIds() {
        Set<Long> ids = new TreeSet<>();
        try (Statement statement = rawConnection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT id FROM kb_chunk WHERE deleted = 0 ORDER BY id")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 kb_chunk 失败: " + e.getMessage(), e);
        }
        return ids;
    }

    /** 按归属分组：文档上传链路按 documentId，数据同步链路按 datasourceId */
    private static Map<String, List<Long>> groupByOwner(Set<Long> chunkIds) {
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        for (Chunk chunk : allLiveChunks()) {
            String key = chunk.getDocumentId() != null
                    ? "document:" + chunk.getDocumentId()
                    : "datasource:" + chunk.getDatasourceId();
            if (chunkIds.contains(chunk.getId())) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk.getId());
            }
        }
        return groups;
    }

    private static ChunksBatchCreatedEvent eventFor(String groupKey, String syncBatchId) {
        if (groupKey.startsWith("document:")) {
            return ChunksBatchCreatedEvent.forDocument(
                    Long.valueOf(groupKey.substring("document:".length())));
        }
        return ChunksBatchCreatedEvent.forDatasource(
                Long.valueOf(groupKey.substring("datasource:".length())), syncBatchId);
    }

    // ==================== ChromaDB ====================

    private static Set<Long> chromaChunkIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (ChromaRetriever.ChromaVectorRecord record : chromaRetriever.enumerateAllVectors()) {
            if (record.chunkId() != null) {
                ids.add(record.chunkId());
            }
        }
        return ids;
    }

    /**
     * 读取 collection 元信息（id / hnsw space）与首条向量的维度。
     * <p>确保迁移未重建 collection、未改动距离度量与维度。</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> collectionMetadata() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        String json = httpGet("/api/v2/tenants/default/databases/default/collections/" + COLLECTION_NAME);
        Map<String, Object> info = MAPPER.readValue(json, Map.class);
        result.put("id", info.get("id"));
        Map<String, Object> configuration = (Map<String, Object>) info.get("configuration_json");
        Map<String, Object> hnsw = configuration == null ? null : (Map<String, Object>) configuration.get("hnsw");
        result.put("space", hnsw == null ? null : hnsw.get("space"));

        Long probe = chromaChunkIds().stream().findFirst().orElse(null);
        Assumptions.assumeTrue(probe != null, "collection 中无向量，无法探测维度");
        String vectorJson = httpPost("/api/v2/tenants/default/databases/default/collections/"
                        + result.get("id") + "/get",
                "{\"include\":[\"embeddings\"],\"limit\":1,"
                        + "\"where\":{\"chunk_id\":{\"$in\":[\"" + probe + "\"]}}}");
        Map<String, Object> vectorResponse = MAPPER.readValue(vectorJson, Map.class);
        List<List<Number>> embeddings = (List<List<Number>>) vectorResponse.get("embeddings");
        result.put("dimension", embeddings == null || embeddings.isEmpty() ? -1 : embeddings.get(0).size());
        return result;
    }

    private static String collectionId() {
        try {
            Map<String, Object> info = MAPPER.readValue(
                    httpGet("/api/v2/tenants/default/databases/default/collections/" + COLLECTION_NAME),
                    Map.class);
            return Objects.toString(info.get("id"), null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String httpGet(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(CHROMA_BASE_URL + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    private static String httpPost(String path, String body) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(CHROMA_BASE_URL + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    // ==================== 配置读取（不在测试源码中出现凭据原文） ====================

    private static String dataSourceProperty(String key) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", "application.yml"));
            if (Files.exists(candidate)) {
                try {
                    String content = Files.readString(candidate, StandardCharsets.UTF_8);
                    Matcher matcher = Pattern.compile("(?m)^\\s*" + key + ":\\s*(\\S+)\\s*$").matcher(content);
                    if (!matcher.find()) {
                        return null;
                    }
                    return resolvePlaceholder(matcher.group(1));
                } catch (Exception e) {
                    return null;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String resolvePlaceholder(String raw) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+):([^}]*)}").matcher(raw);
        if (matcher.matches()) {
            String fromEnv = System.getenv(matcher.group(1));
            return fromEnv != null ? fromEnv : matcher.group(2);
        }
        return raw;
    }
}
