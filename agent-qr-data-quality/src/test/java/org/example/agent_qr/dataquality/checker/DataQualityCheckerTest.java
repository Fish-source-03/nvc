package org.example.agent_qr.dataquality.checker;

import org.example.agent_qr.dataquality.context.RuleExecutionContext;
import org.example.agent_qr.dataquality.entity.QualityFailure;
import org.example.agent_qr.dataquality.entity.QualityReport;
import org.example.agent_qr.dataquality.rule.CompletenessRule;
import org.example.agent_qr.dataquality.rule.DeduplicationRule;
import org.example.agent_qr.dataquality.rule.EncodingRule;
import org.example.agent_qr.dataquality.rule.FormatRule;
import org.example.agent_qr.dataquality.rule.LengthRule;
import org.example.agent_qr.dataquality.rule.QualityRule;
import org.example.agent_qr.dataquality.rule.RuleConfig;
import org.example.agent_qr.dataquality.service.QualityRuleService;
import org.example.agent_qr.dataquality.util.CharsetDetector;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link DataQualityChecker} 测试（批次 10 · 任务 10.1 + 10.4）。
 * <p>
 * 覆盖两组关键回归/缺陷：
 * </p>
 * <ol>
 *   <li><b>问题 35（10.1）</b>：规则从数据库动态加载——新增「字段 X 非空」规则后
 *       质检确实按该规则判定（修复前规则写死在 Java 里，前端配置不参与质检）；
 *       表为空时回退内置四条规则，<b>行为与改造前一致</b>（回归）；</li>
 *   <li><b>问题 26（10.4.3/10.4.5）</b>：失败明细不再按 MD5 跨记录去重导致
 *       recordIndex 丢失——改为按「规则 + 原因」聚合、保留 recordIndices 列表 + 总数，
 *       并限制单条索引数与明细条目数；<b>阻断与过滤行为不受影响</b>（回归）。</li>
 * </ol>
 * <p>
 * 数据源 ID 使用测试专用值（998877），去重规则的历史指纹由 Mock 的 {@code ChunkMapper}
 * 返回空集合，测试不触碰真实数据库。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataQualityCheckerTest {

    /** 测试专用数据源 ID */
    private static final long TEST_DATASOURCE_ID = 998877L;

    /** 阻断阈值（与默认配置一致） */
    private static final double BLOCK_THRESHOLD = 0.5;

    @Mock
    private QualityRuleService qualityRuleService;

    private DataQualityChecker checker;
    private CompletenessRule completenessRule;
    private EncodingRule encodingRule;
    private FormatRule formatRule;
    private LengthRule lengthRule;
    private DeduplicationRule deduplicationRule;

    @BeforeEach
    void setUp() {
        completenessRule = new CompletenessRule();
        ReflectionTestUtils.setField(completenessRule, "ruleExecutionContext", new RuleExecutionContext());
        ReflectionTestUtils.setField(completenessRule, "globalContentFieldsConfig", "content,text,_content");

        encodingRule = new EncodingRule();
        ReflectionTestUtils.setField(encodingRule, "charsetDetector", new CharsetDetector());

        formatRule = new FormatRule();
        lengthRule = new LengthRule();

        deduplicationRule = new DeduplicationRule();
        ChunkMapper chunkMapper = org.mockito.Mockito.mock(ChunkMapper.class);
        lenient().when(chunkMapper.selectRecordHashesByDatasourceId(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(deduplicationRule, "chunkMapper", chunkMapper);

        checker = new DataQualityChecker(
                List.of(completenessRule, encodingRule, formatRule, lengthRule, deduplicationRule),
                qualityRuleService);
        ReflectionTestUtils.setField(checker, "blockThreshold", BLOCK_THRESHOLD);
    }

    // ==================== 任务 10.1：动态规则 ====================

    @Test
    @DisplayName("★ 新增「字段 email 非空」规则后，质检确实按该规则判定（拦住原缺陷：配置不参与质检）")
    void check_shouldApplyNewCompletenessRule_withConfiguredTargetFields() {
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("completeness", List.of("email"), Map.of())));

        List<Map<String, Object>> data = List.of(
                record("email", "zhang@x.com", "content", "第一位员工"),
                record("email", "", "content", "第二位员工"),
                record("email", null, "content", "第三位员工"));

        QualityReport report = checker.check("batch-new-rule", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(report.getTotal()).isEqualTo(3);
        assertThat(report.getPass()).isEqualTo(1);
        assertThat(report.getFail()).isEqualTo(2);
        assertThat(report.getFailedIndices()).containsExactlyInAnyOrder(1, 2);

        assertThat(report.getFailures()).hasSize(1);
        QualityFailure failure = report.getFailures().get(0);
        assertThat(failure.getRuleName()).isEqualTo("完整性");
        assertThat(failure.getRuleType()).isEqualTo("completeness");
        assertThat(failure.getReason()).contains("email");
        assertThat(failure.getRecordCount()).isEqualTo(2);
        assertThat(failure.getRecordIndices()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("★ 表为空时回退内置默认规则链：四条规则行为与改造前一致（重要回归）")
    void check_shouldFallBackToBuiltInRules_whenRuleTableIsEmpty() {
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of());
        when(qualityRuleService.listRules()).thenReturn(List.of());

        List<Map<String, Object>> data = List.of(
                record("content", "有效内容", "name", "张三"),
                record("content", "", "text", "   "),
                record("content", "另一条内容", "order_date", "2024-13-99"),
                record("content", "有效内容", "name", "张三"));

        QualityReport report = checker.check("batch-default-chain", TEST_DATASOURCE_ID, "测试源", data);

        // 逐记录判定（改造前的语义）：
        // 0 通过；1 完整性失败（内容字段全空）；2 格式失败（非法日期）；3 与 0 重复（重复检测失败）
        assertThat(report.getTotal()).isEqualTo(4);
        assertThat(report.getPass()).isEqualTo(1);
        assertThat(report.getFail()).isEqualTo(3);
        assertThat(report.getRate()).isEqualTo(0.25);
        assertThat(report.isBlocked()).isTrue();
        assertThat(report.getFailedIndices()).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(report.getFailures()).extracting(QualityFailure::getRuleName)
                .containsExactlyInAnyOrder("完整性", "格式", "重复检测");
    }

    @Test
    @DisplayName("★ 回归：内置默认规则链的逐记录判定与「改造前」手工规则链完全一致")
    void check_shouldMatchLegacyManualRuleChain() {
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of());
        when(qualityRuleService.listRules()).thenReturn(List.of());

        List<Map<String, Object>> data = buildMixedDataset();

        QualityReport report = checker.check("batch-legacy-compare", TEST_DATASOURCE_ID, "测试源", data);
        Set<Integer> legacyFailedIndices = runLegacyManualChain(data);

        assertThat(report.getFailedIndices()).isEqualTo(legacyFailedIndices);
        assertThat(report.getPass()).isEqualTo(data.size() - legacyFailedIndices.size());
        assertThat(report.getFail()).isEqualTo(legacyFailedIndices.size());
        assertThat(report.isBlocked())
                .isEqualTo((double) report.getPass() / data.size() < BLOCK_THRESHOLD);
    }

    @Test
    @DisplayName("★ 回归：从表中加载的四条默认规则（无目标字段/无参数）判定与内置默认链一致")
    void check_shouldBehaveLikeBuiltInChain_whenDefaultRulesLoadedFromTable() {
        // p2-schema.sql 种子的四条规则：target_fields 与 params 均为 NULL
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("completeness", null, null),
                new RuleConfig("encoding", null, null),
                new RuleConfig("format", null, null),
                new RuleConfig("uniqueness", null, null)));

        List<Map<String, Object>> data = buildMixedDataset();
        QualityReport fromTable = checker.check("batch-seeded", TEST_DATASOURCE_ID, "测试源", data);

        when(qualityRuleService.loadActiveRules()).thenReturn(List.of());
        when(qualityRuleService.listRules()).thenReturn(List.of());
        QualityReport fromBuiltIn = checker.check("batch-builtin", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(fromTable.getPass()).isEqualTo(fromBuiltIn.getPass());
        assertThat(fromTable.getFail()).isEqualTo(fromBuiltIn.getFail());
        assertThat(fromTable.isBlocked()).isEqualTo(fromBuiltIn.isBlocked());
        assertThat(fromTable.getFailedIndices()).isEqualTo(fromBuiltIn.getFailedIndices());
        assertThat(fromTable.getFailures()).extracting(QualityFailure::getRuleName)
                .containsExactlyElementsOf(
                        fromBuiltIn.getFailures().stream().map(QualityFailure::getRuleName).toList());
    }

    @Test
    @DisplayName("服务层未返回的规则（已停用/未配置）不参与质检")
    void check_shouldSkipRulesNotReturnedByService() {
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("completeness", List.of("content"), Map.of())));

        // 该记录含非法日期与非法数字：若格式规则参与则必然失败
        List<Map<String, Object>> data = List.of(
                record("content", "有内容", "order_date", "not-a-date", "amount", "abc"));

        QualityReport report = checker.check("batch-only-completeness", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(report.getFail()).isZero();
        assertThat(report.getFailures()).isEmpty();
        assertThat(report.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("停用的规则不参与质检：表中无启用规则时规则链为空（不做任何检查）")
    void check_shouldRunNoRules_whenAllRulesDisabled() {
        // 表非空但全部停用：loadActiveRules 为空，listRules 非空 —— 属用户显式选择，不回退默认
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of());
        when(qualityRuleService.listRules()).thenReturn(List.of(
                new org.example.agent_qr.dataquality.entity.QualityRuleConfig()));

        List<Map<String, Object>> data = List.of(record("content", ""));

        QualityReport report = checker.check("batch-all-disabled", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(report.getFail()).isZero();
        assertThat(report.getFailures()).isEmpty();
        assertThat(report.getPass()).isEqualTo(1);
    }

    @Test
    @DisplayName("长度规则（新类型）按配置目标字段与区间判定")
    void check_shouldApplyLengthRule_withConfiguredRange() {
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("length", List.of("name"), Map.of("minLength", 2, "maxLength", 4))));

        List<Map<String, Object>> data = List.of(
                record("name", "A"),
                record("name", "ABCD"),
                record("name", "ABCDE"));

        QualityReport report = checker.check("batch-length", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(report.getFailedIndices()).containsExactlyInAnyOrder(0, 2);
        assertThat(report.getFailures()).hasSize(2);
        assertThat(report.getFailures()).extracting(QualityFailure::getRuleType)
                .containsOnly("length");
    }

    @Test
    @DisplayName("编码规则按配置的期望编码判定（默认 UTF-8 之外可配置）")
    void check_shouldApplyEncodingRule_withConfiguredCharset() {
        // 使用足够长的样本：juniversalchardet 对极短非 ASCII 样本会误判为单字节编码
        byte[] gbkBytes = "本数据源用于员工信息管理，包含姓名、部门、岗位与入职时间等字段。"
                .getBytes(Charset.forName("GBK"));

        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("encoding", List.of(), Map.of("charset", "GBK"))));
        QualityReport gbkExpected = checker.check("batch-charset-gbk", TEST_DATASOURCE_ID, "测试源",
                List.of(record("raw", gbkBytes)));
        assertThat(gbkExpected.getFail()).isZero();

        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("encoding", List.of(), Map.of("charset", "UTF-8"))));
        QualityReport utf8Expected = checker.check("batch-charset-utf8", TEST_DATASOURCE_ID, "测试源",
                List.of(record("raw", gbkBytes)));
        assertThat(utf8Expected.getFail()).isEqualTo(1);
        assertThat(utf8Expected.getFailures().get(0).getReason())
                .as("GB 族编码不满足期望的 UTF-8")
                .contains("与期望编码 UTF-8 不符");
    }

    // ==================== 任务 10.4：失败明细 ====================

    @Test
    @DisplayName("★ 万条同因失败不再只留 1 条：聚合保留 recordIndices（上限 100）+ 总数（拦住原缺陷）")
    void check_shouldKeepRecordIndices_forMassFailuresOnSameRule() {
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("completeness", List.of("email"), Map.of())));

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            data.add(record("email", "", "seq", i));
        }

        QualityReport report = checker.check("batch-mass-failure", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(report.getFail()).isEqualTo(10000);
        assertThat(report.getFailures()).hasSize(1);

        QualityFailure failure = report.getFailures().get(0);
        assertThat(failure.getRecordCount())
                .as("明确记录失败总数，而非去重后只剩 1 条")
                .isEqualTo(10000);
        assertThat(failure.getRecordIndices())
                .as("保留具体失败记录索引（上限 %d）", QualityFailure.MAX_RECORD_INDICES)
                .hasSize(QualityFailure.MAX_RECORD_INDICES);
        assertThat(failure.getRecordIndices().get(0)).isZero();
        assertThat(failure.getRecordIndices().get(QualityFailure.MAX_RECORD_INDICES - 1))
                .isEqualTo(QualityFailure.MAX_RECORD_INDICES - 1);
        assertThat(failure.getRecordIndex()).isZero();

        assertThat(report.getFailedIndices())
                .as("内部过滤用的索引集合保持完整（阻断/过滤行为不受明细限额影响）")
                .hasSize(10000);
    }

    @Test
    @DisplayName("失败明细条目数不超过上限，超出部分以截断汇总明细显式提示（体积保护）")
    void check_shouldCapFailureEntryCount() {
        int records = DataQualityChecker.MAX_FAILURE_ENTRIES + 50;
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("format", List.of("code"), Map.of("pattern", "^OK$"))));

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            // 每条记录的失败原因不同（值被拼进 reason）→ 逐条成为独立明细
            data.add(record("code", "bad-" + i));
        }

        QualityReport report = checker.check("batch-entry-cap", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(report.getFailures())
                .hasSize(DataQualityChecker.MAX_FAILURE_ENTRIES + 1);
        QualityFailure last = report.getFailures().get(report.getFailures().size() - 1);
        assertThat(last.getRuleName()).isEqualTo(DataQualityChecker.TRUNCATION_RULE_NAME);
        assertThat(last.getReason()).contains("50");
        assertThat(report.getFail())
                .as("统计口径不受明细截断影响")
                .isEqualTo(records);
    }

    @Test
    @DisplayName("★ 回归：阻断行为不受明细聚合改造影响（低于阈值仍阻断）")
    void check_shouldStillBlock_whenPassRateBelowThreshold() {
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("completeness", List.of("content"), Map.of())));

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            data.add(record("content", i < 6 ? "" : "有效内容"));
        }

        QualityReport report = checker.check("batch-blocked", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(report.getRate()).isEqualTo(0.4);
        assertThat(report.isBlocked()).isTrue();
        assertThat(report.getPass()).isEqualTo(4);
        assertThat(report.getFail()).isEqualTo(6);
        assertThat(report.getFailedIndices()).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
        assertThat(report.getFailures().get(0).getRecordIndices())
                .containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("回归：通过率等于阈值时不阻断（严格小于才阻断）")
    void check_shouldNotBlock_whenPassRateEqualsThreshold() {
        when(qualityRuleService.loadActiveRules()).thenReturn(List.of(
                new RuleConfig("completeness", List.of("content"), Map.of())));

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            data.add(record("content", i < 5 ? "" : "有效内容"));
        }

        QualityReport report = checker.check("batch-threshold-equal", TEST_DATASOURCE_ID, "测试源", data);

        assertThat(report.getRate()).isEqualTo(0.5);
        assertThat(report.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("回归：空数据返回空报告（不阻断、通过率 1.0）")
    void check_shouldReturnEmptyReport_forEmptyData() {
        QualityReport report = checker.check("batch-empty", TEST_DATASOURCE_ID, "测试源", List.of());

        assertThat(report.getTotal()).isZero();
        assertThat(report.getRate()).isEqualTo(1.0);
        assertThat(report.isBlocked()).isFalse();
        assertThat(report.getFailures()).isEmpty();
    }

    // ==================== 辅助 ====================

    /**
     * 构造一张覆盖四条内置规则的混合数据集（含各种失败与通过形态）。
     */
    private static List<Map<String, Object>> buildMixedDataset() {
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(record("content", "第一条内容", "name", "张三"));
        data.add(record("content", "", "text", ""));
        data.add(record("content", "第二条内容", "order_date", "2024-13-99"));
        data.add(record("content", "第三条内容", "amount", "abc"));
        data.add(record("content", "第四条内容", "percent", "150"));
        data.add(record("raw", "含替换字符：� 的记录"));
        data.add(record("content", "第五条内容", "name", "李四"));
        data.add(record("content", "第一条内容", "name", "张三"));
        data.add(record("content", " ", "text", "  "));
        data.add(record("content", "第六条内容", "sign_date", "2024-01-02"));
        data.add(record("content", "第七条内容", "raw", "编码".getBytes(StandardCharsets.UTF_8)));
        data.add(record("content", "第五条内容", "name", "李四"));
        return data;
    }

    /**
     * 「改造前」的手工规则链：按内置顺序逐条记录执行四个规则 Bean，
     * 任一失败即该记录失败（不做明细聚合）。用于与改造后的报告做等价性对照。
     *
     * @param data 数据集
     * @return 失败记录索引集合
     */
    private Set<Integer> runLegacyManualChain(List<Map<String, Object>> data) {
        Set<Integer> failed = new HashSet<>();
        deduplicationRule.reset(TEST_DATASOURCE_ID);
        try {
            for (int i = 0; i < data.size(); i++) {
                boolean passed = true;
                for (QualityRule rule : List.of(completenessRule, encodingRule, formatRule, deduplicationRule)) {
                    if (!rule.evaluate(data.get(i)).isPassed()) {
                        passed = false;
                    }
                }
                if (!passed) {
                    failed.add(i);
                }
            }
        } finally {
            deduplicationRule.clear();
        }
        return failed;
    }

    /**
     * 构造单条记录（按可变的键值对）。
     */
    private static Map<String, Object> record(Object... keyValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            record.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return record;
    }
}
