package org.example.agent_qr.web.websocket;

import org.example.agent_qr.common.dlq.DlqMessageMapper;
import org.example.agent_qr.dataquality.entity.QualityReport;
import org.example.agent_qr.dataquality.mapper.QualityReportMapper;
import org.example.agent_qr.datasource.entity.SyncRecord;
import org.example.agent_qr.datasource.mapper.SyncRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 运维告警巡检与推送测试（批次 10 · 任务 10.2.4，问题 34 场景②）。
 * <p>
 * 覆盖：数据源同步失败 / 质检阻断 / DLQ 积压三类信号的推送，以及
 * "首次只建基线、不轰炸历史数据"与"开关关闭时不巡检"两条防噪行为。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsAlertMonitorTest {

    @Mock
    private DlqMessageMapper dlqMessageMapper;

    @Mock
    private SyncRecordMapper syncRecordMapper;

    @Mock
    private QualityReportMapper qualityReportMapper;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private OpsAlertNotifier notifier;
    private OpsAlertMonitor monitor;

    @BeforeEach
    void setUp() {
        notifier = new OpsAlertNotifier(messagingTemplate);
        monitor = new OpsAlertMonitor(dlqMessageMapper, syncRecordMapper, qualityReportMapper, notifier);
        ReflectionTestUtils.setField(monitor, "enabled", true);
        ReflectionTestUtils.setField(monitor, "dlqBacklogThreshold", 1);
        ReflectionTestUtils.setField(monitor, "maxAlertsPerScan", 10);
        when(qualityReportMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("★ 新建的同步失败记录 → 推送到运维频道（CRITICAL + DATASOURCE_SYNC_FAILED）")
    void scan_shouldAlertNewSyncFailure() {
        // 第一轮：只有历史数据（id=100），建立基线
        when(syncRecordMapper.selectList(any())).thenReturn(List.of(syncRecord(100L, "旧的失败")));
        when(dlqMessageMapper.selectCount(any())).thenReturn(0L);
        monitor.scan();

        verifyNoInteractions(messagingTemplate);

        // 第二轮：增量查询返回新出现的失败记录（id=101 > 水位线 100）
        when(syncRecordMapper.selectList(any())).thenReturn(List.of(syncRecord(101L, "连接超时")));
        monitor.scan();

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(OpsAlertNotifier.OPS_TOPIC), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("type", "OPS_ALERT")
                .containsEntry("level", OpsAlertNotifier.LEVEL_CRITICAL)
                .containsEntry("alertType", OpsAlertNotifier.ALERT_SYNC_FAILED);
        assertThat(payloadCaptor.getValue().get("message").toString()).contains("连接超时");
    }

    @Test
    @DisplayName("★ 新建的质检阻断报告 → 推送运维告警")
    void scan_shouldAlertBlockedQualityReport() {
        when(syncRecordMapper.selectList(any())).thenReturn(List.of());
        when(dlqMessageMapper.selectCount(any())).thenReturn(0L);
        when(qualityReportMapper.selectList(any())).thenReturn(List.of());
        monitor.scan();

        QualityReport blocked = new QualityReport();
        blocked.setId(22L);
        blocked.setBatchId("batch-9");
        blocked.setDatasourceId(3L);
        blocked.setTotal(100);
        blocked.setFail(80);
        blocked.setRate(0.2);
        blocked.setBlocked(true);
        when(qualityReportMapper.selectList(any())).thenReturn(List.of(blocked));
        monitor.scan();

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(OpsAlertNotifier.OPS_TOPIC), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("alertType", OpsAlertNotifier.ALERT_QUALITY_BLOCKED);
        assertThat(payloadCaptor.getValue().get("message").toString()).contains("batch-9");
    }

    @Test
    @DisplayName("★ DLQ 积压：数量达到阈值且发生变化时告警；数量不变不重复告警")
    void scan_shouldAlertDlqBacklogOnlyOnChange() {
        when(syncRecordMapper.selectList(any())).thenReturn(List.of());
        when(qualityReportMapper.selectList(any())).thenReturn(List.of());
        when(dlqMessageMapper.selectCount(any())).thenReturn(0L);
        monitor.scan();     // 基线：0 条

        when(dlqMessageMapper.selectCount(any())).thenReturn(3L);
        monitor.scan();     // 0 → 3：告警
        monitor.scan();     // 3 → 3：不重复告警

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, times(1)).convertAndSend(eq(OpsAlertNotifier.OPS_TOPIC), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("alertType", OpsAlertNotifier.ALERT_DLQ_BACKLOG);
        assertThat(payloadCaptor.getValue().get("message").toString()).contains("3");
    }

    @Test
    @DisplayName("ops.enabled=false → 完全不巡检、不推送")
    void scan_shouldDoNothing_whenDisabled() {
        ReflectionTestUtils.setField(monitor, "enabled", false);

        monitor.scan();

        verifyNoInteractions(dlqMessageMapper, syncRecordMapper, qualityReportMapper, messagingTemplate);
    }

    @Test
    @DisplayName("巡检异常（数据库不可用）不向外扩散，只留日志")
    void scan_shouldSwallowExceptions() {
        when(syncRecordMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        monitor.scan();     // 不抛异常即通过
    }

    @Test
    @DisplayName("运维频道目的地固定为 /topic/ops.alerts（订阅侧由 STOMP 拦截器限管理员）")
    void notifier_shouldSendToOpsTopic() {
        notifier.notifyAlert(OpsAlertNotifier.LEVEL_WARNING, "CUSTOM", "自定义告警", Map.of("k", "v"));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/ops.alerts"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("message", "自定义告警");
    }

    private static SyncRecord syncRecord(Long id, String errorMsg) {
        SyncRecord record = new SyncRecord();
        record.setId(id);
        record.setDatasourceId(3L);
        record.setStatus(SyncRecord.STATUS_FAILED);
        record.setErrorMsg(errorMsg);
        return record;
    }
}
