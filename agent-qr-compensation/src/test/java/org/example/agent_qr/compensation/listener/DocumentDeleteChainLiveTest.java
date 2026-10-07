package org.example.agent_qr.compensation.listener;

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
import org.example.agent_qr.common.event.DocumentDeleteRequestedEvent;
import org.example.agent_qr.compensation.mapper.DeleteTaskMapper;
import org.example.agent_qr.compensation.service.DocumentDeleteServiceV2;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.service.FileStorageService;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 删除链路端到端一致性实库/实服务验证（批次 08 批次验收：MySQL 切片软删 / ChromaDB 向量删除 /
 * 磁盘文件删除三者一致）。
 * <p>
 * 用真实的 {@link DocumentDeleteListener} 串起四个任务修复后的完整链路：
 * </p>
 * <ol>
 *   <li><b>8.1</b>：切片软删后 {@code selectByDocumentId}（+ 接口）不再返回它们；</li>
 *   <li><b>8.2</b>：向量物理删除走 {@code DocumentDeleteServiceV2}，失败才入 DLQ；</li>
 *   <li><b>8.3</b>：残留向量由孤儿扫描兜底（本测试验证主链路删除后确实无残留）；</li>
 *   <li><b>8.4</b>：{@code filePath} 被消费，磁盘文件被清理。</li>
 * </ol>
 * <p>
 * 默认不执行（会写真实 MySQL / ChromaDB）：需 {@code -Dagent-qr.live.delete=true}。
 * 测试数据（切片 / 向量 / 文件）全部清理，ChromaDB 回到既有基线。
 * </p>
 *
 * @author agent-qr
 */
class DocumentDeleteChainLiveTest {

    private static final String COLLECTION_NAME = "enterprise_knowledge";
    private static final String CHROMA_BASE_URL = "http://localhost:8000";
    /** 测试专用文档 ID（不与任何真实文档冲突） */
    private static final long TEST_DOCUMENT_ID = 998803L;
    private static final int VECTOR_DIMENSION = 2560;
    /** 既有基线（外部 19 条向量），测试前后必须一致 */
    private static final int EXPECTED_BASELINE_VECTORS = 19;

