package org.example.agent_qr.dataquality.checker;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.dataquality.entity.QualityFailure;
import org.example.agent_qr.dataquality.entity.QualityReport;
import org.example.agent_qr.dataquality.entity.RuleResult;
import org.example.agent_qr.dataquality.rule.CompletenessRule;
import org.example.agent_qr.dataquality.rule.DeduplicationRule;
import org.example.agent_qr.dataquality.rule.EncodingRule;
import org.example.agent_qr.dataquality.rule.FormatRule;
import org.example.agent_qr.dataquality.rule.QualityRule;
import org.example.agent_qr.dataquality.rule.RuleConfig;
import org.example.agent_qr.dataquality.service.QualityRuleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据质量检查器 — 规则链引擎。
 * <p>
 * 按顺序执行规则链（完整性 → 编码 → 格式 → 重复检测），
 * 统计通过率并与阻断阈值比较。当 passRate {@code <} blockThreshold 时标记为阻断。
 * </p>
 * <p>
 * <b>批次 10 · 任务 10.1（问题 35）的改造</b>：规则来源由"编译期固定的
 * {@code List<QualityRule>} Bean 注入"改为"<b>每次质检从 {@code quality_rule} 表动态加载</b>"
 * （见 {@link QualityRuleService#loadActiveRules()}），并按 {@code ruleType} 分派到对应实现类：
 * </p>
 * <ul>
 *   <li>存进数据库的只有<b>配置</b>（类型/目标字段/阈值/启用状态/优先级），判定逻辑仍在
 *       {@code rule} 包的实现类中——不引入脚本引擎；</li>
 *   <li>表为空（尚未配置）或加载异常时，回退到<b>内置默认四条规则</b>，
 *       保证与改造前行为一致；</li>
 *   <li>表非空但全部停用时，规则链为空、不再执行任何检查（"禁用的规则不参与质检"）。</li>
 * </ul>
 * <p>
 * <b>批次 10 · 任务 10.4（问题 26）的改造</b>：失败明细由"跨记录 MD5 去重（只留首次出现的
 * recordIndex）"改为"<b>按（规则 + 原因）聚合，保留 recordIndex 列表 + 总数</b>"，
 * 使报告能回答"具体哪几条记录失败"；同时为明细条目数与单条索引数设置上限，控制 JSON 体积。
 * </p>
 * <p>
 * <b>批次 11 · R43 的收敛</b>：聚合键里的 reason 改为<b>纯模板</b>（不含随记录变化的取值），
 * 具体取值放 {@link QualityFailure#detail}；截断汇总明细的 {@code recordCount} 统一表示
 * "失败记录数"，未列出的失败种类数改用 {@link QualityFailure#omittedKindCount}。
 * 详见 {@link FailureAggregator}。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class DataQualityChecker {

    /**
     * 失败明细条目上限（按"规则 + 原因"聚合后的条数）。
     * <p>
     * 体积控制（任务 10.4.5）：单条明细最多 {@link QualityFailure#MAX_RECORD_INDICES} 个索引，
     * 条目数上限 {@value}，因此最坏情况下 JSON 规模有界；超出部分记录一条"截断"汇总明细，
     * 不会静默丢失信息。
     * </p>
     */
    public static final int MAX_FAILURE_ENTRIES = 200;

    /** 截断汇总明细的规则名（非真实规则，用于提示明细被截断） */
    public static final String TRUNCATION_RULE_NAME = "(明细截断)";

    /**
     * 截断汇总明细的规则类型标记（R43②）。
     * <p>机器可判别的类型标记（真实规则类型见各 {@code QualityRule#getType()}），
     * 便于前端/调用方识别"这是一条截断汇总"而不是一条真实规则的明细。</p>
     */
    public static final String TRUNCATION_RULE_TYPE = "truncated";

    /**
     * 内置默认规则（按改造前的规则链顺序）。
     * <p>仅在 {@code quality_rule} 表为空或加载异常时使用，保证"表未配置"不会导致质检空转。</p>
     */
    private static final List<RuleConfig> BUILT_IN_DEFAULT_RULES = List.of(
            new RuleConfig(CompletenessRule.TYPE, List.of(), Map.of()),
            new RuleConfig(EncodingRule.TYPE, List.of(), Map.of()),
            new RuleConfig(FormatRule.TYPE, List.of(), Map.of()),
            new RuleConfig(DeduplicationRule.TYPE, List.of(), Map.of())
    );

    /** 规则类型注册表：类型编码 → 实现（由 Spring 注入的全部实现类构建） */
    private final Map<String, QualityRule> ruleRegistry;

    /** 规则配置来源（quality_rule 表） */
    private final QualityRuleService qualityRuleService;

    /** 阻断阈值：通过率低于此值则阻断，默认 0.5 */
    @Value("${agent-qr.data-quality.block-threshold:0.5}")
    private double blockThreshold;

    /**
     * 构造器注入：规则实现列表（规则类型注册表）+ 规则配置服务。
     *
     * @param rules              全部 {@link QualityRule} 实现（Spring 注入）
     * @param qualityRuleService 规则配置服务（动态加载 quality_rule）
     */
    public DataQualityChecker(List<QualityRule> rules, QualityRuleService qualityRuleService) {
        this.qualityRuleService = qualityRuleService;
        Map<String, QualityRule> registry = new LinkedHashMap<>();
        for (QualityRule rule : rules) {
            QualityRule previous = registry.put(rule.getType(), rule);
            if (previous != null) {
                log.warn("规则类型编码重复（后者覆盖前者）: type={}, 已注册={}, 被覆盖={}",
                        rule.getType(), previous.getClass().getSimpleName(),
                        rule.getClass().getSimpleName());
            }
        }
        this.ruleRegistry = Collections.unmodifiableMap(registry);
        log.info("质检规则类型注册完成: {}", ruleRegistry.keySet());
    }

    /**
     * 对同步数据执行质量检查。
     *
     * @param batchId      同步批次 ID
     * @param datasourceId 数据源配置 ID
     * @param sourceName   数据源名称
     * @param rawData      原始数据（记录列表）
     * @return 质量检查报告
     */
    public QualityReport check(String batchId, Long datasourceId, String sourceName,
                               List<Map<String, Object>> rawData) {
        if (rawData == null || rawData.isEmpty()) {
            QualityReport emptyReport = new QualityReport();
            emptyReport.setBatchId(batchId);
            emptyReport.setDatasourceId(datasourceId);
            emptyReport.setSourceName(sourceName);
            emptyReport.setTotal(0);
            emptyReport.setRate(1.0);
            emptyReport.setBlocked(false);
            emptyReport.setCheckTime(LocalDateTime.now());
            return emptyReport;
        }

        // 0. 动态加载规则（生效时机：每次质检实时读取，规则变更下次质检即生效）
        List<RuleConfig> activeRules = loadActiveRules(batchId);

        // 0.1 初始化去重规则（仅当规则链中确实包含重复检测时，才加载历史指纹）
        QualityRule dedupRule = ruleRegistry.get(DeduplicationRule.TYPE);
        boolean dedupActive = activeRules.stream()
                .anyMatch(config -> DeduplicationRule.TYPE.equals(config.ruleType()));
        if (dedupActive && dedupRule instanceof DeduplicationRule dedup) {
            dedup.reset(datasourceId);
        }

        try {
            FailureAggregator aggregator = new FailureAggregator();
            Set<Integer> failedRecordIndices = new HashSet<>();
            int passCount = 0;
            int failCount = 0;

            for (int i = 0; i < rawData.size(); i++) {
                Map<String, Object> record = rawData.get(i);
                boolean recordPassed = true;

                // 规则链顺序执行（顺序由 quality_rule.priority 决定）
                for (RuleConfig config : activeRules) {
                    QualityRule rule = ruleRegistry.get(config.ruleType());
                    if (rule == null) {
                        log.warn("规则类型无对应实现，已跳过: type={}", config.ruleType());
                        continue;
                    }
                    RuleResult result = rule.evaluate(record, config);
                    if (!result.isPassed()) {
                        aggregator.add(rule.getName(), rule.getType(),
                                result.getReason(), result.getDetail(), i);
                        recordPassed = false;
                    }
                }

                if (recordPassed) {
                    passCount++;
                } else {
                    failCount++;
                    failedRecordIndices.add(i);
                }
            }

            List<QualityFailure> failures = aggregator.toFailures();

            int total = rawData.size();
            double passRate = (double) passCount / total;
            boolean blocked = passRate < blockThreshold;

            if (blocked) {
                log.warn("数据质量检查阻断: batchId={}, passRate={}/{}={}, threshold={}",
                        batchId, passCount, total, String.format("%.2f", passRate), blockThreshold);
            } else {
                log.info("数据质量检查通过: batchId={}, passRate={}/{}={}",
                        batchId, passCount, total, String.format("%.2f", passRate));
            }

            QualityReport report = new QualityReport(batchId, total, passCount, failCount,
                    passRate, blocked, failures);
            report.setDatasourceId(datasourceId);
            report.setSourceName(sourceName);
            report.setFailedIndices(failedRecordIndices);
            return report;
        } finally {
            // 清理去重规则的 ThreadLocal，防止内存泄漏
            if (dedupRule instanceof DeduplicationRule dedup) {
                dedup.clear();
            }
        }
    }

    /**
     * 加载本次质检使用的规则链。
     * <p>
     * 三种情形：
     * </p>
     * <ol>
     *   <li>表中有启用规则 → 使用表中的配置（按优先级升序）；</li>
     *   <li>表非空但全部停用 → 返回空链（禁用规则不参与质检，属用户显式选择）；</li>
     *   <li>表为空（尚未配置）或读取异常 → 回退内置默认四条规则，保证与改造前行为一致。</li>
     * </ol>
     *
     * @param batchId 同步批次 ID（仅用于日志）
     * @return 规则链（可为空列表）
     */
    private List<RuleConfig> loadActiveRules(String batchId) {
        List<RuleConfig> activeRules;
        try {
            activeRules = qualityRuleService.loadActiveRules();
            if (!activeRules.isEmpty()) {
                return activeRules;
            }
            if (qualityRuleService.listRules().isEmpty()) {
                log.info("quality_rule 表为空，使用内置默认规则链（4 条）: batchId={}", batchId);
                return BUILT_IN_DEFAULT_RULES;
            }
            log.info("quality_rule 表中无启用规则，本次质检不执行任何检查: batchId={}", batchId);
            return List.of();
        } catch (RuntimeException e) {
            log.warn("质检规则加载失败，回退内置默认规则链: batchId={}, error={}", batchId, e.getMessage());
            return BUILT_IN_DEFAULT_RULES;
        }
    }

    /**
     * 失败明细聚合器（批次 10 · 任务 10.4.3 / 10.4.5；批次 11 · R43 收敛语义）。
     * <p>
     * 按（规则名 + 原因<b>模板</b>）聚合：同一原因的 N 条失败记录合并为一条明细，
     * {@code recordCount} 记失败记录数、{@code recordIndices} 保留前
     * {@link QualityFailure#MAX_RECORD_INDICES} 个索引；条目总数超过
     * {@link #MAX_FAILURE_ENTRIES} 时停止新增并追加一条截断汇总明细。
     * </p>
     * <p>
     * <b>R43 的两点约束</b>：
     * </p>
     * <ol>
     *   <li>聚合键依赖 {@code reason} 是模板（不含具体取值，见 {@code RuleResult}）——
     *       具体取值放 {@link QualityFailure#detail}（保留首次出现的样例）。
     *       因此明细条数只与"规则配置数量"相关，<b>与数据量/数据取值无关</b>；
     *       修复前 reason 拼了取值（如"长度 7"），300 条不同长度即打满 200 条上限。</li>
     *   <li>截断汇总明细的字段语义不再重载：{@code recordCount} = 未列出的失败<b>记录数</b>
     *       （与正常明细同单位），"未列出的失败<b>种类数</b>"单独放在
     *       {@link QualityFailure#omittedKindCount}，并以
     *       {@link #TRUNCATION_RULE_TYPE} 作为机器可判别的类型标记。</li>
     * </ol>
     */
    private static final class FailureAggregator {

        private final Map<String, QualityFailure> entries = new LinkedHashMap<>();

        /** 因超出条目上限而未列出的失败种类（按"规则+原因"键去重，R43②） */
        private final Set<String> omittedKeys = new LinkedHashSet<>();

        /** 因超出条目上限而未列出的失败记录数（按"规则+记录"计，与 recordCount 同单位） */
        private int omittedRecords = 0;

        /**
         * 记录一次失败。
         *
         * @param ruleName    规则名称
         * @param ruleType    规则类型编码
         * @param reason      失败原因模板（不得含具体记录取值，见 R43）
         * @param detail      首条失败记录的具体取值样例（可为 null）
         * @param recordIndex 记录索引
         */
        void add(String ruleName, String ruleType, String reason, String detail, int recordIndex) {
            String key = ruleName + "|" + reason;
            QualityFailure existing = entries.get(key);
            if (existing != null) {
                existing.addRecordIndex(recordIndex);
                return;
            }
            if (entries.size() >= MAX_FAILURE_ENTRIES) {
                omittedKeys.add(key);   // Set 去重 → 种类数（同一种类的后续记录不再重复计数）
                omittedRecords++;       // 每次失败调用 = 一条失败记录
                return;
            }
            QualityFailure failure = new QualityFailure(ruleName, recordIndex, reason);
            failure.setRuleType(ruleType);
            failure.setDetail(detail);
            entries.put(key, failure);
        }

        /**
         * 输出明细列表（含截断汇总）。
         *
         * @return 明细列表
         */
        List<QualityFailure> toFailures() {
            List<QualityFailure> failures = new ArrayList<>(entries.values());
            if (!omittedKeys.isEmpty()) {
                log.warn("失败明细条目超过上限 {}，另有 {} 类失败（共 {} 条失败记录）未列出",
                        MAX_FAILURE_ENTRIES, omittedKeys.size(), omittedRecords);
                QualityFailure truncated = new QualityFailure(TRUNCATION_RULE_NAME, 0,
                        String.format("失败明细条目超过上限 %d，另有 %d 类失败（共 %d 条失败记录）未列出；"
                                        + "失败记录总数见报告统计",
                                MAX_FAILURE_ENTRIES, omittedKeys.size(), omittedRecords));
                // R43②：recordCount 统一表示"失败记录数"；"种类数"用独立字段表达
                truncated.setRecordCount(omittedRecords);
                truncated.setOmittedKindCount(omittedKeys.size());
                truncated.setRuleType(TRUNCATION_RULE_TYPE);
                failures.add(truncated);
            }
            return failures;
        }
    }
}
