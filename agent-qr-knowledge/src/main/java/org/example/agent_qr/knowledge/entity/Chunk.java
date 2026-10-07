package org.example.agent_qr.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.example.agent_qr.common.rag.IndexableText;

import java.time.LocalDateTime;

/**
 * 文档切片实体类，对应数据库表 kb_chunk。
 * <p>
 * 存储文档经文本切割后的片段及其向量化结果引用。
 * 每个切片是知识库检索的最小单元。
 * </p>
 *
 * @author agent-qr
 */
@Data
@TableName("kb_chunk")
public class Chunk implements IndexableText {

    // ==================== 切片状态取值（批次 07 · 任务 7.0.2）====================

    /**
     * 待处理：尚未写入 BM25 索引（保留值，当前写入路径不会产生）。
     */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * 已入库：切片已写入 MySQL 且已进入 BM25 索引，关键词可搜，向量未写入 ChromaDB。
     */
    public static final String STATUS_INDEXED = "INDEXED";

    /**
     * 就绪：向量已写入 ChromaDB，关键词与语义检索均可命中。
     */
    public static final String STATUS_READY = "READY";

    /**
     * 主键 ID，自增。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属文档 ID（文档上传管线使用；数据同步管线为 NULL）。
     */
    private Long documentId;

    /**
     * 所属数据源 ID（数据同步管线使用；文档上传管线为 NULL）。
     * P2 新增：支持 JDBC/REST/S3 等数据源同步产生的切片。
     */
    private Long datasourceId;

    /**
     * 切片在文档中的序号，从 0 开始。
     */
    private Integer chunkIndex;

    /**
     * 切片文本内容。
     */
    private String content;

    /**
     * 切片字符数。
     */
    private Integer charCount;

    /**
     * ChromaDB 中的向量引用 ID。
     * P1 阶段暂不写入，设为 "pending"。
     */
    private String chromaId;

    /**
     * 创建时间，插入时自动填充。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    // ==================== P2 新增字段 ====================

    /**
     * 切片状态，对应 {@code kb_chunk.status} 列。
     * <p>
     * 批次 07 · 任务 7.0.2：原实体的<b>字段缺失</b>——该列一直存在（靠 DB 默认值 {@code 'READY'}），
     * 但 Java 侧读不到也写不到，导致"切片已入库但向量未写"期间仍被当作就绪（问题 28）。
     * 补上映射后取值明确为：
     * <ul>
     *   <li>{@link #STATUS_PENDING} —— 待处理；</li>
     *   <li>{@link #STATUS_INDEXED} —— 已入库（BM25 可搜，向量未写）；</li>
     *   <li>{@link #STATUS_READY} —— 向量已写入，完全就绪。</li>
     * </ul>
     * DB 默认值已同步由 {@code 'READY'} 改为 {@code 'INDEXED'}（任务 7.0.3）。
     * </p>
     */
    private String status;

    /**
     * 原始记录的 MD5 指纹，用于跨批次去重。
     * <p>
     * 在 ETL 管线创建 Chunk 时计算并写入（FingerprintUtils.computeRecordFingerprint），
     * 供 DeduplicationRule 在后续同步时进行跨批次去重比对。
     * </p>
     */
    private String recordHash;

    // ==================== 批次 06 新增字段（问题 10 · 任务 6.1.4）====================

    /**
     * 切片内容类型，对应 {@code kb_chunk.content_type} 列（DB 默认 {@code 'TEXT'}）。
     * <p>
     * 取值：{@code TEXT}（普通文本）/ {@code TABLE}（完整表格）/
     * {@code TABLE_FRAGMENT}（超长表格的保留表头片段）/ {@code MIXED}（文本 + 表格混合）。
     * 供检索侧还原表格上下文使用。
     * </p>
     */
    private String contentType;

    /**
     * 表格标题 / 表格前文本，对应 {@code kb_chunk.table_caption} 列。
     * <p>
     * 用于检索时还原"这是什么表、列含义是什么"的上下文（问题 10 第三层）。
     * </p>
     */
    private String tableCaption;

    /**
     * 软删除标记：0=未删除 / 1=已删除。
     * MyBatis-Plus @TableLogic 自动在所有查询中追加 WHERE deleted = 0。
     */
    @TableLogic
    private Integer deleted;
}
