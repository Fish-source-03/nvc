package org.example.agent_qr.web.scheduler;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.DlqMessageMapper;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DLQ 重放闭环实库/实服务验证（批次 08 · 任务 8.5，R24）。
 * <p>
 * <b>为什么必须真库真服务</b>：R24 ② 的症状是"不报错但静默写入重复/孤儿向量"——
 * 随机 UUID 单条 add 在 Mock 下永远"成功"，只有对真实 ChromaDB 校验
 * <b>向量 id 集合</b>才能证明幂等；R24 ① 的"状态永久停在 INDEXED"同样只有回读 MySQL 才能证实。
 * </p>
 * <p>
 * <b>三个断言对应三处问题</b>：
 * <ol>
 *   <li>{@link #dlqReplay_shouldReachReady_withDeterministicVectorId()} —— 重放后切片 READY，
 *       且 {@code chroma_id} 与 ChromaDB 向量 id 均为 UUIDv3 确定性值；</li>
 *   <li>{@link #dlqReplay_shouldBeIdempotent_withoutOrphanVectors()} —— 把切片状态回退为
 *       INDEXED（模拟"向量已存在、状态未回写"的现场）后重复重放：向量 id 集合不变、
 *       历史随机 UUID 向量被清掉、不新增任何向量；</li>
 *   <li>{@link #dlqReplay_shouldNotTouchReadyChunks()} —— 已 READY 的切片不再被重新向量化
 *       （ChromaDB 无新写入、向量不变）。</li>
 * </ol>
 * </p>
 * <p>
 * <b>默认不执行</b>：会写真实 MySQL / ChromaDB。需显式
 * {@code -Dagent-qr.live.dlq=true} 开启，未开启时以 Assumption 跳过。
 * 测试数据（切片 / 向量 / 死信）在 {@code @AfterAll} 全部清理，
 * 并复算 ChromaDB 向量总数，确保回到既有基线（19 条）。
 * </p>
 *
 * @author agent-qr
 */
class DlqVectorizationReplayLiveChromaTest {

    private static final String COLLECTION_NAME = "enterprise_knowledge";
    private static final String CHROMA_BASE_URL = "http://localhost:8000";
    /** 测试专用文档 ID（不与任何真实文档冲突） */
    private static final long TEST_DOCUMENT_ID = 998802L;
    /** 与既有 collection 的维度一致（dim 2560 / cosine） */
    private static final int VECTOR_DIMENSION = 2560;
    /** 既有基线（外部 19 条向量），测试前后必须一致 */
    private static final int EXPECTED_BASELINE_VECTORS = 19;

    private static SqlSession sqlSession;
    private static ChunkMapper chunkMapper;
    private static DocumentMapper documentMapper;
    private static DlqMessageMapper dlqMessageMapper;
    private static ChromaRetriever chromaRetriever;
    private static ChromaEmbeddingStore chromaEmbeddingStore;
    private static DeadLetterQueue deadLetterQueue;
    private static Connection rawConnection;
    private static DlqRetryScheduler scheduler;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("agent-qr.live.dlq"),
                "DLQ 重放闭环实库测试：需显式 -Dagent-qr.live.dlq=true 开启");

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
        factory.getConfiguration().addMapper(DlqMessageMapper.class);
        sqlSession = factory.openSession(true);
        chunkMapper = sqlSession.getMapper(ChunkMapper.class);
        documentMapper = sqlSession.getMapper(DocumentMapper.class);
        dlqMessageMapper = sqlSession.getMapper(DlqMessageMapper.class);

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
        ReflectionTestUtils.setField(chromaRetriever, "chromaEmbeddingStore", chromaEmbeddingStore);

        Assumptions.assumeTrue(collectionReachable(), "ChromaDB 不可达或 collection 不存在，跳过");

        // 向量化以桩替换：本测试验证的是"重放写入与状态回写"，不依赖 Ollama 的响应质量
        BatchEmbeddingService batchEmbeddingService = mock(BatchEmbeddingService.class);
        when(batchEmbeddingService.submit(any()))
                .thenAnswer(invocation -> java.util.concurrent.CompletableFuture.completedFuture(fixedVector()));

        deadLetterQueue = new DeadLetterQueue();
        ReflectionTestUtils.setField(deadLetterQueue, "dlqMessageMapper", dlqMessageMapper);
        ReflectionTestUtils.setField(deadLetterQueue, "maxRetries", 4);
        ReflectionTestUtils.setField(deadLetterQueue, "backoffBase", 3);

        scheduler = new DlqRetryScheduler();
        ReflectionTestUtils.setField(scheduler, "deadLetterQueue", deadLetterQueue);
        ReflectionTestUtils.setField(scheduler, "dlqMessageMapper", dlqMessageMapper);
        ReflectionTestUtils.setField(scheduler, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(scheduler, "documentMapper", documentMapper);
        ReflectionTestUtils.setField(scheduler, "chromaEmbeddingStore", chromaEmbeddingStore);
        ReflectionTestUtils.setField(scheduler, "chromaRetriever", chromaRetriever);
        ReflectionTestUtils.setField(scheduler, "batchEmbeddingService", batchEmbeddingService);
        ReflectionTestUtils.setField(scheduler, "deleteTaskMapper", null);
        ReflectionTestUtils.setField(scheduler, "documentDeleteServiceV2", null);
        ReflectionTestUtils.setField(scheduler, "fileStorageService", null);

        cleanup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlSession != null) {
            cleanup();
            int total = totalVectorCount();
            System.out.printf("[baseline] ChromaDB 向量总数 = %d（期望 %d）%n", total, EXPECTED_BASELINE_VECTORS);
            assertThat(total)
                    .as("测试产生的向量必须全部清理，回到既有基线")
                    .isEqualTo(EXPECTED_BASELINE_VECTORS);
            sqlSession.close();
        }
        if (rawConnection != null) {
            rawConnection.close();
        }
    }

    // ==================== ① 状态流转 + 确定性 id ====================

    @Test
    @DisplayName("★ R24 ①：DLQ 重放后切片 status=READY，chroma_id 与 ChromaDB 向量 id 均为确定性 UUIDv3")
    void dlqReplay_shouldReachReady_withDeterministicVectorId() {
        List<Chunk> chunks = seedChunks(2, Chunk.STATUS_INDEXED);
        enqueueReplayMessage();

        scheduler.retryDeadLetters();

        for (Chunk chunk : chunks) {
            Chunk reloaded = chunkMapper.selectById(chunk.getId());
            assertThat(reloaded.getStatus())
                    .as("修复前：重放只写 chroma_id，status 永久停在 INDEXED")
                    .isEqualTo(Chunk.STATUS_READY);
            assertThat(reloaded.getChromaId()).isEqualTo(ChromaRetriever.vectorIdFor(chunk.getId()));
        }

        List<ChromaRetriever.ChromaVectorRecord> vectors = vectorsOfChunks(chunks);
        assertThat(vectors).hasSize(2);
        for (Chunk chunk : chunks) {
            assertThat(vectors).anySatisfy(record -> {
                assertThat(record.chunkId()).isEqualTo(chunk.getId());
                assertThat(record.vectorId()).isEqualTo(ChromaRetriever.vectorIdFor(chunk.getId()));
                assertThat(UUID.fromString(record.vectorId()).version()).isEqualTo(3);
            });
        }

        assertThat(dlqMessageMapper.selectPendingRetries(LocalDateTime.now().plusHours(1)))
                .as("重放成功 → 死信消息被删除（退避机制未被绕过）")
                .noneMatch(m -> Long.valueOf(TEST_DOCUMENT_ID).equals(m.getDocumentId()));
    }

    // ==================== ② 幂等：重复重放不产生重复/孤儿向量 ====================

    @Test
    @DisplayName("★ R24 ②：重复重放后 ChromaDB 向量 id 集合不变，且历史随机 UUID 向量被清理")
    void dlqReplay_shouldBeIdempotent_withoutOrphanVectors() {
        List<Chunk> chunks = seedChunks(1, Chunk.STATUS_INDEXED);
        Chunk chunk = chunks.get(0);

        // 现场还原：ChromaDB 里已有一条"旧路径"写入的随机 UUID 向量（元数据 chunk_id 指向该切片）
        String legacyRandomId = UUID.randomUUID().toString();
        chromaEmbeddingStore.addAll(
                List.of(legacyRandomId),
                List.of(new Embedding(fixedVector())),
                List.of(TextSegment.from(chunk.getContent(), new Metadata(java.util.Map.of(
                        "chunk_id", chunk.getId().toString(),
                        "document_id", String.valueOf(TEST_DOCUMENT_ID),
                        "document_title", "批08-DLQ重放测试")))));
        assertThat(vectorsOfChunks(chunks)).hasSize(1);

        // 第一次重放
        enqueueReplayMessage();
        scheduler.retryDeadLetters();
        List<String> afterFirst = vectorIdsOfChunks(chunks);
        assertThat(afterFirst).containsExactly(ChromaRetriever.vectorIdFor(chunk.getId()));

        // 模拟 R24 ① 的现场：向量已落库、状态却仍是 INDEXED → 再次重放
        chunkMapper.updateStatus(chunk.getId(), Chunk.STATUS_INDEXED);
        enqueueReplayMessage();
        scheduler.retryDeadLetters();

        List<String> afterSecond = vectorIdsOfChunks(chunks);
        assertThat(afterSecond)
                .as("重复重放必须得到同一 id 集合（确定性 id + 先删后写），不得新增随机 UUID 向量")
                .isEqualTo(afterFirst)
                .doesNotContain(legacyRandomId);
        assertThat(chunkMapper.selectById(chunk.getId()).getStatus()).isEqualTo(Chunk.STATUS_READY);
    }

    // ==================== ③ 只处理未就绪切片 ====================

    @Test
    @DisplayName("★ R24 ③：已 READY 的切片不被重新处理（ChromaDB 无新写入、向量不变）")
    void dlqReplay_shouldNotTouchReadyChunks() {
        List<Chunk> chunks = seedChunks(1, Chunk.STATUS_INDEXED);
        Chunk chunk = chunks.get(0);

        enqueueReplayMessage();
        scheduler.retryDeadLetters();

        String deterministicId = ChromaRetriever.vectorIdFor(chunk.getId());
        List<String> before = vectorIdsOfChunks(chunks);

        // 切片此时已 READY：再次重放不应产生任何写入（幂等闸门）
        enqueueReplayMessage();
        scheduler.retryDeadLetters();

        List<String> after = vectorIdsOfChunks(chunks);
        assertThat(after).isEqualTo(before).containsExactly(deterministicId);
        assertThat(chunkMapper.selectById(chunk.getId()).getStatus()).isEqualTo(Chunk.STATUS_READY);
        assertThat(after)
                .as("整文档重灌会表现为重复向量；这里必须仍只有 1 条")
                .hasSize(1);
        System.out.printf("[idempotent] 已 READY 切片重放前后向量 id 集合不变: %s%n", after);
    }

    // ==================== 辅助：造数与清理 ====================

    private static List<Chunk> seedChunks(int count, String status) {
        cleanup();
        List<Chunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Chunk chunk = new Chunk();
            chunk.setDocumentId(TEST_DOCUMENT_ID);
            chunk.setChunkIndex(i);
            chunk.setContent("zz_b08_dlq_replay_content_" + i);
            chunk.setCharCount(30);
            chunk.setChromaId("pending");
            chunk.setStatus(status);
            chunk.setDeleted(0);
            chunkMapper.insert(chunk);
            chunks.add(chunk);
        }
        return chunks;
    }

    private static void enqueueReplayMessage() {
        DlqMessage msg = new DlqMessage();
        msg.setEventType(DlqMessage.EVENT_EMBED);
        msg.setDocumentId(TEST_DOCUMENT_ID);
        msg.setPayload(String.format("{\"documentId\":%d}", TEST_DOCUMENT_ID));
        msg.setRetryCount(0);
        msg.setStatus(DlqMessage.STATUS_PENDING);
        msg.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        msg.setCreateTime(LocalDateTime.now());
        dlqMessageMapper.insert(msg);
    }

    private static List<ChromaRetriever.ChromaVectorRecord> vectorsOfChunks(List<Chunk> chunks) {
        List<Long> ids = chunks.stream().map(Chunk::getId).toList();
        return chromaRetriever.enumerateAllVectors().stream()
                .filter(record -> record.chunkId() != null && ids.contains(record.chunkId()))
                .toList();
    }

    private static List<String> vectorIdsOfChunks(List<Chunk> chunks) {
        return vectorsOfChunks(chunks).stream()
                .map(ChromaRetriever.ChromaVectorRecord::vectorId)
                .sorted()
                .toList();
    }

    private static float[] fixedVector() {
        float[] vector = new float[VECTOR_DIMENSION];
        for (int i = 0; i < VECTOR_DIMENSION; i++) {
            vector[i] = (float) Math.sin(i * 0.01);
        }
        return vector;
    }

    private static int totalVectorCount() {
        try {
            List<ChromaRetriever.ChromaVectorRecord> all = chromaRetriever.enumerateAllVectors();
            return all.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** ChromaDB collection 是否可达（HTTP 直连校验，避免与"枚举结果为空"混淆） */
    private static boolean collectionReachable() {
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpResponse<String> response = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(URI.create(CHROMA_BASE_URL + "/api/v2/tenants/default/databases/default/collections/"
                                    + COLLECTION_NAME))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清理测试数据：向量（先按元数据 document_id 兜底枚举）+ 切片 + 死信。
     * <p>兜底枚举保证即使切片行已被清掉、遗留的测试向量也不会成为新基线。</p>
     */
    private static void cleanup() {
        if (rawConnection == null) {
            return;
        }
        try {
            List<String> vectorIds = new ArrayList<>(chromaRetriever.enumerateAllVectors().stream()
                    .filter(record -> String.valueOf(TEST_DOCUMENT_ID).equals(String.valueOf(record.documentId())))
                    .map(ChromaRetriever.ChromaVectorRecord::vectorId)
                    .toList());
            List<Object> chunkIds = new ArrayList<>();
            try (PreparedStatement ps = rawConnection.prepareStatement(
                    "SELECT id FROM kb_chunk WHERE document_id = " + TEST_DOCUMENT_ID);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    chunkIds.add(rs.getLong(1));
                }
            }
            for (Object id : chunkIds) {
                vectorIds.addAll(chromaRetriever.findVectorIdsByChunkIds(List.of((Long) id)).values());
            }
            if (!vectorIds.isEmpty()) {
                chromaEmbeddingStore.removeAll(vectorIds);
            }
            try (PreparedStatement ps = rawConnection.prepareStatement(
                    "DELETE FROM kb_chunk WHERE document_id = " + TEST_DOCUMENT_ID)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = rawConnection.prepareStatement(
                    "DELETE FROM dlq_message WHERE document_id = " + TEST_DOCUMENT_ID)) {
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("测试数据清理失败", e);
        }
    }

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
