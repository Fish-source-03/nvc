package org.example.agent_qr.dataquality.rule;

import org.example.agent_qr.dataquality.entity.RuleResult;

import java.util.Map;

/**
 * 数据质量检查规则接口。
 * <p>
 * 所有质量检查规则实现此接口，通过 evaluate 方法对单条记录执行检查。
 * 规则链按顺序执行：完整性 → 编码 → 格式。
 * </p>
 * <p>
 * <b>批次 10 · 任务 10.1（问题 35）的改造</b>：实现类从"编译期固定的规则 Bean"
 * 变为"<b>规则类型</b>的提供者"。具体的启用状态、目标字段与校验参数改由
 * {@code quality_rule} 表（见 {@link RuleConfig}）动态配置，由
 * {@code DataQualityChecker} 按 {@link #getType()} 分派。
 * </p>
 * <p>
 * ⚠️ 未重写 {@link #evaluate(Map, RuleConfig)} 的实现类，其行为与改造前<b>完全一致</b>
 * （默认忽略配置，委托给 {@link #evaluate(Map)}）。
 * </p>
 *
 * @author agent-qr
 */
public interface QualityRule {

    /**
     * 获取规则名称。
     *
     * @return 规则名称（如"完整性"、"编码"、"格式"）
     */
    String getName();

    /**
     * 获取规则类型编码（批次 10 新增）。
     * <p>
     * 该编码是 {@code quality_rule.rule_type} 列的取值，用于把数据库中的规则配置
     * 分派到对应的实现类。一经发布不应随意变更（会切断既有配置与实现的对应关系）。
     * </p>
     *
     * @return 规则类型编码（小写英文，如 "completeness"）
     */
    String getType();

    /**
     * 对单条记录执行质量检查。
     *
     * @param record 待检查的数据记录（字段名 → 字段值）
     * @return 检查结果（通过或失败 + 原因）
     */
    RuleResult evaluate(Map<String, Object> record);

    /**
     * 带动态配置的检查（批次 10 新增）。
     * <p>
     * 默认实现忽略配置并委托给 {@link #evaluate(Map)}——保证不支持配置的规则
     * （如重复检测）行为不变；支持配置的规则按需重写。
     * </p>
     *
     * @param record 待检查的数据记录
     * @param config 该规则在 {@code quality_rule} 表中的配置
     * @return 检查结果
     */
    default RuleResult evaluate(Map<String, Object> record, RuleConfig config) {
        return evaluate(record);
    }
}
