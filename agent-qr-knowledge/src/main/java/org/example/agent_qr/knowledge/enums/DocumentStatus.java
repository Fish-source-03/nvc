package org.example.agent_qr.knowledge.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 文档状态枚举，描述文档在知识库生命周期中的各个阶段。
 * <p>
 * 状态流转（批次 07 · 任务 7.0a 双状态机，共 8 个状态）：
 * </p>
 * <pre>
 * UPLOADED → PARSING → CHUNKING → INDEXED → EMBEDDING → READY
 *                                  ↑          ↑           ↑
 *                             已入库       向量化中     向量已写入
 *                             BM25 可检索
 * </pre>
 * <p>
 * 异常时可能进入 {@link #FAILED}，删除时进入 {@link #DELETING}。
 * </p>
 * <p>
 * <b>为什么要拆出 INDEXED</b>（原缺陷：切片入库后立即置 READY，见问题 28）：
 * 切片写入 MySQL 后 BM25 关键词检索<b>已可命中</b>，但向量尚未写入 ChromaDB，
 * 语义检索仍搜不到。原实现在"提交向量化任务"（而非"向量化完成"）时就置 READY，
 * 用户会看到"就绪"却搜不到内容。拆成两步后：
 * <ul>
 *   <li>{@link #INDEXED} —— 切片已入库，<b>关键词可搜</b>，向量未就绪；</li>
 *   <li>{@link #EMBEDDING} —— 正在写入向量；</li>
 *   <li>{@link #READY} —— 向量已落库，<b>关键词与语义均可搜</b>。</li>
 * </ul>
 * 前端据此区分"完全就绪"与"部分就绪"。
 * </p>
 *
 * @author agent-qr
 */
public enum DocumentStatus {

    /** 已上传，等待解析 */
    UPLOADED("已上传"),

    /** 解析中 */
    PARSING("解析中"),

    /** 切片中 */
    CHUNKING("切片中"),

    /**
     * 已入库：切片已写入 MySQL，BM25 关键词检索可命中，但向量未写入 ChromaDB。
     * <p>与 {@link #EMBEDDING} 的区别：INDEXED 表示"向量化尚未开始"，
     * EMBEDDING 表示"向量化进行中"；两者都不可做语义检索。</p>
     */
    INDEXED("部分就绪（关键词可搜）"),

    /** 向量化中：切片已入库，正在提交/写入向量 */
    EMBEDDING("向量化中"),

    /** 就绪，关键词与语义检索均可命中 */
    READY("就绪"),

    /** 处理失败 */
    FAILED("失败"),

    /** 删除中 */
    DELETING("删除中");

    /**
     * MyBatis-Plus 标记：将枚举 name() 存入数据库。
     */
    @EnumValue
    private final String value;

    private final String description;

    DocumentStatus(String description) {
        this.value = this.name();
        this.description = description;
    }

    /**
     * 获取状态的中文描述。
     *
     * @return 中文描述
     */
    public String getDescription() {
        return description;
    }
}
