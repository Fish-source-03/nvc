package org.example.agent_qr.web.websocket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.DlqMessageMapper;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.dataquality.entity.QualityReport;
import org.example.agent_qr.dataquality.mapper.QualityReportMapper;
import org.example.agent_qr.datasource.entity.SyncRecord;
import org.example.agent_qr.datasource.mapper.SyncRecordMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维告警扫描器（批次 10 · 任务 10.2.4，问题 34 场景②）。
 * <p>
 * 巡检三类运维信号并推送到 {@link OpsAlertNotifier#OPS_TOPIC}（仅管理员可订阅）：
 * </p>
 * <ol>
 *   <li><b>数据源同步失败</b> —— {@code sync_record.status = FAILED} 的新记录
 *       （{@code DataSourceService} 在连接器失败/异常时都会落这样一条记录）；</li>
 *   <li><b>质检阻断</b> —— {@code quality_report.blocked = true} 的新报告
 *       （{@code DataQualityService} 阻断时只写日志，没有事件可用，故按记录巡检）；</li>
 *   <li><b>DLQ 积压</b> —— {@code dlq_message} 中 PENDING/DEAD 的总数超过阈值且<b>数量发生变化</b>时告警
 *       （死信入队本身没有事件，且"积压"是存量指标，按量巡检比按入队瞬时告警更贴近语义）。</li>
 * </ol>
 * <p>
 * <b>防重复</b>：三类信号均维护水位线（首次扫描只建基线，不告警历史数据；
 * 之后只对"水位线之后的新记录 / 数量变化"告警），避免每轮扫描重复轰炸运维频道。
 * </p>
 * <p>
 * <b>为什么不用事件驱动</b>：{@code DeadLetterQueue.enqueue} 与 {@code DataQualityService}
 * 位于 other 模块且无对应事件契约，为它们新增事件会扩大改动面；巡检方式还能覆盖
 * "积压"这类没有瞬时事件的状态。批内不改 common / data-quality 的既有发布逻辑。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsAlertMonitor {

    /** 扫描开关（默认开启） */
    @Value("${agent-qr.websocket.ops.enabled:true}")
    private boolean enabled = true;

    /** DLQ 积压告警阈值（PENDING + DEAD 条数，默认 1：出现死信即告警） */
    @Value("${agent-qr.websocket.ops.dlq-backlog-threshold:1}")
    private int dlqBacklogThreshold = 1;

    /** 单轮扫描最多推送的告警条数（防止故障风暴刷屏） */
    @Value("${agent-qr.websocket.ops.max-alerts-per-scan:10}")
    private int maxAlertsPerScan = 10;

    private final DlqMessageMapper dlqMessageMapper;
    private final SyncRecordMapper syncRecordMapper;
    private final QualityReportMapper qualityReportMapper;
    private final OpsAlertNotifier opsAlertNotifier;

    /** 是否已建立水位线基线（首轮扫描只建基线，不告警历史数据） */
    private boolean initialized = false;

    /** 已告警到的同步失败记录 ID 水位线 */
    private long lastSyncRecordId = 0L;

    /** 已告警到的质检阻断报告 ID 水位线 */
    private long lastQualityReportId = 0L;

    /** 上次观察到的 DLQ 积压条数（用于"数量变化"判定） */
    private int lastDlqBacklog = 0;

    /**
     * 巡检入口（周期由 {@code agent-qr.websocket.ops.scan-interval-ms} 控制，默认 60 秒）。
     * <p>任何异常都只记 WARN：巡检失败不得影响应用其他功能。</p>
     */
    @Scheduled(fixedDelayString = "${agent-qr.websocket.ops.scan-interval-ms:60000}",
            initialDelayString = "${agent-qr.websocket.ops.initial-delay-ms:30000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            if (!initialized) {
                establishBaseline();
                initialized = true;
                return;
            }
            scanSyncFailures();
            scanQualityBlockedReports();
            scanDlqBacklog();
        } catch (Exception e) {
            log.warn("运维告警巡检失败（下一轮重试）: error={}", e.getMessage());
        }
    }

    /**
     * 建立水位线基线：把"当前已存在的失败/阻断/积压"排除在外，避免启动即轰炸历史数据。
     */
    private void establishBaseline() {
        lastSyncRecordId = latestSyncRecordId();
        lastQualityReportId = latestQualityReportId();
        lastDlqBacklog = currentDlqBacklog();
        log.info("运维告警巡检基线已建立: lastSyncRecordId={}, lastQualityReportId={}, dlqBacklog={}",
                lastSyncRecordId, lastQualityReportId, lastDlqBacklog);
    }

    /**
     * 巡检新的同步失败记录并告警。
     */
    private void scanSyncFailures() {
        List<SyncRecord> failures = syncRecordMapper.selectList(new LambdaQueryWrapper<SyncRecord>()
                .eq(SyncRecord::getStatus, SyncRecord.STATUS_FAILED)
                .gt(SyncRecord::getId, lastSyncRecordId)
                .orderByAsc(SyncRecord::getId)
                .last("LIMIT " + Math.max(1, maxAlertsPerScan)));
        for (SyncRecord record : failures) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("recordId", record.getId());
            details.put("datasourceId", record.getDatasourceId());
            details.put("errorMsg", record.getErrorMsg());
            opsAlertNotifier.notifyAlert(OpsAlertNotifier.LEVEL_CRITICAL,
                    OpsAlertNotifier.ALERT_SYNC_FAILED,
                    "数据源同步失败: datasourceId=" + record.getDatasourceId()
                            + ", error=" + record.getErrorMsg(),
                    details);
            lastSyncRecordId = record.getId();
        }
    }

    /**
     * 巡检新的质检阻断报告并告警。
     */
    private void scanQualityBlockedReports() {
        List<QualityReport> blockedReports = qualityReportMapper.selectList(
                new LambdaQueryWrapper<QualityReport>()
                        .eq(QualityReport::isBlocked, true)
                        .gt(QualityReport::getId, lastQualityReportId)
                        .orderByAsc(QualityReport::getId)
                        .last("LIMIT " + Math.max(1, maxAlertsPerScan)));
        for (QualityReport report : blockedReports) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reportId", report.getId());
            details.put("batchId", report.getBatchId());
            details.put("datasourceId", report.getDatasourceId());
            details.put("passRate", report.getRate());
            details.put("total", report.getTotal());
            details.put("fail", report.getFail());
            opsAlertNotifier.notifyAlert(OpsAlertNotifier.LEVEL_CRITICAL,
                    OpsAlertNotifier.ALERT_QUALITY_BLOCKED,
                    "质检阻断: batchId=" + report.getBatchId()
                            + ", passRate=" + String.format("%.2f", report.getRate()),
                    details);
            lastQualityReportId = report.getId();
        }
    }

    /**
     * 巡检 DLQ 积压：数量达到阈值且与上次观察值不同（新增/消化）时告警一次。
     */
    private void scanDlqBacklog() {
        int backlog = currentDlqBacklog();
        if (backlog >= dlqBacklogThreshold && backlog != lastDlqBacklog) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("backlog", backlog);
            details.put("threshold", dlqBacklogThreshold);
            details.put("previousBacklog", lastDlqBacklog);
            opsAlertNotifier.notifyAlert(OpsAlertNotifier.LEVEL_WARNING,
                    OpsAlertNotifier.ALERT_DLQ_BACKLOG,
                    "DLQ 积压 " + backlog + " 条（阈值 " + dlqBacklogThreshold + "）",
                    details);
        }
        lastDlqBacklog = backlog;
    }

    /**
     * 当前 DLQ 积压条数（PENDING + DEAD）。
     *
     * @return 积压条数
     */
    private int currentDlqBacklog() {
        Long count = dlqMessageMapper.selectCount(new LambdaQueryWrapper<DlqMessage>()
                .in(DlqMessage::getStatus, DlqMessage.STATUS_PENDING, DlqMessage.STATUS_DEAD));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 当前最大同步记录 ID（基线用）。
     *
     * @return 最大 ID；无记录返回 0
     */
    private long latestSyncRecordId() {
        List<SyncRecord> latest = syncRecordMapper.selectList(new LambdaQueryWrapper<SyncRecord>()
                .orderByDesc(SyncRecord::getId)
                .last("LIMIT 1"));
        return latest.isEmpty() || latest.get(0).getId() == null ? 0L : latest.get(0).getId();
    }

    /**
     * 当前最大质检报告 ID（基线用）。
     *
     * @return 最大 ID；无记录返回 0
     */
    private long latestQualityReportId() {
        List<QualityReport> latest = qualityReportMapper.selectList(new LambdaQueryWrapper<QualityReport>()
                .orderByDesc(QualityReport::getId)
                .last("LIMIT 1"));
        return latest.isEmpty() || latest.get(0).getId() == null ? 0L : latest.get(0).getId();
    }
}
