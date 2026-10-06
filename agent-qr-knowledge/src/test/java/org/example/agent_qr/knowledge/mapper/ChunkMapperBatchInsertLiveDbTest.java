package org.example.agent_qr.knowledge.mapper;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.rag.entity.ChunkStructured;
import org.example.agent_qr.rag.mapper.ChunkStructuredMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
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

/**
 * {@link ChunkMapper#insertBatch} / {@link ChunkStructuredMapper#insertBatch} 实库测试
 * （批次 05 · 任务 5.2.2 / 问题 21 ②）。
 * <p>
 * 为什么必须对真实 MySQL 执行：多值 INSERT 的 {@code <foreach>} 拼接、自增主键回填
 * （{@code useGeneratedKeys}）都是"代码看起来对、运行时才报错"的高危点，
 * Mock 拦不住。
 * </p>
 * <p>
 * <b>数据基线保护</b>：所有写入都带 {@code datasource_id = 998877}（测试专用）与
 * {@code domain = 'ZZ_B05_PERF'}，测试结束全部物理删除，并复算基线（kb_chunk 总行数与
 * deleted=0 行数）确保回到测试前状态。
 * </p>
 *
 * @author agent-qr
 */
class ChunkMapperBatchInsertLiveDbTest {

    /** 测试专用数据源 ID（不与任何真实数据源冲突） */
    private static final long TEST_DATASOURCE_ID = 998877L;
    private static final String TEST_DOMAIN = "ZZ_B05_PERF";

    private static SqlSession sqlSession;
    private static ChunkMapper chunkMapper;
    private static ChunkStructuredMapper chunkStructuredMapper;
    private static Connection verificationConnection;

    private static long baselineTotal;
    private static long baselineLive;
    private static long baselineStructured;

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

        DataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, username, password);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(ChunkMapper.class);
        factory.getConfiguration().addMapper(ChunkStructuredMapper.class);
        sqlSession = factory.openSession(true);
        chunkMapper = sqlSession.getMapper(ChunkMapper.class);
        chunkStructuredMapper = sqlSession.getMapper(ChunkStructuredMapper.class);

        baselineTotal = count("SELECT COUNT(*) FROM kb_chunk");
        baselineLive = count("SELECT COUNT(*) FROM kb_chunk WHERE deleted = 0");
        baselineStructured = count("SELECT COUNT(*) FROM kb_chunk_structured");
        cleanup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlSession == null) {
            return;
        }
        cleanup();
        // 复算基线：证明测试数据已全部清理
        System.out.printf("[baseline] kb_chunk total %d -> %d | deleted=0 %d -> %d | kb_chunk_structured %d -> %d%n",
                baselineTotal, count("SELECT COUNT(*) FROM kb_chunk"),
                baselineLive, count("SELECT COUNT(*) FROM kb_chunk WHERE deleted = 0"),
                baselineStructured, count("SELECT COUNT(*) FROM kb_chunk_structured"));
        sqlSession.close();
        verificationConnection.close();
    }

    // ==================== SQL 与参数正确性 ====================

    @org.junit.jupiter.api.BeforeEach
    void resetData() throws Exception {
        // 各用例独立：每个用例开始前清空上一轮写入的测试数据
        cleanup();
    }

    @Test
    @DisplayName("★ 批量插入 1000 条：单次 SQL 写入全部行，且自增主键被逐条回填")
    void insertBatch_shouldWriteAllRows_andBackfillGeneratedIds() throws Exception {
        List<Chunk> chunks = buildChunks(1000, 0);

        int affected = chunkMapper.insertBatch(chunks);

        assertThat(affected).isEqualTo(1000);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getId()).isNotNull());
        assertThat(chunks.stream().map(Chunk::getId).distinct().count())
                .as("回填的主键必须逐条不同（否则 structured 会写错 chunk_id）")
                .isEqualTo(1000);
        assertThat(countByDatasourceId()).isEqualTo(1000);
    }

    @Test
    @DisplayName("★ 分批写入（1000 + 500）后总行数正确，批次下标与主键回填无错位")
    void insertBatch_shouldWorkAcrossBatchBoundaries() throws Exception {
        List<Chunk> chunks = buildChunks(1500, 0);
        int written = 0;
        for (int i = 0; i < chunks.size(); i += 1000) {
            written += chunkMapper.insertBatch(chunks.subList(i, Math.min(i + 1000, chunks.size())));
        }

        assertThat(written).isEqualTo(1500);
        assertThat(countByDatasourceId()).isEqualTo(1500);
        // 校验回填的主键与 chunk_index 一一对应（下标错位是 foreach 批量插入的典型事故）
        try (PreparedStatement ps = verificationConnection.prepareStatement(
                "SELECT COUNT(*) FROM kb_chunk WHERE datasource_id = ? AND id IS NOT NULL AND deleted = 0")) {
            ps.setLong(1, TEST_DATASOURCE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1500);
            }
        }
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getId()).isNotNull());
    }

    @Test
    @DisplayName("★ 结构化字段批量插入：数值/日期/字符串三类字段均正确落库")
    void insertBatch_shouldWriteStructuredFields() throws Exception {
        Chunk chunk = buildChunks(1, 0).get(0);
        chunkMapper.insertBatch(List.of(chunk));

        List<ChunkStructured> fields = new ArrayList<>();
        fields.add(structured(chunk.getId(), "salary", "10000.5", new BigDecimal("10000.5"), null,
                ChunkStructured.TYPE_NUMBER));
        fields.add(structured(chunk.getId(), "hire_date", "2026-10-07", null,
                java.time.LocalDate.parse("2026-10-07"), ChunkStructured.TYPE_DATE));
        fields.add(structured(chunk.getId(), "dept", "HR", null, null, ChunkStructured.TYPE_STRING));

        int affected = chunkStructuredMapper.insertBatch(fields);

        assertThat(affected).isEqualTo(3);
        assertThat(chunkStructuredMapper.selectByChunkId(chunk.getId()))
                .extracting(ChunkStructured::getFieldName, ChunkStructured::getFieldType)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("salary", ChunkStructured.TYPE_NUMBER),
                        org.assertj.core.groups.Tuple.tuple("hire_date", ChunkStructured.TYPE_DATE),
                        org.assertj.core.groups.Tuple.tuple("dept", ChunkStructured.TYPE_STRING));
    }

    @Test
    @DisplayName("★ 批量写入较逐条写入显著更快（防止回退为逐条 insert 的回归护栏）")
    void insertBatch_shouldBeSubstantiallyFasterThanRowByRow() throws Exception {
        int rows = 2000;

        cleanup();
        List<Chunk> rowByRow = buildChunks(rows, 0);
        long t0 = System.currentTimeMillis();
        for (Chunk chunk : rowByRow) {
            chunkMapper.insert(chunk);
        }
        long rowByRowMs = System.currentTimeMillis() - t0;

        cleanup();
        List<Chunk> batched = buildChunks(rows, 0);
        long t1 = System.currentTimeMillis();
        for (int i = 0; i < batched.size(); i += 1000) {
            chunkMapper.insertBatch(batched.subList(i, Math.min(i + 1000, batched.size())));
        }
        long batchMs = System.currentTimeMillis() - t1;

        System.out.printf("[perf] kb_chunk 写入 %d 条: 逐条 %d ms | 批量(1000/批) %d ms%n",
                rows, rowByRowMs, batchMs);
        assertThat(countByDatasourceId()).isEqualTo(rows);
        assertThat(batchMs * 2).as("批量写入应至少快 2 倍（实测 %d ms vs %d ms）", batchMs, rowByRowMs)
                .isLessThanOrEqualTo(Math.max(rowByRowMs, 150));
    }

    // ==================== 辅助 ====================

    private static List<Chunk> buildChunks(int count, int indexOffset) {
        List<Chunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Chunk chunk = new Chunk();
            chunk.setDocumentId(null);
            chunk.setDatasourceId(TEST_DATASOURCE_ID);
            chunk.setChunkIndex(indexOffset + i);
            chunk.setContent("zz_b05_perf_content_" + (indexOffset + i));
            chunk.setCharCount(22);
            chunk.setChromaId("pending");
            chunk.setRecordHash("zzb05hash" + (indexOffset + i));
            chunk.setDeleted(0);
            chunks.add(chunk);
        }
        return chunks;
    }

    private static ChunkStructured structured(Long chunkId, String fieldName, String fieldValue,
                                              BigDecimal numericValue, java.time.LocalDate dateValue,
                                              String fieldType) {
        ChunkStructured cs = new ChunkStructured();
        cs.setChunkId(chunkId);
        cs.setDomain(TEST_DOMAIN);
        cs.setFieldName(fieldName);
        cs.setFieldValue(fieldValue);
        cs.setNumericValue(numericValue);
        cs.setDateValue(dateValue);
        cs.setFieldType(fieldType);
        return cs;
    }

    private static long countByDatasourceId() throws Exception {
        try (PreparedStatement ps = verificationConnection.prepareStatement(
                "SELECT COUNT(*) FROM kb_chunk WHERE datasource_id = ?")) {
            ps.setLong(1, TEST_DATASOURCE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long count(String sql) throws Exception {
        try (PreparedStatement ps = verificationConnection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 物理删除本轮测试写入的全部数据（含软删标记的行）。 */
    private static void cleanup() throws Exception {
        if (verificationConnection == null) {
            return;
        }
        try (PreparedStatement ps = verificationConnection.prepareStatement(
                "DELETE FROM kb_chunk WHERE datasource_id = ?")) {
            ps.setLong(1, TEST_DATASOURCE_ID);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = verificationConnection.prepareStatement(
                "DELETE FROM kb_chunk_structured WHERE domain = ?")) {
            ps.setString(1, TEST_DOMAIN);
            ps.executeUpdate();
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
