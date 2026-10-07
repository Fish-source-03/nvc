package org.example.agent_qr.rag.entity;

import lombok.Data;

/**
 * 检索结果模型，用于封装从向量数据库/关键词索引/结构化聚合路径检索到的文档信息。
 * <p>
 * <b>批次 11 · R37（R18 残留）—— 跨路字段口径的显式约定</b>
 * </p>
 * <p>
 * 三条来源路径对同一组字段的填写规则如下（<b>显式区分</b>，不再各写各的）：
 * </p>
 * <table border="1">
 *   <caption>字段口径</caption>
 *   <tr><th>字段</th><th>语义路（Chroma）</th><th>关键词路（BM25）</th><th>聚合路（结构化查询）</th></tr>
 *   <tr>
 *     <td>{@link #documentId}</td>
 *     <td>chunkId 字符串（元数据 {@code chunk_id}）；缺失时 {@code "vector:" + embeddingId}</td>
 *     <td>chunkId 字符串</td>
 *     <td>chunkId 字符串</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #documentTitle}</td>
 *     <td>元数据 {@code document_title}；缺失时占位标题</td>
 *     <td>占位标题（索引只存切片标识，无文档标题来源）</td>
 *     <td>占位标题（查询无标题列）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #similarity}</td>
 *     <td>cosine 相似度（0~1）</td>
 *     <td>Lucene BM25 原始分（无界，跨查询不可比）</td>
 *     <td>固定 1.0（该路径无相关性排序）</td>
 *   </tr>
 * </table>
 * <p>
 * ⚠️ 两点必须知道的口径事实：
 * </p>
 * <ol>
 *   <li><b>{@code documentId} 是 RRF 融合的唯一去重键</b>，要求两路同命名空间
 *       （chunkId 字符串）。{@link #VECTOR_ID_NAMESPACE_PREFIX} 前缀是<b>例外标记</b>——
 *       历史向量缺 {@code chunk_id} 元数据时无法得知其切片身份，
 *       于是用显式命名空间避免把向量 UUID 误当成 chunkId（批次 09 · 任务 9.1 的既定回退）；</li>
 *   <li><b>{@code similarity} 在 {@code HybridRetriever} 融合后会被覆写为 RRF 分</b>，
 *       因此"融合结果里的 similarity"既不是 cosine 也不是 BM25 分，
 *       只是融合内部排序分（越小量级，勿当相似度阈值使用）。</li>
 * </ol>
 *
 * @author agent-qr
 */
@Data
public class RetrievedDocument {

    /** 无文档标题来源时的占位标题前缀：{@code chunk-<chunkId>}（R37 统一的占位口径） */
    public static final String TITLE_PLACEHOLDER_PREFIX = "chunk-";

    /** 连切片标识都没有时的兜底标题（与 {@code ChromaRetriever} 的既有回退一致） */
    public static final String UNTITLED_DOCUMENT_TITLE = "未命名文档";

    /**
     * 缺少 {@code chunk_id} 元数据的历史向量：{@code documentId} 回退命名空间前缀。
     * <p>显式区分"向量 UUID 回退值"与"chunkId 字符串"，避免二者被当作同一命名空间。</p>
     */
    public static final String VECTOR_ID_NAMESPACE_PREFIX = "vector:";

    /**
     * 占位标题的唯一构造口径（R37）：
     * 有 chunkId → {@code chunk-<chunkId>}；无 → {@link #UNTITLED_DOCUMENT_TITLE}。
     *
     * @param chunkId 切片 ID（可为 null）
     * @return 占位标题
     */
    public static String placeholderTitle(Long chunkId) {
        return chunkId == null ? UNTITLED_DOCUMENT_TITLE : TITLE_PLACEHOLDER_PREFIX + chunkId;
    }

    /**
     * 跨路去重键（{@code HybridRetriever} RRF 融合的唯一键）。
     * <p>
     * 正常情形为 <b>chunkId 字符串</b>（三条路径统一口径）；
     * 仅当历史向量缺 {@code chunk_id} 元数据时回退为
     * {@link #VECTOR_ID_NAMESPACE_PREFIX} + ChromaDB embeddingId。
     * </p>
     */
    private String documentId;

    /**
     * 切片 ID（对应 kb_chunk 表主键，用于域过滤和数据源状态校验）。
     */
    private Long chunkId;

    /**
     * 展示用文档标题。
     * <p>
     * 口径（R37）：语义路取元数据 {@code document_title}；
     * 其余路径无标题来源时使用占位标题 {@link #placeholderTitle(Long)}
     * （{@code chunk-<chunkId>}，无 chunkId 时为 {@link #UNTITLED_DOCUMENT_TITLE}）。
     * </p>
     */
    private String documentTitle;

    /**
     * 文档内容片段。
     */
    private String content;

    /**
     * 路内相关性分数，<b>跨路不可比</b>（R37 显式区分）。
     * <p>
     * 语义路 = cosine 相似度（0~1）；关键词路 = Lucene BM25 原始分（无界）；
     * 聚合路 = 固定 1.0。经 {@code HybridRetriever} 融合后该值被覆写为 RRF 分，
     * 不再是任何一种相似度——需要相似度阈值时请使用单路检索结果。
     * </p>
     */
    private Double similarity;
}
