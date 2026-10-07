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
                        aggregator.add(rule.getName(), rule.getType(), result.getReason(), i);
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
     * 失败明细聚合器（批次 10 · 任务 10.4.3 / 10.4.5）。
     * <p>
     * 按（规则名 + 原因）聚合：同一原因的 N 条失败记录合并为一条明细，
     * {@code recordCount} 记总数、{@code recordIndices} 保留前
     * {@link QualityFailure#MAX_RECORD_INDICES} 个索引；条目总数超过
     * {@link #MAX_FAILURE_ENTRIES} 时停止新增并追加一条截断汇总明细。
     * </p>
     */
    private static final class FailureAggregator {

        private final Map<String, QualityFailure> entries = new LinkedHashMap<>();

        /** 因超出条目上限而未记录的明细种类数 */
        private int droppedKinds = 0;

        /**
         * 记录一次失败。
         *
         * @param ruleName    规则名称
         * @param ruleType    规则类型编码
         * @param reason      失败原因
         * @param recordIndex 记录索引
         */
        void add(String ruleName, String ruleType, String reason, int recordIndex) {
            String key = ruleName + "|" + reason;
            QualityFailure existing = entries.get(key);
            if (existing != null) {
                existing.addRecordIndex(recordIndex);
                return;
            }
            if (entries.size() >= MAX_FAILURE_ENTRIES) {
                droppedKinds++;
                return;
            }
            QualityFailure failure = new QualityFailure(ruleName, recordIndex, reason);
            failure.setRuleType(ruleType);
            entries.put(key, failure);
        }

        /**
         * 输出明细列表（含截断汇总）。
         *
         * @return 明细列表
         */
        List<QualityFailure> toFailures() {
            List<QualityFailure> failures = new ArrayList<>(entries.values());
            if (droppedKinds > 0) {
                log.warn("失败明细条目超过上限 {}，另有 {} 类失败未列出（记录总数见报告）",
                        MAX_FAILURE_ENTRIES, droppedKinds);
                QualityFailure truncated = new QualityFailure(TRUNCATION_RULE_NAME, 0,
                        String.format("失败明细条目超过上限 %d，另有 %d 类失败未列出；失败记录总数见报告统计",
                                MAX_FAILURE_ENTRIES, droppedKinds));
                truncated.setRecordCount(droppedKinds);
                failures.add(truncated);
            }
            return failures;
        }
    }
}
