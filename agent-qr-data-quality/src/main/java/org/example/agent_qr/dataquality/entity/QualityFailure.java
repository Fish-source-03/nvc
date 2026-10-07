package org.example.agent_qr.dataquality.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 质检失败明细 DTO（方案 B：作为 {@code quality_report.failures} JSON 列的元素类型）。
 * <p>
 * <b>批次 10 · 任务 10.4（问题 26）的定性与改造</b>：
 * </p>
 * <ol>
 *   <li><b>正式化为 DTO</b>：本类<b>不</b>对应任何数据库表（不建 {@code quality_failure} 表），
 *       没有主键与 {@code reportId}，仅随 {@code QualityReport} 一起序列化进 JSON 列。
 *       因此不加 {@code @TableName}/{@code @TableId}，转而补充序列化契约注解与字段说明；</li>
 *   <li><b>修掉 recordIndex 丢失</b>（10.4.3）：改造前失败明细按 {@code MD5(ruleName|reason)}
 *       跨记录去重，1 万条"内容为空"只留 1 条，其 {@code recordIndex} 仅代表首次出现位置——
 *       报告无法回答"具体哪几条失败了"。现在改为<b>按（规则 + 原因）聚合</b>：
 *       {@link #recordCount} 记录该组失败总数，{@link #recordIndices} 保留具体记录索引
 *       （上限 {@link #MAX_RECORD_INDICES} 个，防止 JSON 无界增长）；
 *       {@link #recordIndex} 继续保留首次出现位置，兼容旧 JSON 与既有前端展示。</li>
 *   <li><b>体积控制</b>（10.4.5）：单条明细的索引列表上限 {@value #MAX_RECORD_INDICES}，
 *       明细<b>条目</b>上限由 {@code DataQualityChecker} 控制（{@code MAX_FAILURE_ENTRIES}）。</li>
 *   <li><b>R43（批次 11）——聚合键与被截断语义</b>：
 *       ① {@link #reason} 改为<b>模板</b>（不拼具体取值），随记录变化的信息进
 *       {@link #detail}，使聚合条数只与规则配置数量相关、与数据量无关；
 *       ② 截断汇总明细改用 {@link #omittedKindCount} 表达"被丢弃的种类数"，
 *       {@link #recordCount} 在所有明细里统一表示"失败记录数"。</li>
 * </ol>
 * <p>
 * 反序列化兼容：{@code @JsonIgnoreProperties(ignoreUnknown = true)} 允许读取未来版本
 * 新增字段的 JSON；旧版 JSON 只有 {@code recordIndex}（无 {@code recordIndices}/{@code recordCount}），
 * 仍可正常解析。
 * </p>
 *
 * @author agent-qr
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class QualityFailure {

    /** 单条明细最多保留的记录索引个数（超出部分只计入 recordCount） */
    public static final int MAX_RECORD_INDICES = 100;

    /** 触发失败的规则名称（如"完整性"、"格式"） */
    private String ruleName;

    /** 规则类型编码（如 completeness/format），便于前端按类型归类 */
    private String ruleType;

    /**
     * 失败原因（同一规则 + 同一原因的记录聚合为一条明细）。
     * <p>
     * R43①（批次 11）：本字段是<b>模板</b>——只含与规则配置相关的稳定内容，
     * 不含随记录变化的取值（见 {@code RuleResult#reason}）。
     * </p>
     */
    private String reason;

    /**
     * 失败详情样例（R43①）：首条失败记录的具体取值信息
     * （如 {@code 字段 'name' 长度 1}）。不参与聚合，仅为可读性/排查服务。
     */
    private String detail;

    /** 首次出现的失败记录索引（从 0 开始）；兼容旧版 JSON 与既有前端列展示 */
    private Integer recordIndex;

    /**
     * 失败记录索引列表（从 0 开始，最多 {@value #MAX_RECORD_INDICES} 个）。
     * <p>与 {@link #recordCount} 配合即可回答"具体哪几条记录失败"。</p>
     */
    private List<Integer> recordIndices = new ArrayList<>();

    /**
     * 该明细对应的<b>失败记录数</b>（按"规则 + 记录"计）。
     * <p>
     * R43②（批次 11）语义收敛：正常明细 = 该原因下的失败记录数；
     * 截断汇总明细 = 因条目上限而<b>未列出</b>的失败记录数。
     * 两种情形单位一致（都是失败记录数），"被丢弃的失败种类数"改由
     * {@link #omittedKindCount} 单独表达——修复前该字段在截断条里表示"种类数"，
     * 属语义重载。
     * </p>
     */
    private int recordCount;

    /**
     * 因明细条目上限而<b>未列出的失败种类数</b>（R43②）。
     * <p>
     * 仅截断汇总明细（{@code ruleType = "truncated"}）非空；正常明细为 null
     * （{@code @JsonInclude(NON_NULL)} 下不出现在 JSON 中）。
     * </p>
     */
    private Integer omittedKindCount;

    /**
     * 兼容旧构造器（规则名、首次出现的记录索引、失败原因）。
     *
     * @param ruleName    规则名称
     * @param recordIndex 失败记录索引（首次出现位置）
     * @param reason      失败原因
     */
    public QualityFailure(String ruleName, int recordIndex, String reason) {
        this.ruleName = ruleName;
        this.recordIndex = recordIndex;
        this.reason = reason;
        this.recordIndices.add(recordIndex);
        this.recordCount = 1;
    }

    /**
     * 追加一条失败记录（批次 10 · 任务 10.4.3）：计数自增，索引在限额内追加。
     *
     * @param index 失败记录索引（从 0 开始）
     */
    public void addRecordIndex(int index) {
        if (recordIndex == null) {
            recordIndex = index;
        }
        if (recordIndices == null) {
            recordIndices = new ArrayList<>();
        }
        if (recordIndices.size() < MAX_RECORD_INDICES) {
            recordIndices.add(index);
        }
        recordCount++;
    }

    /**
     * 反序列化 null 防御：JSON 中显式 {@code "recordIndices": null} 不应留下空指针。
     *
     * @param recordIndices 索引列表
     */
    public void setRecordIndices(List<Integer> recordIndices) {
        this.recordIndices = recordIndices == null ? new ArrayList<>() : recordIndices;
    }
}
