package org.example.agent_qr.dataquality.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.event.DataSyncCompletedEvent;
import org.example.agent_qr.dataquality.service.DataQualityService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 数据同步完成事件监听器。
 * <p>
 * 监听 {@link DataSyncCompletedEvent}，异步触发数据质量检查。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncListener {

    private final DataQualityService qualityService;

    /**
     * 监听数据同步完成事件，触发质量检查。
     * <p>
     * R49（批次 11）：显式指定线程池。修复前本方法只有裸 {@code @Async}——
     * {@code AsyncConfigV2}（六池隔离）虽实现了 {@code AsyncConfigurer} 但未覆写
     * {@code getAsyncExecutor()}，裸注解会落到 Spring 默认执行器，
     * <b>不享受六池隔离与 MDC（TraceId）传递</b>，是隔离设计的一个洞。
     * 质检是对同步数据的<b>后处理/统计类</b>任务（产出质检报告），
     * 语义最接近 {@code statExecutor}（统计更新，轻量、可延迟），
     * 故指定该池；其余五池的既有归属不变。
     * </p>
     *
     * @param event 同步完成事件
     */
    @Async("statExecutor")
    @EventListener
    public void onDataSyncCompleted(DataSyncCompletedEvent event) {
        log.info("收到数据同步完成事件: datasourceId={}, sourceName={}, batchId={}, rows={}",
                event.getDatasourceId(), event.getSourceName(), event.getSyncBatchId(),
                event.getRawData() != null ? event.getRawData().size() : 0);

        try {
            // 执行质量检查并持久化
            qualityService.executeAndSave(
                    event.getSyncBatchId(),
                    event.getDatasourceId(),
                    event.getSourceName(),
                    event.getRawData()
            );
        } catch (Exception e) {
            log.error("同步后质量检查失败: datasourceId={}, batchId={}, error={}",
                    event.getDatasourceId(), event.getSyncBatchId(), e.getMessage(), e);
        }
    }
}
