package org.example.agent_qr.datasource.service;

import org.example.agent_qr.common.BusinessException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link DataSourceService} 单飞锁测试（批次 05 · 任务 5.3.3）。
 * <p>
 * 拦截的核心缺陷：定时调度开启后，若没有并发控制，一旦上一轮同步耗时超过 Cron 周期，
 * 就会不断叠加新任务（问题 22 的"单次同步并发控制：无锁、无单飞去重"）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataSourceServiceSingleFlightTest {

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
        java.lang.reflect.Field field = DataSourceService.class.getDeclaredField("connectorMap");
        field.setAccessible(true);
        field.set(dataSourceService, connectorMap);

        DataSourceConfig config = new DataSourceConfig();
        config.setId(9L);
        config.setSourceName("zz_b05_single_flight");
        config.setSourceType("JDBC");
        config.setSyncStrategy(DataSourceConfig.SYNC_FULL);
        config.setConnectionConfig("{}");
        when(dataSourceMapper.selectById(9L)).thenReturn(config);
    }

    @Test
    @DisplayName("★ 同一数据源的并发触发被拒绝（单飞锁生效）")
    void triggerSync_shouldRejectConcurrentTrigger() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jdbcConnector.fullSync(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new SyncResult(1, List.of(Map.of("id", 1)), null);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SyncResult> running = executor.submit(() -> dataSourceService.triggerSync(9L));
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("第一轮同步应已进入执行").isTrue();
            assertThat(dataSourceService.isSyncInFlight(9L)).isTrue();

            assertThatThrownBy(() -> dataSourceService.triggerSync(9L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("正在同步中");

            release.countDown();
            assertThat(running.get(10, TimeUnit.SECONDS).getTotalRows()).isEqualTo(1);
            assertThat(dataSourceService.isSyncInFlight(9L))
                    .as("异常/正常路径都必须释放锁")
                    .isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("★ 同步抛异常后锁被释放（finally 保证），下一次触发可以正常执行")
    void triggerSync_shouldReleaseLock_whenSyncFails() {
        when(jdbcConnector.fullSync(any()))
                .thenThrow(new RuntimeException("JDBC 连接中断"))
                .thenReturn(new SyncResult(2, List.of(Map.of("id", 1), Map.of("id", 2)), null));

        assertThatThrownBy(() -> dataSourceService.triggerSync(9L))
                .isInstanceOf(RuntimeException.class);
        assertThat(dataSourceService.isSyncInFlight(9L)).isFalse();

        SyncResult second = dataSourceService.triggerSync(9L);
        assertThat(second.getTotalRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("顺序触发不受影响（锁在同步结束后立即释放）")
    void triggerSync_shouldAllowSequentialTriggers() {
        when(jdbcConnector.fullSync(any()))
                .thenReturn(new SyncResult(1, List.of(Map.of("id", 1)), null));

        assertThat(dataSourceService.triggerSync(9L).getTotalRows()).isEqualTo(1);
        assertThat(dataSourceService.triggerSync(9L).getTotalRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败路径仍写 FAILED 记录（回归：单飞锁包装未破坏 5.1.2 的状态落库）")
    void triggerSync_shouldStillRecordFailed_whenConnectorReportsFailure() {
        when(jdbcConnector.fullSync(any())).thenReturn(SyncResult.failure("boom", List.of(), null));

        dataSourceService.triggerSync(9L);

        org.mockito.ArgumentCaptor<SyncRecord> captor =
                org.mockito.ArgumentCaptor.forClass(SyncRecord.class);
        org.mockito.Mockito.verify(syncRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
    }
}
