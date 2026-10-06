package org.example.agent_qr.datasource.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 数据同步结果。
 * <p>
 * 除同步数据本身（{@link #rawData}）与游标（{@link #nextCursor}）外，
 * 还携带同步的<b>成败语义</b>：
 * </p>
 * <ul>
 *   <li>{@link #success} —— 连接器是否完整完成本次同步。为 {@code false} 时
 *       {@link #rawData} 可能是异常前读到的<b>部分结果</b>，调用方必须写
 *       {@code sync_record.status = FAILED} 而不是 SUCCESS（批次 05 · 任务 5.1.1）。</li>
 *   <li>{@link #truncated} —— 同步成功但因命中上限（如 REST 的 maxPages）而被截断，
 *       调用方据此写 {@code PARTIAL} 并告警（批次 05 · 任务 5.1.7）。</li>
 * </ul>
 * <p>
 * 之所以用"失败标志"而非直接抛 {@code BusinessException}（设计 §8.7.2 的写法），
 * 是因为同步失败需要区分"部分成功"与"完全失败"——异常传播会丢失部分结果。
 * </p>
 *
 * @author agent-qr
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncResult {

    /** 同步的总行数 */
    private int totalRows;

    /** 同步获取的原始数据 */
    private List<Map<String, Object>> rawData;

    /** 下次增量同步的游标 */
    private String nextCursor;

    /** 本次同步是否成功完成（false 表示发生异常，rawData 可能为部分结果） */
    private boolean success = true;

    /** 失败原因（{@link #success} 为 false 时非空） */
    private String errorMessage;

    /** 是否因命中分页/读取上限而被截断（成功但数据未取全） */
    private boolean truncated;

    /**
     * 兼容构造：成功、未截断的三参结果。
     * <p>
     * 保留此构造器是为了不破坏既有调用方（下游依赖 {@code totalRows/rawData/nextCursor} 三元语义）。
     * </p>
     *
     * @param totalRows  总行数
     * @param rawData    原始数据
     * @param nextCursor 下次增量游标
     */
    public SyncResult(int totalRows, List<Map<String, Object>> rawData, String nextCursor) {
        this(totalRows, rawData, nextCursor, true, null, false);
    }

    /**
     * 创建空同步结果（成功、无数据）。
     */
    public static SyncResult empty() {
        return new SyncResult(0, List.of(), null, true, null, false);
    }

    /**
     * 创建失败结果（保留异常前读到的部分数据与游标，供排查与后续重试使用）。
     *
     * @param errorMessage 失败原因
     * @param partialRows  异常前已读取的部分数据（可为 null）
     * @param nextCursor   游标（可为 null）
     * @return 失败态同步结果
     */
    public static SyncResult failure(String errorMessage,
                                     List<Map<String, Object>> partialRows,
                                     String nextCursor) {
        List<Map<String, Object>> rows = partialRows != null ? partialRows : List.of();
        return new SyncResult(rows.size(), rows, nextCursor, false, errorMessage, false);
    }

    /**
     * 标记为"成功但被截断"（命中分页上限等），返回同一实例便于链式调用。
     *
     * @param reason 截断原因
     * @return 当前实例（{@link #truncated} 置为 true）
     */
    public SyncResult markTruncated(String reason) {
        this.truncated = true;
        if (this.errorMessage == null || this.errorMessage.isBlank()) {
            this.errorMessage = reason;
        }
        return this;
    }
}
