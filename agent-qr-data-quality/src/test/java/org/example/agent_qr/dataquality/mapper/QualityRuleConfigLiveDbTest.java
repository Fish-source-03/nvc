package org.example.agent_qr.dataquality.mapper;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.agent_qr.dataquality.entity.QualityRuleConfig;
import org.example.agent_qr.dataquality.rule.CompletenessRule;
import org.example.agent_qr.dataquality.rule.DeduplicationRule;
import org.example.agent_qr.dataquality.rule.EncodingRule;
import org.example.agent_qr.dataquality.rule.FormatRule;
import org.example.agent_qr.dataquality.rule.LengthRule;
import org.example.agent_qr.dataquality.rule.QualityRule;
import org.example.agent_qr.dataquality.rule.RuleConfig;
import org.example.agent_qr.dataquality.service.QualityRuleService;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code quality_rule} 表的实库测试（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 为什么必须对真实 MySQL 执行：
 * </p>
 * <ol>
 *   <li>{@code params} 是 <b>JSON 列</b> + MyBatis-Plus {@code JacksonTypeHandler}，
 *       能否正确写入/读回只有实库能验证（Mock 拦不住 JSON 列的类型转换问题）；</li>
 *   <li>"停用的规则不参与质检"最终由 SQL 的 {@code enabled} 过滤实现；</li>
 *   <li>表结构与内置默认规则由 {@code db/p2-schema.sql} 提供，需核对已对运行库生效。</li>
 * </ol>
 * <p>
 * <b>数据基线保护</b>：测试数据一律以 {@code ZZ_B10_} 前缀命名，用例结束后物理删除，
 * 并复算 {@code quality_rule} 总行数确保回到测试前状态。
 * </p>
 *
 * @author agent-qr
 */
class QualityRuleConfigLiveDbTest {

    /** 测试规则名称前缀（与真实规则区分，便于清理） */
    private static final String TEST_PREFIX = "ZZ_B10_";

    private static SqlSession sqlSession;
    private static QualityRuleConfigMapper ruleConfigMapper;
    private static QualityRuleService ruleService;
    private static Connection verificationConnection;

    private static long baselineTotal;

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
        factory.getConfiguration().addMapper(QualityRuleConfigMapper.class);
        sqlSession = factory.openSession(true);
        ruleConfigMapper = sqlSession.getMapper(QualityRuleConfigMapper.class);

        List<QualityRule> implementations = List.of(
                new CompletenessRule(), new EncodingRule(), new FormatRule(),
                new LengthRule(), new DeduplicationRule());
        ruleService = new QualityRuleService(ruleConfigMapper, implementations);

