package org.example.agent_qr.datasource.service;

import org.example.agent_qr.datasource.connector.DataSourceConnector;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.datasource.mapper.SyncRecordMapper;
import org.example.agent_qr.datasource.scheduler.SyncScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataSourceService} 定时任务恢复通道测试（批次 05 · 任务 5.3 返工修订）。
 * <p>
 * 拦截的核心缺陷（独立验证发现）：熔断/未注册后<b>没有任何恢复通道</b>——
 * 前端表单不发送 status，手动同步成功虽然会把数据源置回 ACTIVE，
 * 却不会在当前 JVM 内重新注册定时器，于是数据源"看起来好了，但再也不会自动同步"。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataSourceServiceScheduleRecoveryTest {

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private SyncRecordMapper syncRecordMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SyncScheduler syncScheduler;

    @Mock
    private DataSourceConnector jdbcConnector;

    @InjectMocks
    private DataSourceService dataSourceService;

    private final Map<String, DataSourceConnector> connectorMap = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        connectorMap.put("JDBC", jdbcConnector);
        java.lang.reflect.Field field = DataSourceService.class.getDeclaredField("connectorMap");
        field.setAccessible(true);
        field.set(dataSourceService, connectorMap);
        when(jdbcConnector.fullSync(any()))
                .thenReturn(new SyncResult(1, List.of(Map.of("id", 1)), null));
    }

    @Test
    @DisplayName("★ 手动同步成功后，被熔断取消的定时任务被重新注册（恢复通道）")
    void triggerSync_shouldReRegisterSchedule_whenSuccessAndNotScheduled() {
        when(dataSourceMapper.selectById(5L)).thenReturn(config(5L, "0 0 3 * * *"));
        when(syncScheduler.isScheduled(5L)).thenReturn(false);

        dataSourceService.triggerSync(5L);

        verify(syncScheduler).register(any(DataSourceConfig.class));
    }

    @Test
    @DisplayName("定时触发路径下任务本来就在注册表中，不重复注册（只清零失败计数）")
    void triggerSync_shouldOnlyResetFailures_whenAlreadyScheduled() {
        when(dataSourceMapper.selectById(5L)).thenReturn(config(5L, "0 0 3 * * *"));
        when(syncScheduler.isScheduled(5L)).thenReturn(true);

        dataSourceService.triggerSync(5L);

        verify(syncScheduler).resetFailures(5L);
        verify(syncScheduler, never()).register(any(DataSourceConfig.class));
    }

    @Test
    @DisplayName("未配置 cron 的数据源同步成功后不会凭空注册定时任务")
    void triggerSync_shouldNotRegister_whenCronMissing() {
        when(dataSourceMapper.selectById(5L)).thenReturn(config(5L, null));
        when(syncScheduler.isScheduled(5L)).thenReturn(false);

        dataSourceService.triggerSync(5L);

        verify(syncScheduler, never()).register(any(DataSourceConfig.class));
    }

    @Test
    @DisplayName("同步失败不触发恢复注册（失败不能掩盖故障）")
    void triggerSync_shouldNotRegister_whenSyncFails() {
        when(dataSourceMapper.selectById(5L)).thenReturn(config(5L, "0 0 3 * * *"));
        when(jdbcConnector.fullSync(any())).thenReturn(SyncResult.failure("boom", List.of(), null));

        dataSourceService.triggerSync(5L);

        verify(syncScheduler, never()).register(any(DataSourceConfig.class));
        verify(syncScheduler, never()).resetFailures(anyLong());
    }

    @Test
    @DisplayName("恢复注册自身抛异常时不影响同步结果的返回")
    void triggerSync_shouldNotFail_whenRecoveryThrows() {
        when(dataSourceMapper.selectById(5L)).thenReturn(config(5L, "0 0 3 * * *"));
        when(syncScheduler.isScheduled(5L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("scheduler down"))
                .when(syncScheduler).resetFailures(5L);

        SyncResult result = dataSourceService.triggerSync(5L);

        org.assertj.core.api.Assertions.assertThat(result.isSuccess()).isTrue();
    }

    private DataSourceConfig config(Long id, String cron) {
        DataSourceConfig config = new DataSourceConfig();
        config.setId(id);
        config.setSourceName("zz_b05_recovery");
        config.setSourceType("JDBC");
        config.setSyncStrategy(DataSourceConfig.SYNC_FULL);
        config.setConnectionConfig("{}");
        config.setStatus(DataSourceConfig.STATUS_ACTIVE);
        config.setSyncCron(cron);
        return config;
    }
}
