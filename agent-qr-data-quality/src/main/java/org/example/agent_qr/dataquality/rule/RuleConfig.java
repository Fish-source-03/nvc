package org.example.agent_qr.dataquality.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单条质检规则的运行期配置（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 由 {@code quality_rule} 表的行转换而来：表只存<b>配置</b>（规则类型、目标字段、校验参数），
 * 具体判定逻辑仍由 {@link QualityRule} 的各实现类提供——这样既让规则可动态增删改，
 * 又不需要引入脚本引擎（保持可控与可测试）。
 * </p>
 * <p>
 * 不可变值对象：{@code targetFields} / {@code params} 在构造时做防御性拷贝，
 * 避免规则实现与调用方共享可变集合。
 * </p>
 *
 * @param ruleType     规则类型编码（对应 {@link QualityRule#getType()}）
 * @param targetFields 目标字段列表，为空表示由规则实现自行决定默认检查范围
 * @param params       校验参数（JSON 对象反序列化结果），无参数时为空 Map
 * @author agent-qr
 */
public record RuleConfig(String ruleType, List<String> targetFields, Map<String, Object> params) {

    /**
     * 紧凑构造器：null 归一化为空集合，并做防御性拷贝。
     * <p>
     * 注意不能用 {@code Map.copyOf}——参数 JSON 中允许出现 null 值。
     * </p>
     */
    public RuleConfig {
        targetFields = targetFields == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(targetFields));
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /**
     * 取字符串型参数。
     *
     * @param key 参数名
     * @return 参数值（转为字符串）；不存在或为 null 时返回 null
     */
    public String stringParam(String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 取整型参数。
     *
     * @param key 参数名
     * @return 参数值；不存在、为 null 或无法解析为整数时返回 null
     */
    public Integer intParam(String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
