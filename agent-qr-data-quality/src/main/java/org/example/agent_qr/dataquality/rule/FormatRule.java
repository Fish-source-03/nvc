package org.example.agent_qr.dataquality.rule;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.dataquality.entity.RuleResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 格式检查规则（规则类型 {@code format}）。
 * <p>
 * 检查记录中日期字段（yyyy-MM-dd 格式）和数字字段的格式合法性。
 * 百分比字段值应在 [0, 100] 范围内。
 * </p>
 * <p>
 * <b>批次 10 · 任务 10.1（问题 35）</b>：新增"正则表达式"配置——当
 * {@code quality_rule.params.pattern} 与目标字段均配置时，按正则校验这些字段的值
 * （空值跳过，由完整性规则负责）；未配置 pattern 时行为与改造前完全一致（启发式字段名匹配）。
 * 正则表达式在入库前由 {@code QualityRuleService} 校验可编译性。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class FormatRule implements QualityRule {

    /** 规则类型编码 */
    public static final String TYPE = "format";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 已编译的正则缓存（键为 pattern 原文，条数受规则配置数约束） */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "格式";
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public RuleResult evaluate(Map<String, Object> record) {
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String key = entry.getKey().toLowerCase();
            Object value = entry.getValue();

            if (value == null) {
                continue;
            }

            String strValue = value.toString().trim();
            if (strValue.isEmpty()) {
                continue;
            }

            // 日期字段检查
            if (key.contains("date") || key.contains("time") || key.contains("_at")) {
                try {
                    // 尝试解析 ISO 日期时间格式
                    if (strValue.contains("T")) {
                        LocalDate.parse(strValue.substring(0, 10), DATE_FORMATTER);
                    } else {
                        LocalDate.parse(strValue, DATE_FORMATTER);
                    }
                } catch (DateTimeParseException e) {
                    return RuleResult.fail(
                            String.format("字段 '%s' 的值 '%s' 不是合法的日期格式(yyyy-MM-dd)",
                                    entry.getKey(), strValue));
                }
            }

            // 数字字段检查
            if (key.contains("amount") || key.contains("price") || key.contains("salary")
                    || key.contains("number") || key.contains("count") || key.contains("size")) {
                try {
                    new BigDecimal(strValue.replace(",", ""));
                } catch (NumberFormatException e) {
                    return RuleResult.fail(
                            String.format("字段 '%s' 的值 '%s' 不是合法的数字格式",
                                    entry.getKey(), strValue));
                }
            }

            // 百分比字段检查
            if (key.contains("percent") || key.contains("rate") || key.contains("ratio")) {
                try {
                    double percent = Double.parseDouble(strValue.replace("%", ""));
                    if (percent < 0 || percent > 100) {
                        return RuleResult.fail(
                                String.format("字段 '%s' 的值 '%s' 不在 [0, 100] 范围内",
                                        entry.getKey(), strValue));
                    }
                } catch (NumberFormatException e) {
                    return RuleResult.fail(
                            String.format("字段 '%s' 的值 '%s' 不是合法的百分比格式",
                                    entry.getKey(), strValue));
                }
            }
        }

        return RuleResult.pass();
    }

    /**
     * 带配置的检查（批次 10）：配置了正则与目标字段时按正则校验。
     */
    @Override
    public RuleResult evaluate(Map<String, Object> record, RuleConfig config) {
        String patternText = config == null ? null : config.stringParam("pattern");
        if (patternText == null || patternText.isBlank()
                || config.targetFields().isEmpty()) {
            // 未配置正则（或未指定目标字段）→ 沿用改造前的启发式检查
            return evaluate(record);
        }

        Pattern pattern = patternCache.computeIfAbsent(patternText, FormatRule::compilePattern);
        if (pattern == null) {
            // 理论上被入库校验拦住；兜底为"不判失败"，并留下 WARN
            log.warn("格式规则的正则表达式无法编译，已跳过: pattern={}", patternText);
            return RuleResult.pass();
        }

        for (String field : config.targetFields()) {
            Object value = record.get(field);
            if (value == null) {
                continue;
            }
            String strValue = value.toString().trim();
            if (strValue.isEmpty()) {
                continue;
            }
            if (!pattern.matcher(strValue).matches()) {
                return RuleResult.fail(String.format(
                        "字段 '%s' 的值 '%s' 不符合正则表达式 %s", field, strValue, patternText));
            }
        }

        return RuleResult.pass();
    }

    /**
     * 编译正则；非法正则返回 null（不抛异常）。
     *
     * @param patternText 正则原文
     * @return 编译后的 Pattern；非法时为 null
     */
    private static Pattern compilePattern(String patternText) {
        try {
            return Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
