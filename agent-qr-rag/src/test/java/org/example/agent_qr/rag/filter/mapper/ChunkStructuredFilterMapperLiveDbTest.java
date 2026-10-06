package org.example.agent_qr.rag.filter.mapper;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.FilterConditionExtractor.FieldDefinition;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChunkStructuredFilterMapper} 实库冒烟测试（批次 04 · 任务 4.1 / 4.3 / 4.4）。
 * <p>
 * 本批次的 Mapper 改动包含三类"代码看起来对、运行时才报错"的风险：
 * <ol>
 *   <li>{@code LIMIT #{limit}} 绑定参数（替换硬编码 LIMIT 500/2000）；</li>
 *   <li>{@code <script>}/{@code <foreach>} 动态 IN 子句（批量取切片内容）；</li>
 *   <li>开区间 SQL 的真实比较语义（{@code >} / {@code <} 与闭区间的差别）。</li>
 * </ol>
 * 这些无法由 Mockito 单测覆盖，因此对<b>真实 MySQL</b>执行只读查询验证。
 * </p>
 * <p>
 * 环境不可用（无 MySQL / 无 application.yml）时自动跳过；本测试<b>只执行 SELECT</b>，
 * 不写入任何数据（数据基线不可破坏）。数据库连接参数从 application.yml 读取，
 * 不在测试源码中出现任何凭据原文。
 * </p>
 *
 * @author agent-qr
 */
class ChunkStructuredFilterMapperLiveDbTest {

    private static SqlSession sqlSession;
    private static ChunkStructuredFilterMapper mapper;
    private static Connection verificationConnection;

    @BeforeAll
    static void setUp() throws Exception {
        String url = dataSourceProperty("url");
        String username = dataSourceProperty("username");
        String password = dataSourceProperty("password");
        Assumptions.assumeTrue(url != null, "application.yml 中未找到数据源配置，跳过实库冒烟测试");

        Connection probe;
        try {
            probe = DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达（" + e.getClass().getSimpleName() + "），跳过实库冒烟测试");
            return;
        }
        verificationConnection = probe;

        DataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, username, password);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(ChunkStructuredFilterMapper.class);
        sqlSession = factory.openSession();
        mapper = sqlSession.getMapper(ChunkStructuredFilterMapper.class);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlSession != null) {
            sqlSession.close();
        }
        if (verificationConnection != null) {
            verificationConnection.close();
        }
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("字段定义查询可用，且能映射到 FieldDefinition（供 FilterConditionExtractor 使用）")
    void selectDistinctFieldsByDomain_shouldMapToFieldDefinition() {
        List<FieldDefinition> fields = mapper.selectDistinctFieldsByDomain("HR");

        Assumptions.assumeFalse(fields.isEmpty(), "库中暂无 HR 域结构化数据，跳过");
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.getFieldName()).isNotBlank();
            assertThat(field.getFieldType()).isNotBlank();
        });
        assertThat(fields).extracting(FieldDefinition::getFieldName)
                .contains("clearance_level", "department");
    }

    @Test
    @DisplayName("★ 开区间与闭区间语义真实生效：GT(2) 与 LTE(2) 互斥，并集等于该字段全部记录")
    void numberOperators_shouldRespectOpenAndClosedIntervals() throws Exception {
        String field = firstNumberField();
        Assumptions.assumeTrue(field != null, "库中暂无 NUMBER 字段，跳过");

        List<Long> gt2 = mapper.selectChunkIdsByNumberGt(field, new BigDecimal("2"), 500);
        List<Long> lte2 = mapper.selectChunkIdsByNumberLte(field, new BigDecimal("2"), 500);
        List<Long> all = jdbcChunkIds(field, "numeric_value IS NOT NULL");

        Assumptions.assumeFalse(all.isEmpty(), "该字段无数据，跳过");

        assertThat(intersect(gt2, lte2))
                .as("GT 是严格大于：若退化为等值/闭区间，两个集合会重叠")
                .isEmpty();
        assertThat(union(gt2, lte2))
                .as("GT(2) ∪ LTE(2) 必须覆盖该字段的全部记录")
                .containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    @DisplayName("★ 闭区间查询（无 operator 语义）与 JDBC 直查结果一致")
    void numberRange_shouldMatchDirectSql() throws Exception {
        String field = firstNumberField();
        Assumptions.assumeTrue(field != null, "库中暂无 NUMBER 字段，跳过");

        List<Long> byRange = mapper.selectChunkIdsByNumberRange(field, BigDecimal.ZERO, new BigDecimal("2"), 500);
        List<Long> expected = jdbcChunkIds(field, "numeric_value >= 0 AND numeric_value <= 2");

        assertThat(byRange).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("★ LIMIT 绑定参数生效（limit=1 时最多返回 1 条，证明 #{limit} 被真实下推）")
    void selectChunkIdsByDomain_shouldHonorBoundLimit() {
        List<Long> one = mapper.selectChunkIdsByDomain("HR", 1);
        List<Long> all = mapper.selectChunkIdsByDomain("HR", 2000);

        Assumptions.assumeFalse(all.isEmpty(), "库中暂无 HR 域结构化数据，跳过");
        assertThat(one).hasSizeLessThanOrEqualTo(1);
        assertThat(all.size()).isGreaterThanOrEqualTo(one.size());
    }

    @Test
    @DisplayName("★ 批量取切片内容（script/foreach IN 子句 + RetrievedDocument 映射）")
    void selectChunkContentsByIds_shouldReturnChunkContents() {
        List<Long> chunkIds = mapper.selectChunkIdsByDomain("HR", 3);
        Assumptions.assumeFalse(chunkIds.isEmpty(), "库中暂无 HR 域结构化数据，跳过");

        List<RetrievedDocument> documents = mapper.selectChunkContentsByIds(chunkIds);

        assertThat(documents).isNotEmpty();
        assertThat(documents).extracting(RetrievedDocument::getChunkId)
                .allSatisfy(chunkId -> assertThat(chunkIds).contains(chunkId));
        assertThat(documents).allSatisfy(document -> assertThat(document.getContent()).isNotNull());
    }

    @Test
    @DisplayName("枚举值查询在无 ENUM 字段的域上安全返回空列表（不报错）")
    void selectEnumValues_shouldReturnEmpty_whenNoEnumField() {
        assertThat(mapper.selectEnumValues("department", "HR")).isEmpty();
    }

    // ==================== 辅助方法 ====================

    private String firstNumberField() {
        return mapper.selectDistinctFieldsByDomain("HR").stream()
                .filter(field -> "NUMBER".equals(field.getFieldType()))
                .map(FieldDefinition::getFieldName)
                .filter(name -> !jdbcChunkIdsQuietly(name).isEmpty())
                .findFirst()
                .orElse(null);
    }

    private List<Long> jdbcChunkIdsQuietly(String fieldName) {
        try {
            return jdbcChunkIds(fieldName, "numeric_value IS NOT NULL");
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 用原生 JDBC 独立复算期望值（与 Mapper 注解 SQL 无关的第二条证据链）。 */
    private List<Long> jdbcChunkIds(String fieldName, String condition) throws Exception {
        String sql = "SELECT DISTINCT chunk_id FROM kb_chunk_structured WHERE field_name = ? AND "
                + condition + " ORDER BY chunk_id";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = verificationConnection.prepareStatement(sql)) {
            statement.setString(1, fieldName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private Set<Long> intersect(List<Long> a, List<Long> b) {
        Set<Long> result = new LinkedHashSet<>(a);
        result.retainAll(new LinkedHashSet<>(b));
        return result;
    }

    private Set<Long> union(List<Long> a, List<Long> b) {
        Set<Long> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return result;
    }

    /**
     * 从 application.yml 读取数据源参数（支持 {@code ${ENV:default}} 形式），
     * 避免在测试源码中硬编码任何凭据。
     */
    private static String dataSourceProperty(String key) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", "application.yml"));
            if (Files.exists(candidate)) {
                try {
                    String content = Files.readString(candidate, StandardCharsets.UTF_8);
                    Matcher matcher = Pattern.compile("(?m)^\\s*" + key + ":\\s*(\\S+)\\s*$")
                            .matcher(content);
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
