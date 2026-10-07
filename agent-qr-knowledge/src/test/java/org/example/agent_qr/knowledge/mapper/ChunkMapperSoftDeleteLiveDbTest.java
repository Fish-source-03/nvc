package org.example.agent_qr.knowledge.mapper;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.service.DocumentQueryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 软删切片不可漏出的实库测试（批次 08 · 任务 8.1，问题 27）。
 * <p>
 * <b>为什么必须连真库</b>：缺陷本体是"手写 SQL 少了 {@code deleted = 0}"，
 * 而 MyBatis-Plus 的 {@code @TableLogic} 只在<b>框架生成 SQL</b> 时拼接条件——
 * Mock 掉 Mapper 就什么也验证不了（SQL 文本断言由
 * {@link ChunkMapperSoftDeleteSqlTest} 负责，本测试负责<b>运行时行为</b>）。
 * </p>
 * <p>
 * <b>数据基线保护</b>：全部测试数据挂在测试专用 {@code document_id = 998801} 下，
 * 结束（含每个用例开始前）物理删除，不影响既有 11788 行 {@code kb_chunk} 基线与
 * 19 条有效切片。
 * </p>
 *
 * @author agent-qr
 */
class ChunkMapperSoftDeleteLiveDbTest {

    /** 测试专用文档 ID（不与任何真实文档冲突） */
    private static final long TEST_DOCUMENT_ID = 998801L;

    private static SqlSession sqlSession;
    private static ChunkMapper chunkMapper;
    private static DocumentMapper documentMapper;
    private static DocumentQueryService documentQueryService;
    private static Connection verificationConnection;

    @BeforeAll
    static void setUp() throws Exception {
        String url = dataSourceProperty("url");
        String username = dataSourceProperty("username");
        String password = dataSourceProperty("password");
        Assumptions.assumeTrue(url != null, "application.yml 中未找到数据源配置，跳过实库测试");

        try {
            verificationConnection = DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达（" + e.getClass().getSimpleName() + "），跳过实库测试");
            return;
        }

        DataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, username, password);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(ChunkMapper.class);
        factory.getConfiguration().addMapper(DocumentMapper.class);
        sqlSession = factory.openSession(true);
        chunkMapper = sqlSession.getMapper(ChunkMapper.class);
        documentMapper = sqlSession.getMapper(DocumentMapper.class);
        documentQueryService = new DocumentQueryService(documentMapper, chunkMapper, mock(AbacEvaluator.class));

