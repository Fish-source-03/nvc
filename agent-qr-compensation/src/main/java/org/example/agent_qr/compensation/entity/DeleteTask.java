package org.example.agent_qr.compensation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 删除任务实体，对应数据库表 delete_task。
 * <p>
 * 记录 ChromaDB 物理删除任务的状态和重试信息。
 * </p>
 *
 * @author agent-qr
 */
@Data
@TableName("delete_task")
public class DeleteTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的文档 ID */
    private Long documentId;

    /** ChromaDB 向量 ID 列表（逗号分隔串，由 String.join(",", ...) 写入，非 JSON 数组） */
    private String chromaIds;

    /** 状态：PENDING / DONE / FAILED */
    private String status;

    /** 重试次数 */
    private Integer retryCount;

    /** 创建时间 */
    private LocalDateTime createTime;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
}