        baselineTotal = count("SELECT COUNT(*) FROM quality_rule");
        cleanup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlSession == null) {
            return;
        }
        cleanup();
        System.out.printf("[baseline] quality_rule total %d -> %d%n",
                baselineTotal, count("SELECT COUNT(*) FROM quality_rule"));
        sqlSession.close();
        verificationConnection.close();
    }

    @BeforeEach
    void resetData() throws Exception {
        cleanup();
    }

    // ==================== 表结构与内置默认规则 ====================

    @Test
    @DisplayName("★ quality_rule 表已对运行库生效，且含内置四条默认规则（与改造前规则链等价）")
    void qualityRuleTable_shouldExist_withSeededDefaultRules() throws Exception {
        assertThat(tableExists("quality_rule")).isTrue();

        List<RuleConfig> active = ruleService.loadActiveRules();
        assertThat(active).extracting(RuleConfig::ruleType)
                .as("内置默认规则必须覆盖改造前的四条规则")
                .contains("completeness", "encoding", "format", "uniqueness");
    }

    @Test
    @DisplayName("params 为 JSON 列：写入/读回保持一致（JacksonTypeHandler 实库验证）")
    void insert_shouldRoundTripJsonParams() {
        QualityRuleConfig config = rule(TEST_PREFIX + "长度", "length",
                "name,title", Map.of("minLength", 2, "maxLength", 64));

        ruleConfigMapper.insert(config);

        assertThat(config.getId()).isNotNull();
        QualityRuleConfig loaded = ruleConfigMapper.selectById(config.getId());
        assertThat(loaded.getRuleName()).isEqualTo(TEST_PREFIX + "长度");
        assertThat(loaded.getRuleType()).isEqualTo("length");
        assertThat(loaded.getTargetFields()).isEqualTo("name,title");
        assertThat(loaded.getParams()).containsEntry("minLength", 2).containsEntry("maxLength", 64);
        assertThat(loaded.getEnabled()).isTrue();
        assertThat(loaded.getPriority()).isEqualTo(50);
        assertThat(loaded.getCreateTime()).isNotNull();
        assertThat(loaded.getUpdateTime()).isNotNull();
    }

    @Test
    @DisplayName("params 为空时写 NULL 且读回为 null（不产生 \"null\" 脏值）")
    void insert_shouldSupportNullParams() {
        QualityRuleConfig config = rule(TEST_PREFIX + "编码", "encoding", null, null);

        ruleConfigMapper.insert(config);

        QualityRuleConfig loaded = ruleConfigMapper.selectById(config.getId());
        assertThat(loaded.getParams()).isNull();
        assertThat(loaded.getTargetFields()).isNull();
    }

    // ==================== 动态加载：启用过滤与优先级 ====================

    @Test
    @DisplayName("★ 停用的规则不参与质检（SQL 过滤），启用规则按优先级升序返回")
    void loadActiveRules_shouldExcludeDisabled_andOrderByPriority() {
        QualityRuleConfig earlier = rule(TEST_PREFIX + "高优先级", "format", "prio-high",
                Map.of("pattern", "^A$"));
        earlier.setPriority(1);
        QualityRuleConfig later = rule(TEST_PREFIX + "低优先级", "format", "prio-low",
                Map.of("pattern", "^B$"));
        later.setPriority(300);
        QualityRuleConfig disabled = rule(TEST_PREFIX + "已停用", "format", "prio-disabled",
                Map.of("pattern", "^C$"));
        disabled.setEnabled(false);
        disabled.setPriority(0);

        ruleConfigMapper.insert(later);
        ruleConfigMapper.insert(earlier);
        ruleConfigMapper.insert(disabled);

        List<RuleConfig> active = ruleService.loadActiveRules();
        List<String> testTargetFields = active.stream()
                .flatMap(rule -> rule.targetFields().stream())
                .filter(field -> field.startsWith("prio-"))
                .toList();

        assertThat(testTargetFields)
                .as("停用规则不在启用规则中；启用规则按优先级升序（1 在 300 之前）")
                .containsExactly("prio-high", "prio-low");
    }

    // ==================== 更新 / 删除 ====================

    @Test
    @DisplayName("启停切换落库：update 后 enabled 变更且能影响动态加载结果")
    void update_shouldToggleEnabledInDatabase() {
        QualityRuleConfig config = rule(TEST_PREFIX + "待停用", "format", "toggle-me",
                Map.of("pattern", "^\\S+@\\S+$"));
        ruleConfigMapper.insert(config);

        long before = countEnabledRulesWithTarget("toggle-me");
        ruleService.setEnabled(config.getId(), false);

        QualityRuleConfig loaded = ruleConfigMapper.selectById(config.getId());
        assertThat(loaded.getEnabled()).isFalse();
        assertThat(countEnabledRulesWithTarget("toggle-me"))
                .as("停用后不再出现在启用规则中（动态加载结果随之变化）")
                .isEqualTo(before - 1);
    }

    @Test
    @DisplayName("删除规则：行被物理删除")
    void delete_shouldRemoveRow() {
        QualityRuleConfig config = rule(TEST_PREFIX + "待删除", "completeness", "email", null);
        ruleConfigMapper.insert(config);

        ruleService.deleteRule(config.getId());

        assertThat(ruleConfigMapper.selectById(config.getId())).isNull();
    }

    @Test
    @DisplayName("非法配置不落库（实库校验：未知类型被拒绝后行数不变）")
    void createRule_shouldNotPersistInvalidConfig() throws Exception {
        long before = count("SELECT COUNT(*) FROM quality_rule");
        QualityRuleConfig invalid = rule(TEST_PREFIX + "非法", "groovy-script", null, null);

        try {
            ruleService.createRule(invalid);
        } catch (RuntimeException ignored) {
            // 预期：BusinessException（不支持的规则类型）
        }

        assertThat(count("SELECT COUNT(*) FROM quality_rule")).isEqualTo(before);
    }

    // ==================== 辅助 ====================

    /** 统计启用规则中目标字段等于给定值的条数（用于断言启停切换影响动态加载） */
    private static long countEnabledRulesWithTarget(String targetField) {
        return ruleService.loadActiveRules().stream()
                .filter(rule -> rule.targetFields().contains(targetField))
                .count();
    }

    private static QualityRuleConfig rule(String name, String type, String targetFields,
                                          Map<String, Object> params) {
        QualityRuleConfig config = new QualityRuleConfig();
        config.setRuleName(name);
        config.setRuleType(type);
        config.setTargetFields(targetFields);
        config.setParams(params);
        config.setEnabled(true);
        config.setPriority(50);
        return config;
    }

    private static void cleanup() throws Exception {
        try (Statement statement = verificationConnection.createStatement()) {
            statement.executeUpdate(
                    "DELETE FROM quality_rule WHERE rule_name LIKE '" + TEST_PREFIX + "%'");
        }
    }

    private static boolean tableExists(String table) throws Exception {
        try (ResultSet rs = verificationConnection.getMetaData()
                .getTables(verificationConnection.getCatalog(), null, table, null)) {
            return rs.next();
        }
    }

    private static long count(String sql) throws Exception {
        try (Statement statement = verificationConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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
