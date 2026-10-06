package org.example.agent_qr.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源配置实体，对应数据库表 data_source_config。
 * <p>
 * 持久化外部数据源的连接信息、同步策略和游标状态。
 * </p>
 *
 * @author agent-qr
 */
@Data
@TableName("data_source_config")
public class DataSourceConfig {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源名称 */
    private String sourceName;

    /** 数据源类型：JDBC / REST / S3 */
    private String sourceType;

    /** 所属业务域 */
    private String domain;

    /** 同步策略：FULL / INCREMENTAL */
    private String syncStrategy;

    /** 增量同步的游标字段名 */
    private String cursorField;

    /** 上次同步的游标值 */
    private String lastCursor;

    /** 连接配置（JSON 格式） */
    private String connectionConfig;

    /** 字段映射配置（JSON 格式） */
    private String fieldMapping;

    /** 该数据源完整性检查的字段列表（逗号分隔），为空时回退到全局默认值 content,text,_content */
    private String contentFields;

    /** 状态：ACTIVE / INACTIVE / ERROR */
    private String status;

    /** 累计同步总数 */
    private Integer totalSynced;

    /** 累计质量通过数（NULL 表示从未执行过质量检查） */
    private Integer totalPassed;

    /** 上次同步时间 */
    private LocalDateTime lastSyncAt;

    /**
     * 定时同步 Cron 表达式（批次 05 · 任务 5.3.1，问题 22）。
     * <p>为空表示不定时同步。应用启动时按本字段批量注册 Cron 任务。</p>
     * <p>注意：{@link JsonAlias} 用于兼容前端既有的 {@code cronExpression} 字段名
     * （前端 {@code DataSourceFormDialog} 已提供该输入项，但后端此前无对应字段）。</p>
     */
    @JsonAlias("cronExpression")
    private String syncCron;

    /**
     * 是否启用定时同步（批次 05 · 任务 5.3.1）。
     * <p>{@code null} 视为启用（历史数据无此列值）；仅显式 {@code false} 才跳过注册。</p>
     */
    private Boolean syncEnabled;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 状态常量 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_ERROR = "ERROR";

    /** 同步策略常量 */
    public static final String SYNC_FULL = "FULL";
    public static final String SYNC_INCREMENTAL = "INCREMENTAL";
}
