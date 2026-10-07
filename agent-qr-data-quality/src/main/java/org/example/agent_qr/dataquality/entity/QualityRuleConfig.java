package org.example.agent_qr.dataquality.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 质检规则配置实体，对应数据库表 {@code quality_rule}（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 设计要点：表里<b>只存配置</b>（规则类型、目标字段、校验参数、启用状态、优先级），
 * 具体判定逻辑仍由 {@code rule} 包下各 {@code QualityRule} 实现类提供，
 * 由 {@code DataQualityChecker} 按 {@code ruleType} 分派——不引入任何脚本引擎。
 * </p>
 * <p>
 * 类名带 {@code Config} 后缀是为了与 {@code rule.QualityRule}（规则实现接口）区分：
 * 本类是"规则配置"，接口是"规则类型"。
 * </p>
 *
 * @author agent-qr
 */
@Data
@TableName(value = "quality_rule", autoResultMap = true)
public class QualityRuleConfig {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则名称（展示用，前端表格首列） */
    private String ruleName;

    /** 规则类型编码，取值必须是 {@code rule.QualityRule#getType()} 之一 */
    private String ruleType;

    /** 目标字段列表（逗号分隔）；为空表示由规则实现自行决定检查范围 */
    private String targetFields;

    /**
     * 校验参数（JSON 对象），例如
     * {@code {"pattern": "^\\d+$"}} / {@code {"minLength": 1, "maxLength": 64}} /
     * {@code {"charset": "UTF-8"}}。
     * <p>由 MyBatis-Plus {@link JacksonTypeHandler} 自动与 JSON 列互转。</p>
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> params;

    /** 是否启用：仅启用的规则参与质检 */
    private Boolean enabled;

    /** 优先级（升序执行，数值小者先执行；null 视为默认优先级） */
    private Integer priority;

    /** 创建时间（由数据库默认值维护） */
    private LocalDateTime createTime;

    /** 更新时间（由数据库 ON UPDATE 维护） */
    private LocalDateTime updateTime;
}