        cleanup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlSession == null) {
            return;
        }
        cleanup();
        long remaining = count("SELECT COUNT(*) FROM kb_chunk WHERE document_id = " + TEST_DOCUMENT_ID);
        System.out.printf("[baseline] 测试数据清理后残留 kb_chunk(document_id=%d) = %d%n",
                TEST_DOCUMENT_ID, remaining);
        assertThat(remaining).isZero();
        sqlSession.close();
        verificationConnection.close();
    }

    @BeforeEach
    void resetData() {
        cleanup();
    }

    // ==================== 缺陷用例（修复前失败、修复后通过） ====================

    @Test
    @DisplayName("★ 文档软删后 selectByDocumentId 必须返回空（修复前会返回全部已删切片）")
    void selectByDocumentId_shouldReturnEmpty_afterSoftDelete() {
        seedChunks(3);
        assertThat(chunkMapper.selectByDocumentId(TEST_DOCUMENT_ID)).hasSize(3);

        chunkMapper.softDeleteByDocumentId(TEST_DOCUMENT_ID);

        // 修复前：SQL 无 deleted = 0 → 仍返回 3 条（文档列表看不到文档，切片却能查到）
        assertThat(chunkMapper.selectByDocumentId(TEST_DOCUMENT_ID))
                .as("已软删文档的切片不得再被查出")
                .isEmpty();
    }

    @Test
    @DisplayName("★ DocumentQueryService.getChunks（= GET /documents/{id}/chunks 的返回值）软删后应为空")
    void getChunks_shouldReturnEmpty_afterSoftDelete() {
        seedChunks(2);
        assertThat(documentQueryService.getChunks(TEST_DOCUMENT_ID)).hasSize(2);

        chunkMapper.softDeleteByDocumentId(TEST_DOCUMENT_ID);

        assertThat(documentQueryService.getChunks(TEST_DOCUMENT_ID))
                .as("KnowledgeController 直接返回该结果，因此接口响应为空列表")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 文档软删后 selectChromaIdsByDocumentId 不得再返回其向量引用 ID")
    void selectChromaIdsByDocumentId_shouldReturnEmpty_afterSoftDelete() {
        seedChunks(2);
        assertThat(chunkMapper.selectChromaIdsByDocumentId(TEST_DOCUMENT_ID)).hasSize(2);

        chunkMapper.softDeleteByDocumentId(TEST_DOCUMENT_ID);

        assertThat(chunkMapper.selectChromaIdsByDocumentId(TEST_DOCUMENT_ID)).isEmpty();
    }

    // ==================== 回归用例 ====================

    @Test
    @DisplayName("★ 回归：未删除文档的切片查询结果与修复前一致（条数 + chunk_index 升序）")
    void selectByDocumentId_shouldKeepPreviousBehaviour_forLiveDocument() {
        seedChunks(4);

        List<Chunk> chunks = chunkMapper.selectByDocumentId(TEST_DOCUMENT_ID);

        assertThat(chunks).hasSize(4);
        assertThat(chunks).extracting(Chunk::getChunkIndex).containsExactly(0, 1, 2, 3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getDeleted()).isZero());
    }

    @Test
    @DisplayName("★ 回归：软删切片不进入 selectAllReadyChunks（任务 8.3 禁改方法，语义保持不变）")
    void selectAllReadyChunks_shouldNotContainSoftDeletedRows() {
        seedChunks(2);
        long baseReady = chunkMapper.selectAllReadyChunks().size();

        seedChunksWithStatus(2, 2, Chunk.STATUS_READY);
        assertThat(chunkMapper.selectAllReadyChunks()).hasSize((int) baseReady + 2);

        chunkMapper.softDeleteByDocumentId(TEST_DOCUMENT_ID);

        assertThat(chunkMapper.selectAllReadyChunks())
                .as("软删后应回到基线（本测试数据全部被排除）")
                .hasSize((int) baseReady);
    }

    @Test
    @DisplayName("★ 任务 8.3 新增的 selectLiveChunkIds：只返回存活切片 ID（软删后为空）")
    void selectLiveChunkIds_shouldReturnOnlyLiveIds() {
        List<Chunk> chunks = seedChunks(3);
        List<Long> ids = chunks.stream().map(Chunk::getId).toList();

        assertThat(chunkMapper.selectLiveChunkIds(ids)).containsExactlyInAnyOrderElementsOf(ids);

        // 只软删其中一条：差集即"孤儿向量的判定依据"
        chunkMapper.deleteById(chunks.get(0).getId());

        assertThat(chunkMapper.selectLiveChunkIds(ids))
                .containsExactlyInAnyOrder(chunks.get(1).getId(), chunks.get(2).getId());
    }

    // ==================== 辅助 ====================

    private static List<Chunk> seedChunks(int count) {
        return seedChunksWithStatus(count, 0, Chunk.STATUS_INDEXED);
    }

    private static List<Chunk> seedChunksWithStatus(int count, int indexOffset, String status) {
        List<Chunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Chunk chunk = new Chunk();
            chunk.setDocumentId(TEST_DOCUMENT_ID);
            chunk.setChunkIndex(indexOffset + i);
            chunk.setContent("zz_b08_softdelete_content_" + (indexOffset + i));
            chunk.setCharCount(30);
            chunk.setChromaId("zz-b08-chroma-" + (indexOffset + i));
            chunk.setStatus(status);
            chunk.setDeleted(0);
            chunkMapper.insert(chunk);
            chunks.add(chunk);
        }
        return chunks;
    }

    private static long count(String sql) throws Exception {
        try (PreparedStatement ps = verificationConnection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 物理删除本轮测试写入的全部数据（含已软删的行） */
    private static void cleanup() {
        if (verificationConnection == null) {
            return;
        }
        try (PreparedStatement ps = verificationConnection.prepareStatement(
                "DELETE FROM kb_chunk WHERE document_id = ?")) {
            ps.setLong(1, TEST_DOCUMENT_ID);
            ps.executeUpdate();
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
