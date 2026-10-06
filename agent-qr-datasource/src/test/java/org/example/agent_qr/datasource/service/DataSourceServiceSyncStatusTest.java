package org.example.agent_qr.datasource.service;

import org.example.agent_qr.common.event.DataSyncCompletedEvent;
import org.example.agent_qr.datasource.connector.DataSourceConnector;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.entity.SyncRecord;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.datasource.mapper.SyncRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataSourceService} 同步状态落库测试（批次 05 · 任务 5.1.2）。
 * <p>
 * 拦截的核心缺陷（问题 23）：连接器吞掉异常后返回"空/部分结果"，
 * 调用方据此写 {@code sync_record.status = SUCCESS}，失败被记为成功。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataSourceServiceSyncStatusTest {

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private SyncRecordMapper syncRecordMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private DataSourceConnector jdbcConnector;

    @InjectMocks
    private DataSourceService dataSourceService;

    private final Map<String, DataSourceConnector> connectorMap = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        connectorMap.put("JDBC", jdbcConnector);
        // 字段注入：connectorMap 由 @InjectMocks 按类型注入（Map），此处显式覆盖
        java.lang.reflect.Field field = DataSourceService.class.getDeclaredField("connectorMap");
        field.setAccessible(true);
        field.set(dataSourceService, connectorMap);
    }

    @Test
    @DisplayName("★ 连接器返回失败时写 sync_record=FAILED（而不是 SUCCESS），并把数据源置为 ERROR")
    void triggerSync_shouldRecordFailed_whenConnectorReturnsFailure() {
        DataSourceConfig config = config();
        when(dataSourceMapper.selectById(7L)).thenReturn(config);
        when(jdbcConnector.fullSync(any())).thenReturn(
                SyncResult.failure("JDBC 全量同步失败: Communications link failure",
                        List.of(Map.of("id", 1)), null));

        SyncResult result = dataSourceService.triggerSync(7L);

        assertThat(result.isSuccess()).isFalse();
        assertThat(capturedRecord().getStatus())
                .as("失败被记为 SUCCESS 时本断言失败")
                .isEqualTo(SyncRecord.STATUS_FAILED);
        assertThat(capturedRecord().getErrorMsg()).contains("Communications link failure");
        verify(dataSourceMapper).updateStatus(7L, DataSourceConfig.STATUS_ERROR);
    }

    @Test
    @DisplayName("★ 连接器失败时不发布 DataSyncCompletedEvent（下游无可信数据可用）")
    void triggerSync_shouldNotPublishEvent_whenConnectorFails() {
        DataSourceConfig config = config();
        when(dataSourceMapper.selectById(7L)).thenReturn(config);
        when(jdbcConnector.fullSync(any())).thenReturn(SyncResult.failure("boom", List.of(), null));

        dataSourceService.triggerSync(7L);

        verify(eventPublisher, never()).publishEvent(any(DataSyncCompletedEvent.class));
    }

    @Test
    @DisplayName("★ 结果被截断（命中分页上限）时写 PARTIAL 并保留告警信息，且仍发布事件")
    void triggerSync_shouldRecordPartial_whenResultTruncated() {
        DataSourceConfig config = config();
        when(dataSourceMapper.selectById(7L)).thenReturn(config);
        SyncResult truncated = new SyncResult(3, List.of(Map.of("id", 1)), "cursor-3");
        truncated.markTruncated("REST 全量同步命中 maxPages=3 上限，结果被截断");
        when(jdbcConnector.fullSync(any())).thenReturn(truncated);

        SyncResult result = dataSourceService.triggerSync(7L);

        assertThat(result.isTruncated()).isTrue();
        assertThat(capturedRecord().getStatus()).isEqualTo(SyncRecord.STATUS_PARTIAL);
        assertThat(capturedRecord().getErrorMsg()).contains("maxPages");
        verify(eventPublisher).publishEvent(any(DataSyncCompletedEvent.class));
    }

    @Test
    @DisplayName("对照组：正常成功结果仍写 SUCCESS 并发布事件")
    void triggerSync_shouldRecordSuccess_onNormalResult() {
        DataSourceConfig config = config();
        when(dataSourceMapper.selectById(7L)).thenReturn(config);
        when(jdbcConnector.fullSync(any())).thenReturn(
                new SyncResult(2, List.of(Map.of("id", 1), Map.of("id", 2)), "2"));

        dataSourceService.triggerSync(7L);

        assertThat(capturedRecord().getStatus()).isEqualTo(SyncRecord.STATUS_SUCCESS);
        verify(dataSourceMapper).updateStatus(7L, DataSourceConfig.STATUS_ACTIVE);
        verify(eventPublisher).publishEvent(any(DataSyncCompletedEvent.class));
    }

    // ==================== 辅助 ====================

    private DataSourceConfig config() {
        DataSourceConfig config = new DataSourceConfig();
        config.setId(7L);
        config.setSourceName("zz_b05_test");
        config.setSourceType("JDBC");
        config.setSyncStrategy(DataSourceConfig.SYNC_FULL);
        config.setConnectionConfig("{}");
        return config;
    }

    private SyncRecord capturedRecord() {
        ArgumentCaptor<SyncRecord> captor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(syncRecordMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }
}
