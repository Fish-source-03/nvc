package org.example.agent_qr.dataquality.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条规则检查结果。
 * <p>
 * <b>批次 11 · R43：reason / detail 的职责划分（聚合键约束）</b>
 * </p>
 * <p>
 * 失败明细按「规则名 + {@code reason}」聚合，因此 {@link #reason} <b>必须是模板</b>：
 * 同一规则配置对任意记录都产出同一字符串，<b>不得拼入随记录变化的取值</b>
 * （具体数值、字段名、实际值、检测出的编码、替换字符个数等）——
 * 否则每条记录各成一条明细，数据量一大就把明细条目上限撑爆（R43① 的根因）。
 * 随记录变化的信息一律放入 {@link #detail}，由聚合器保留首次出现的样例。
 * </p>
 *
 * @author agent-qr
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleResult {

    /** 是否通过检查 */
    private boolean passed;

    /**
     * 失败原因<b>模板</b>（通过时为空字符串）。
     * <p>
     * 参与「规则名 + reason」聚合成败明细，因此只能包含与规则配置相关的稳定内容
     * （如阈值、正则、期望编码），不得包含具体记录取值。
     * </p>
     */
    private String reason;

    /**
     * 失败详情<b>样例</b>（可为 null）：首条失败记录的具体取值信息
     * （如 {@code 字段 'name' 长度 1}），供排查用，不参与聚合。
     */
    private String detail;

    /**
     * 创建通过的结果。
     */
    public static RuleResult pass() {
        return new RuleResult(true, "", null);
    }

    /**
     * 创建失败的结果（无详情）。
     *
     * @param reason 失败原因模板
     */
    public static RuleResult fail(String reason) {
        return new RuleResult(false, reason, null);
    }

    /**
     * 创建失败的结果（含首条记录的取值详情，R43）。
     *
     * @param reason 失败原因模板（不得含具体记录取值）
     * @param detail 具体取值样例（可为 null）
     */
    public static RuleResult fail(String reason, String detail) {
        return new RuleResult(false, reason, detail);
    }
}