    private static SqlSession sqlSession;
    private static ChunkMapper chunkMapper;
    private static DocumentMapper documentMapper;
    private static DeleteTaskMapper deleteTaskMapper;
    private static ChromaRetriever chromaRetriever;
    private static ChromaEmbeddingStore chromaEmbeddingStore;
    private static Connection rawConnection;
    private static Path uploadDir;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("agent-qr.live.delete"),
                "删除链路实库测试：需显式 -Dagent-qr.live.delete=true 开启");

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
        factory.getConfiguration().addMapper(DeleteTaskMapper.class);
        sqlSession = factory.openSession(true);
        chunkMapper = sqlSession.getMapper(ChunkMapper.class);
        documentMapper = sqlSession.getMapper(DocumentMapper.class);
        deleteTaskMapper = sqlSession.getMapper(DeleteTaskMapper.class);

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
        // ChromaRetriever 的删除方法依赖 store（批次 08 · 8.3.3 起 store 为 null 会显式抛错）
        ReflectionTestUtils.setField(chromaRetriever, "chromaEmbeddingStore", chromaEmbeddingStore);

        Assumptions.assumeTrue(collectionReachable(), "ChromaDB 不可达或 collection 不存在，跳过");

        uploadDir = Files.createTempDirectory("b08-delete-chain");
    }

    @AfterAll
    static void tearDown() {
        if (sqlSession != null) {
            cleanup();
            int total = chromaRetriever.enumerateAllVectors().size();
            System.out.printf("[baseline] ChromaDB 向量总数 = %d（期望 %d）%n", total, EXPECTED_BASELINE_VECTORS);
            assertThat(total).as("测试产生的向量必须全部清理，回到既有基线")
                    .isEqualTo(EXPECTED_BASELINE_VECTORS);
            sqlSession.close();
        }
        try {
            if (rawConnection != null) {
                rawConnection.close();
            }
        } catch (Exception ignored) {
            // 关闭失败不影响断言结论
        }
    }

    @Test
    @DisplayName("★ 端到端：删除文档后 MySQL 切片软删 / ChromaDB 向量删除 / 磁盘文件删除三者一致")
    void deleteChain_shouldKeepMysqlChromaAndDiskConsistent() throws Exception {
        cleanup();

        // ========== 造数：切片 + 向量 + 磁盘文件 ==========
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Chunk chunk = new Chunk();
            chunk.setDocumentId(TEST_DOCUMENT_ID);
            chunk.setChunkIndex(i);
            chunk.setContent("zz_b08_e2e_content_" + i);
            chunk.setCharCount(20);
            chunk.setChromaId("pending");
            chunk.setStatus(Chunk.STATUS_READY);
            chunk.setDeleted(0);
            chunkMapper.insert(chunk);
            // 用切片真实主键派生向量 id（与写入路径同一口径）
            chunk.setChromaId(ChromaRetriever.vectorIdFor(chunk.getId()));
            chunkMapper.updateById(chunk);
            chunks.add(chunk);
        }
        List<Long> chunkIds = chunks.stream().map(Chunk::getId).toList();
        List<String> chromaIds = chunks.stream().map(Chunk::getChromaId).toList();
        writeVectors(chunks);
        assertThat(vectorsOf(chunkIds)).hasSize(2);

        String relativePath = "2026/10/zz_b08_e2e.pdf";
        Path storedFile = uploadDir.resolve(relativePath);
        Files.createDirectories(storedFile.getParent());
        Files.writeString(storedFile, "端到端删除测试");

        // ========== 触发删除事件（真实监听器 + 真实 Mapper/Chroma/文件服务） ==========
        DeadLetterQueue deadLetterQueue = mock(DeadLetterQueue.class);
        FileStorageService fileStorageService = new FileStorageService();
        ReflectionTestUtils.setField(fileStorageService, "uploadDir", uploadDir.toString());

        DocumentDeleteServiceV2 serviceV2 = new DocumentDeleteServiceV2();
        ReflectionTestUtils.setField(serviceV2, "chromaRetriever", chromaRetriever);
        ReflectionTestUtils.setField(serviceV2, "deleteTaskMapper", deleteTaskMapper);
        ReflectionTestUtils.setField(serviceV2, "deadLetterQueue", deadLetterQueue);

        DocumentDeleteListener listener = new DocumentDeleteListener(
                documentMapper, chunkMapper, serviceV2, deadLetterQueue);
        ReflectionTestUtils.setField(listener, "fileStorageService", fileStorageService);

        listener.handleDocumentDeleteRequested(
                new DocumentDeleteRequestedEvent(TEST_DOCUMENT_ID, chunkIds, chromaIds, relativePath));

        // ========== 三者一致性断言 ==========
        // 0) 全链路成功 → 不得产生死信（先断言，便于区分"删除失败"与"残留未清"）
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());

        // 1) MySQL：切片已软删，且查询接口口径（selectByDocumentId）不再返回
        assertThat(chunkMapper.selectByDocumentId(TEST_DOCUMENT_ID))
                .as("问题 27：软删后切片不得再被查出")
                .isEmpty();
        assertThat(count("SELECT COUNT(*) FROM kb_chunk WHERE document_id = " + TEST_DOCUMENT_ID + " AND deleted = 1"))
                .isEqualTo(2);

        // 2) ChromaDB：向量已被物理删除（无残留 → 孤儿扫描无活可干）
        assertThat(vectorsOf(chunkIds))
                .as("ChromaDB 向量必须随文档删除一并清理")
                .isEmpty();

        // 3) 磁盘：物理文件已被清理
        assertThat(storedFile)
                .as("问题 32：filePath 必须被消费，物理文件不得残留")
                .doesNotExist();
    }

    // ==================== 辅助 ====================

    private static void writeVectors(List<Chunk> chunks) {
        List<String> ids = chunks.stream().map(Chunk::getChromaId).toList();
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        for (Chunk chunk : chunks) {
            embeddings.add(new Embedding(fixedVector()));
            segments.add(TextSegment.from(chunk.getContent(), new Metadata(Map.of(
                    "chunk_id", chunk.getId().toString(),
                    "document_id", String.valueOf(TEST_DOCUMENT_ID),
                    "document_title", "批08端到端删除测试"))));
        }
        chromaEmbeddingStore.addAll(ids, embeddings, segments);
    }

    private static List<ChromaRetriever.ChromaVectorRecord> vectorsOf(List<Long> chunkIds) {
        return chromaRetriever.enumerateAllVectors().stream()
                .filter(record -> record.chunkId() != null && chunkIds.contains(record.chunkId()))
                .toList();
    }

    private static float[] fixedVector() {
        float[] vector = new float[VECTOR_DIMENSION];
        for (int i = 0; i < VECTOR_DIMENSION; i++) {
            vector[i] = (float) Math.cos(i * 0.01);
        }
        return vector;
    }

    private static long count(String sql) {
        try (PreparedStatement ps = rawConnection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    private static boolean collectionReachable() {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(CHROMA_BASE_URL + "/api/v2/tenants/default/databases/default/collections/"
                                    + COLLECTION_NAME))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 清理测试数据：向量（按元数据 document_id 兜底枚举）+ 切片 + 删除任务 */
    private static void cleanup() {
        if (rawConnection == null) {
            return;
        }
        try {
            List<String> vectorIds = new ArrayList<>(chromaRetriever.enumerateAllVectors().stream()
                    .filter(record -> String.valueOf(TEST_DOCUMENT_ID).equals(String.valueOf(record.documentId())))
                    .map(ChromaRetriever.ChromaVectorRecord::vectorId)
                    .toList());
            List<Long> chunkIds = new ArrayList<>();
            try (PreparedStatement ps = rawConnection.prepareStatement(
                    "SELECT id FROM kb_chunk WHERE document_id = " + TEST_DOCUMENT_ID);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    chunkIds.add(rs.getLong(1));
                }
            }
            for (Long id : chunkIds) {
                vectorIds.addAll(chromaRetriever.findVectorIdsByChunkIds(List.of(id)).values());
            }
            if (!vectorIds.isEmpty()) {
                chromaEmbeddingStore.removeAll(vectorIds);
            }
            try (PreparedStatement ps = rawConnection.prepareStatement(
                    "DELETE FROM kb_chunk WHERE document_id = " + TEST_DOCUMENT_ID)) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = rawConnection.prepareStatement(
                    "DELETE FROM delete_task WHERE document_id = " + TEST_DOCUMENT_ID)) {
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
