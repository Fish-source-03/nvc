package org.example.agent_qr.dataquality.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据质量检查报告实体，对应 quality_report 表。
 * <p>
 * 方案 B：failures 明细通过 MySQL JSON 列存储，
 * 使用 MyBatis-Plus JacksonTypeHandler 自动序列化/反序列化。
 * </p>
 *
 * @author agent-qr
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "quality_report", autoResultMap = true)
public class QualityReport {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 同步批次 ID */
    private String batchId;

    /** 数据源配置 ID */
    private Long datasourceId;

    /** 数据源名称 */
    private String sourceName;

    /** 总记录数 */
    @JsonProperty("totalCount")
    private int total;

    /** 通过数 */
    @JsonProperty("passCount")
    private int pass;

    /** 失败数 */
    @JsonProperty("failCount")
    private int fail;

    /** 通过率（pass / total） */
    @JsonProperty("passRate")
    private double rate;

    /** 是否被阻断（通过率低于阈值） */
    private boolean blocked;

    /** 失败明细列表（MySQL JSON 列，JacksonTypeHandler 自动序列化） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<QualityFailure> failures = new ArrayList<>();

    /**
     * 失败记录索引集合（不持久化，仅供内存过滤使用，避免去重后漏过滤）。
     * <p>
     * ★ 批次 10 · 任务 10.4.4（问题 26）：加 {@link JsonIgnore}——它是<b>内部过滤用的中间状态</b>，
     * 前端不需要（失败明细已通过 {@code failures[].recordIndices} 暴露具体索引），
     * 随详情接口返回只会暴露内部索引集合并增大响应体积。
     * 注意：{@code QualityReport.failedIndices} 仍被 {@code DataQualityService.filterPassedData}
     * 过滤环节使用，<b>不得移除</b>。
     * </p>
     */
    @JsonIgnore
    @TableField(exist = false)
    private Set<Integer> failedIndices = new HashSet<>();

    /** 检查时间 */
    private LocalDateTime checkTime;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /**
     * 兼容旧构造器（不含持久化字段，Checker 无需修改）。
     */
    public QualityReport(String batchId, int total, int pass, int fail,
                         double rate, boolean blocked, List<QualityFailure> failures) {
        this.batchId = batchId;
        this.total = total;
        this.pass = pass;
        this.fail = fail;
        this.rate = rate;
        this.blocked = blocked;
        this.failures = failures != null ? failures : new ArrayList<>();
        this.checkTime = LocalDateTime.now();
    }
}
