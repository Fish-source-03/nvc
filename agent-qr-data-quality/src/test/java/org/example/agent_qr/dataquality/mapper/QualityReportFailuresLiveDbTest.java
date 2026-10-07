package org.example.agent_qr.dataquality.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.agent_qr.dataquality.entity.QualityFailure;
import org.example.agent_qr.dataquality.entity.QualityReport;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code quality_report.failures} JSON 列的实库读写测试
 * （批次 10 · 任务 10.4.2 / 10.4.3 / 10.4.5，问题 26）。
 * <p>
 * 为什么必须对真实 MySQL 执行：方案 B 的持久化契约是"实体 ↔ JSON 列"，
 * MyBatis-Plus 的 {@code JacksonTypeHandler} 只在实库上才能验证
 * （Mock 层覆盖不到 JSON 列的类型转换）。
 * </p>
 * <p>
 * 覆盖两条链路：
 * </p>
 * <ol>
 *   <li><b>写入</b>：新格式明细（{@code recordIndices}/{@code recordCount}/{@code ruleType}）
 *       确实以 JSON 数组落库，可用 MySQL 的 {@code JSON_LENGTH} 查询；</li>
 *   <li><b>读取</b>：走 MyBatis-Plus 自动 resultMap 的查询路径（列表接口用的 {@code BaseMapper}）
 *       能把 JSON 列解析回明细列表。</li>
 * </ol>
 * <p>
 * ⚠️ <b>已知缺陷（不在本批次可改文件范围内，已上报）</b>：自定义 {@code @Select} 方法
 * （{@code QualityReportMapper#selectByBatchId}，即报告<b>详情</b>接口的查询）<b>不会</b>
 * 套用实体的 {@code autoResultMap}（MyBatis-Plus 3.5.5 的
 * {@code MybatisMapperAnnotationBuilder} 不处理该注解），因此详情接口读回的
 * {@code failures} 恒为空列表。本次实测证据：库中已有含明细的历史报告
 * （{@code JSON_LENGTH(failures) > 0}）经该方法查询得到 {@code failures.size()=0}。
 * 修复建议：给该方法加 {@code @ResultMap("mybatis-plus_QualityReport")}，
 * 或在 Service 层改用 {@code BaseMapper} 的 wrapper 查询。
 * </p>
 * <p>
 * <b>数据基线保护</b>：测试报告使用 {@code ZZ_B10_*} 批次号，用例结束物理删除并复算行数。
 * </p>
 *
 * @author agent-qr
 */
class QualityReportFailuresLiveDbTest {

    private static final String NEW_BATCH_ID = "ZZ_B10_NEW";
    private static final String LEGACY_BATCH_ID = "ZZ_B10_LEGACY";

    private static SqlSession sqlSession;
    private static QualityReportMapper reportMapper;
    private static Connection verificationConnection;

    private static long baselineTotal;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        factory.getConfiguration().addMapper(QualityReportMapper.class);
        sqlSession = factory.openSession(true);
        reportMapper = sqlSession.getMapper(QualityReportMapper.class);

        baselineTotal = count("SELECT COUNT(*) FROM quality_report");
        cleanup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlSession == null) {
            return;
        }
        cleanup();
        System.out.printf("[baseline] quality_report total %d -> %d%n",
                baselineTotal, count("SELECT COUNT(*) FROM quality_report"));
        sqlSession.close();
        verificationConnection.close();
    }

    @BeforeEach
    void resetData() throws Exception {
        cleanup();
    }

    @Test
    @DisplayName("★ 新格式明细落库为 JSON 数组：recordIndices / recordCount / ruleType 均可查询")
    void insert_shouldPersistNewFailureShapeAsJson() throws Exception {
        QualityFailure failure = new QualityFailure("完整性", 2, "内容字段为空");
        failure.setRuleType("completeness");
        failure.addRecordIndex(3);

        QualityReport report = new QualityReport(NEW_BATCH_ID, 10, 7, 3, 0.7, false, List.of(failure));
        report.setDatasourceId(998877L);
        report.setSourceName("ZZ_B10 测试源");
        report.setCheckTime(LocalDateTime.now());
        reportMapper.insert(report);

        assertThat(report.getId()).isNotNull();
        long jsonLength = scalarLong(
                "SELECT JSON_LENGTH(failures) FROM quality_report WHERE batch_id = '" + NEW_BATCH_ID + "'");
        assertThat(jsonLength)
                .as("failures 必须是 JSON 数组（可被 MySQL 的 JSON 函数解析）")
                .isEqualTo(1);

        // MySQL 输出 JSON 时会在冒号后加空格，比较前统一去掉空白
        String rawJson = scalarString(
                "SELECT CAST(failures AS CHAR) FROM quality_report WHERE batch_id = '" + NEW_BATCH_ID + "'")
                .replaceAll("\\s+", "");
        assertThat(rawJson)
                .contains("\"ruleType\":\"completeness\"")
                .contains("\"recordIndices\":[2,3]")
                .contains("\"recordCount\":2")
                .contains("\"recordIndex\":2");
    }

    @Test
    @DisplayName("★ 存量旧格式 JSON（只有 recordIndex）可原样保留：升级无需迁移历史数据")
    void legacyShapeJson_shouldRemainReadable() throws Exception {
        try (Statement statement = verificationConnection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO quality_report
                        (batch_id, datasource_id, source_name, total, pass, fail, rate, blocked,
                         failures, check_time)
                    VALUES
                        ('%s', 998877, 'ZZ_B10 存量', 12, 0, 12, 0, 1,
                         CAST('[{"reason":"数据重复：当前批次内存在相同记录","ruleName":"重复检测","recordIndex":7}]' AS JSON),
                         NOW())
                    """.formatted(LEGACY_BATCH_ID));
        }

        long jsonLength = scalarLong(
                "SELECT JSON_LENGTH(failures) FROM quality_report WHERE batch_id = '" + LEGACY_BATCH_ID + "'");
        String rawJson = scalarString(
                        "SELECT CAST(failures AS CHAR) FROM quality_report WHERE batch_id = '" + LEGACY_BATCH_ID + "'")
                .replaceAll("\\s+", "");

        assertThat(jsonLength).isEqualTo(1);
        assertThat(rawJson).contains("\"recordIndex\":7");
        assertThat(count("SELECT COUNT(*) FROM quality_report WHERE batch_id LIKE 'ZZ_B10%'")).isEqualTo(1);
    }

    @Test
    @DisplayName("读取路径（MyBatis-Plus 自动 resultMap，列表接口所用）：明细 JSON 可解析回列表")
    void selectList_shouldReadFailuresBackThroughAutoResultMap() throws Exception {
        QualityFailure failure = new QualityFailure("格式", 1, "字段 'code' 的值 'bad' 不符合正则表达式 ^OK$");
        failure.setRuleType("format");
        failure.addRecordIndex(2);

        QualityReport report = new QualityReport("ZZ_B10_READ", 3, 1, 2, 0.33, false, List.of(failure));
        reportMapper.insert(report);

        List<QualityReport> loaded = reportMapper.selectList(
                new LambdaQueryWrapper<QualityReport>().eq(QualityReport::getBatchId, "ZZ_B10_READ"));

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getFailures())
                .as("JSON 列必须能读回明细（否则报告无法定位失败记录）")
                .hasSize(1);

        // 明细的 JSON 形态与落库一致（元素类型之外的可观察契约）
        String json = objectMapper.writeValueAsString(loaded.get(0).getFailures());
        assertThat(json).contains("recordIndices").contains("[1,2]").contains("\"recordCount\":2");
    }

    @Test
    @DisplayName("failures 为空数组的报告读回为空（不产生 null 指针）")
    void emptyFailures_shouldReadBackAsEmpty() {
        QualityReport report = new QualityReport("ZZ_B10_EMPTY", 5, 5, 0, 1.0, false, List.of());
        reportMapper.insert(report);

        List<QualityReport> loaded = reportMapper.selectList(
                new LambdaQueryWrapper<QualityReport>().eq(QualityReport::getBatchId, "ZZ_B10_EMPTY"));

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getFailures()).isNotNull().isEmpty();
    }

    // ==================== 辅助 ====================

    private static void cleanup() throws Exception {
        try (Statement statement = verificationConnection.createStatement()) {
            statement.executeUpdate("DELETE FROM quality_report WHERE batch_id LIKE 'ZZ_B10%'");
        }
    }

    private static long scalarLong(String sql) throws Exception {
        try (Statement statement = verificationConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalarString(String sql) throws Exception {
        try (Statement statement = verificationConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long count(String sql) throws Exception {
        return scalarLong(sql);
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
