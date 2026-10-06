package org.example.agent_qr.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 切片批量创建完成事件 —— 向量化链路的统一触发点（批次 07 · 任务 7.0.6）。
 * <p>
 * <b>为什么放在 common 包</b>：文档上传链路（knowledge 模块）与数据同步链路
 * （knowledge 模块的 ETL 监听器）都要发布它，统一的消费方
 * {@code ChunkEmbeddingBatchListener} 也在 knowledge 模块——
 * 事件契约属跨链路共享，放在 common 与既有事件（{@code ChunksCreatedEvent}、
 * {@code DataQualityPassedEvent}）保持一致。
 * </p>
 * <p>
 * <b>粒度与载荷</b>（已确认的决策）：
 * <ul>
 *   <li>粒度：<b>每文档</b>（文档上传链路）/ <b>每数据源同步批次</b>（数据同步链路）各发一次；</li>
 *   <li>载荷：<b>只带标识，不带切片列表</b>。切片内容可能几十万条，
 *       塞进事件会造成大 payload；消费方从 MySQL <b>分批读回</b>。</li>
 * </ul>
 * </p>
 * <p>
 * <b>改造动机</b>（问题 28）：原实现由 {@code ChunkEmbeddingListener} /
 * {@code DataSyncEtlListener} 在写完 MySQL 后<b>直接</b>逐个 {@code submit} 向量化任务，
 * 并在提交处就把文档置为 READY。改为事件驱动后，发布方与消费方解耦，
 * 状态流转由消费方在"向量真正写入"之后推进。
 * </p>
 *
 * @author agent-qr
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunksBatchCreatedEvent {

    /**
     * 文档 ID（文档上传链路）。数据同步链路为 {@code null}。
     */
    private Long documentId;

    /**
     * 数据源 ID（数据同步链路）。文档上传链路为 {@code null}。
     */
    private Long datasourceId;

    /**
     * 同步批次 ID（数据同步链路，可空）。仅用于日志与追踪，
     * 不参与切片筛选——{@code kb_chunk} 无批次列，待处理切片按
     * "状态非 READY" 判定（见 {@code ChunkMapper#selectPendingByDatasourceIdAfterId}）。
     */
    private String syncBatchId;

    /**
     * 构造文档上传链路事件。
     *
     * @param documentId 文档 ID
     * @return 事件
     */
    public static ChunksBatchCreatedEvent forDocument(Long documentId) {
        return new ChunksBatchCreatedEvent(documentId, null, null);
    }

    /**
     * 构造数据同步链路事件。
     *
     * @param datasourceId 数据源 ID
     * @param syncBatchId  同步批次 ID
     * @return 事件
     */
    public static ChunksBatchCreatedEvent forDatasource(Long datasourceId, String syncBatchId) {
        return new ChunksBatchCreatedEvent(null, datasourceId, syncBatchId);
    }

    /**
     * 事件是否携带足以定位待处理切片的标识。
     *
     * @return true 表示 documentId 或 datasourceId 至少有一个非空
     */
    public boolean hasTarget() {
        return documentId != null || datasourceId != null;
    }

    /**
     * 事件来源描述（日志用）。
     *
     * @return 形如 {@code documentId=9} 或 {@code datasourceId=2, batchId=b-1}
     */
    public String describeTarget() {
        if (documentId != null) {
            return "documentId=" + documentId;
        }
        return "datasourceId=" + datasourceId + ", batchId=" + syncBatchId;
    }
}
