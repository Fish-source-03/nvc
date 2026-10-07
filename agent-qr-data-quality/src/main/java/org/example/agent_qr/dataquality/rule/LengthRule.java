package org.example.agent_qr.dataquality.rule;

import org.example.agent_qr.dataquality.entity.RuleResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 字段长度检查规则（规则类型 {@code length}，批次 10 · 任务 10.1 新增）。
 * <p>
 * 背景：规则管理页的规则类型清单（P3 原型）本就包含"字段长度限制"，
 * 但后端此前没有对应实现——若不补齐，前端新建的 length 规则将是一条
 * "永远通过"的假规则。本类补齐该类型，参数来自 {@code quality_rule}：
 * </p>
 * <ul>
 *   <li>{@code target_fields}（必填，逗号分隔）——需要校验长度的字段；</li>
 *   <li>{@code params.minLength} / {@code params.maxLength}（可选）——长度区间（字符数）。</li>
 * </ul>
 * <p>
 * 取值语义：仅校验<b>存在且非 null</b> 的字段——字段缺失/为 null 由完整性规则负责，
 * 避免同一问题被两条规则重复计失败；空串<b>参与</b>长度校验（长度为 0）。
 * 未配置任何阈值或目标字段时直接通过。
 * </p>
 * <p>
 * R43①（批次 11）：失败原因已<b>模板化</b>——reason 只含阈值（配置内容），
 * 字段名与实际长度进入 {@code RuleResult.detail}，否则"每条记录长度不同"
 * 会让明细条目数随数据量线性增长（300 条即触发 200 条明细上限）。
 * </p>
 *
 * @author agent-qr
 */
@Component
public class LengthRule implements QualityRule {

    /** 规则类型编码 */
    public static final String TYPE = "length";

    /** 校验参数名：最小长度 */
    public static final String PARAM_MIN_LENGTH = "minLength";

    /** 校验参数名：最大长度 */
    public static final String PARAM_MAX_LENGTH = "maxLength";

    @Override
    public String getName() {
        return "长度";
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 无配置时无判定依据，直接通过（本规则仅在配置了目标字段与阈值时才生效）。
     */
    @Override
    public RuleResult evaluate(Map<String, Object> record) {
        return RuleResult.pass();
    }

    @Override
    public RuleResult evaluate(Map<String, Object> record, RuleConfig config) {
        if (config == null || config.targetFields().isEmpty()) {
            return RuleResult.pass();
        }
        Integer minLength = config.intParam(PARAM_MIN_LENGTH);
        Integer maxLength = config.intParam(PARAM_MAX_LENGTH);
        if (minLength == null && maxLength == null) {
            return RuleResult.pass();
        }

        for (String field : config.targetFields()) {
            Object value = record.get(field);
            if (value == null) {
                continue;
            }
            int length = value.toString().length();
            // R43①：reason 只保留"与规则配置相关"的稳定内容（阈值），
            // 字段名与实际长度（随记录变化）放入 detail——否则每条不同长度的记录
            // 都会各成一条明细，撑爆明细条目上限。
            if (minLength != null && length < minLength) {
                return RuleResult.fail(
                        String.format("字段长度小于最小长度 %d", minLength),
                        String.format("字段 '%s' 长度 %d", field, length));
            }
            if (maxLength != null && length > maxLength) {
                return RuleResult.fail(
                        String.format("字段长度超过最大长度 %d", maxLength),
                        String.format("字段 '%s' 长度 %d", field, length));
            }
        }

        return RuleResult.pass();
    }
}
