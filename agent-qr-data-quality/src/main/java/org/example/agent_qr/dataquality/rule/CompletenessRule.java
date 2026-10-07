package org.example.agent_qr.dataquality.rule;

import org.example.agent_qr.dataquality.context.RuleExecutionContext;
import org.example.agent_qr.dataquality.entity.RuleResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 完整性检查规则（规则类型 {@code completeness}）。
 * <p>
 * 检查可配置的关键字段列表是否至少有一个非空。
 * 优先使用数据源级配置 {@code DataSourceConfig.contentFields}，
 * 未配置时回退到全局默认值 {@code agent-qr.data-quality.content-fields}。
 * </p>
 * <p>
 * <b>批次 10 · 任务 10.1（问题 35）</b>：新增"规则级目标字段"——{@code quality_rule.target_fields}
 * 配置的字段列表<b>优先</b>于数据源级与全局默认值，使前端新增的"字段 X 非空"规则能够真实生效。
 * 未配置目标字段时，取值链与改造前完全一致（数据源配置 → 全局默认）。
 * </p>
 *
 * @author agent-qr
 */
@Component
public class CompletenessRule implements QualityRule {

    /** 规则类型编码 */
    public static final String TYPE = "completeness";

    /** 全局默认内容字段名列表（逗号分隔），作为回退值 */
    @Value("${agent-qr.data-quality.content-fields:content,text,_content}")
    private String globalContentFieldsConfig;

    @Autowired
    private RuleExecutionContext ruleExecutionContext;

    @Override
    public String getName() {
        return "完整性";
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public RuleResult evaluate(Map<String, Object> record) {
        // 1. 优先使用数据源级配置
        String fieldsConfig = ruleExecutionContext.get(
                RuleExecutionContext.KEY_CONTENT_FIELDS, String.class);

        // 2. 数据源未配置时回退到全局默认值
        if (fieldsConfig == null || fieldsConfig.isBlank()) {
            fieldsConfig = globalContentFieldsConfig;
        }

        List<String> contentFields = Arrays.stream(fieldsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return checkFields(record, contentFields);
    }

    /**
     * 带配置的检查（批次 10）：{@code quality_rule.target_fields} 非空时以其为准。
     * <p>
     * 语义与改造前一致：<b>任一</b>目标字段非空即通过；全部为空则失败。
     * </p>
     */
    @Override
    public RuleResult evaluate(Map<String, Object> record, RuleConfig config) {
        if (config != null && !config.targetFields().isEmpty()) {
            return checkFields(record, config.targetFields());
        }
        return evaluate(record);
    }

    /**
     * 检查目标字段列表是否至少有一个非空。
     *
     * @param record        数据记录
     * @param contentFields 目标字段列表
     * @return 检查结果
     */
    private RuleResult checkFields(Map<String, Object> record, List<String> contentFields) {
        for (String field : contentFields) {
            Object value = record.get(field);
            if (value != null && !value.toString().isBlank()) {
                return RuleResult.pass();
            }
        }

        return RuleResult.fail("内容字段为空（检查字段: " + contentFields + " 均为空）");
    }
}
