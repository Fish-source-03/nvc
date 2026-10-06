package org.example.agent_qr.datasource.scheduler;

import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.datasource.service.DataSourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SyncScheduler} 测试（批次 05 · 任务 5.3.2 / 5.3.3 / 5.3.4 / 问题 22）。
 * <p>
 * 拦截的核心缺陷：SyncScheduler 是<b>死代码</b>——无 {@code @Scheduled}、
 * 无 {@code TaskScheduler}、无调用方、实体与建表脚本也没有 cron 字段，
 * "定时同步"这一 P2 核心能力实际不存在。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncSchedulerTest {

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private DataSourceService dataSourceService;

    @Mock
    private TaskScheduler taskScheduler;

    @InjectMocks
    private SyncScheduler syncScheduler;

    @BeforeEach
    void setUp() {
        ScheduledFuture<?> idleFuture = mock(ScheduledFuture.class);
        when(idleFuture.isDone()).thenReturn(false);
        when(idleFuture.isCancelled()).thenReturn(false);
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenAnswer(invocation -> idleFuture);
    }

    // ==================== 5.3.2 注册条件 ====================

    @Test
    @DisplayName("★ syncEnabled=false 时任务不注册")
    void register_shouldNotRegister_whenSyncDisabled() {
        DataSourceConfig config = config(true, "0 0 3 * * *");
        config.setSyncEnabled(false);

        syncScheduler.register(config);

        assertThat(syncScheduler.isScheduled(1L)).isFalse();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("★ Cron 为空时不注册（空串/纯空白同样不注册）")
    void register_shouldNotRegister_whenCronBlank() {
        syncScheduler.register(config(true, null));
        syncScheduler.register(config(true, "   "));

        assertThat(syncScheduler.isScheduled(1L)).isFalse();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("★ status=INACTIVE（操作员显式停用）的数据源不注册定时任务")
    void register_shouldNotRegister_whenStatusInactive() {
        DataSourceConfig inactive = config(true, "0 0 3 * * *");
        inactive.setStatus(DataSourceConfig.STATUS_INACTIVE);

        syncScheduler.register(inactive);

        assertThat(syncScheduler.isScheduled(1L)).isFalse();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    @DisplayName("★ 返工修订：status=ERROR 的数据源要注册（注册与运行期条件必须一致，否则 ERROR 永不自愈）")
    void register_shouldRegister_whenStatusError() {
        DataSourceConfig error = config(true, "0 0 3 * * *");
        error.setStatus(DataSourceConfig.STATUS_ERROR);

        syncScheduler.register(error);

        assertThat(syncScheduler.isScheduled(1L))
                .as("注册期排除 ERROR 会导致重启后永不注册（初版缺陷）")
                .isTrue();
    }

    @Test
    @DisplayName("★ Cron 表达式变更后任务被重新注册（旧任务先取消）")
    void register_shouldReschedule_whenCronChanges() {
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        ScheduledFuture<?> second = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenReturn((ScheduledFuture) first, (ScheduledFuture) second);

        syncScheduler.register(config(true, "0 0 3 * * *"));
        syncScheduler.register(config(true, "0 0 4 * * *"));

        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
        verify(first).cancel(false);
        assertThat(syncScheduler.isScheduled(1L)).isTrue();
    }

    @Test
    @DisplayName("★ 定时任务可实际触发：1 秒 Cron 在 3 秒内真实调起同步")
    void register_shouldActuallyFireTask() {
        ThreadPoolTaskScheduler real = new ThreadPoolTaskScheduler();
        real.setPoolSize(2);
        real.setThreadNamePrefix("zz-b05-scheduler-");
        real.initialize();
        ReflectionTestUtils.setField(syncScheduler, "taskScheduler", real);
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "*/1 * * * * *"));
        when(dataSourceService.triggerSync(anyLong())).thenReturn(SyncResult.empty());

        try {
            syncScheduler.register(config(true, "*/1 * * * * *"));

            verify(dataSourceService, timeout(3500).atLeastOnce()).triggerSync(1L);
        } finally {
            syncScheduler.unregister(1L);
            real.shutdown();
        }
    }

    @Test
    @DisplayName("取消后任务不再触发")
    void unregister_shouldStopTask() throws Exception {
        ThreadPoolTaskScheduler real = new ThreadPoolTaskScheduler();
        real.setPoolSize(2);
        real.initialize();
        ReflectionTestUtils.setField(syncScheduler, "taskScheduler", real);
        CountDownLatch firstFire = new CountDownLatch(1);
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "*/1 * * * * *"));
        when(dataSourceService.triggerSync(anyLong())).thenAnswer(invocation -> {
            firstFire.countDown();
            return SyncResult.empty();
        });

        try {
            syncScheduler.register(config(true, "*/1 * * * * *"));
            assertThat(firstFire.await(3, TimeUnit.SECONDS)).isTrue();

            syncScheduler.unregister(1L);
            assertThat(syncScheduler.isScheduled(1L)).isFalse();

            int callsAfterUnregister = org.mockito.Mockito.mockingDetails(dataSourceService)
                    .getInvocations().size();
            Thread.sleep(2500);
            assertThat(org.mockito.Mockito.mockingDetails(dataSourceService).getInvocations().size())
                    .as("取消后不应再有新的触发")
                    .isEqualTo(callsAfterUnregister);
        } finally {
            real.shutdown();
        }
    }

    @Test
    @DisplayName("★ 启动时按数据库配置批量注册：ERROR 也要注册（返工修订，重启后可自愈）")
    void registerAllScheduledSyncs_shouldRegisterOnlyEligible() {
        DataSourceConfig eligible = config(true, "0 0 3 * * *");
        eligible.setId(1L);
        DataSourceConfig noCron = config(true, null);
        noCron.setId(2L);
        DataSourceConfig inactive = config(true, "0 0 3 * * *");
        inactive.setId(3L);
        inactive.setStatus(DataSourceConfig.STATUS_INACTIVE);
        DataSourceConfig error = config(true, "0 0 5 * * *");
        error.setId(4L);
        error.setStatus(DataSourceConfig.STATUS_ERROR);
        when(dataSourceMapper.selectList(any())).thenReturn(List.of(eligible, noCron, inactive, error));

        syncScheduler.registerAllScheduledSyncs();

        assertThat(syncScheduler.isScheduled(1L)).isTrue();
        assertThat(syncScheduler.isScheduled(2L)).as("无 cron 不注册").isFalse();
        assertThat(syncScheduler.isScheduled(3L)).as("INACTIVE 不注册").isFalse();
        assertThat(syncScheduler.isScheduled(4L))
                .as("ERROR 数据源必须被注册，否则重启后永不重试（初版缺陷）")
                .isTrue();
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
    }

    // ==================== 5.3.3 状态校验与委派 ====================

    // ==================== 返工修订：失败重试风暴熔断 ====================

    @Test
    @DisplayName("★ 连续失败达到阈值即熔断：任务被取消，且不再产生新的 FAILED 记录")
    void scheduleSync_shouldSuspendSchedule_afterConsecutiveFailures() {
        DataSourceConfig config = config(true, "0 0 3 * * *");
        when(dataSourceMapper.selectById(1L)).thenReturn(config);
        when(dataSourceService.triggerSync(1L))
                .thenReturn(SyncResult.failure("JDBC 全量同步失败: Communications link failure",
                        List.of(), null));
        syncScheduler.register(config);

        for (int i = 0; i < 4; i++) {
            syncScheduler.scheduleSync(1L);
        }
        assertThat(syncScheduler.isScheduled(1L)).as("未达阈值前仍在调度").isTrue();
        assertThat(syncScheduler.failureCount(1L)).isEqualTo(4);

        syncScheduler.scheduleSync(1L);

        assertThat(syncScheduler.isScheduled(1L))
                .as("达到阈值必须取消任务，否则 sync_record 会无界增长")
                .isFalse();
        verify(dataSourceService, times(5)).triggerSync(1L);
    }

    @Test
    @DisplayName("★ 熔断后已取消的任务不会再被调用（模拟后续 cron tick 无开销）")
    void scheduleSync_shouldNotCallService_whenSuspended() {
        ThreadPoolTaskScheduler real = new ThreadPoolTaskScheduler();
        real.setPoolSize(1);
        real.initialize();
        ReflectionTestUtils.setField(syncScheduler, "taskScheduler", real);
        ReflectionTestUtils.setField(syncScheduler, "maxConsecutiveFailures", 2);
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "*/1 * * * * *"));
        when(dataSourceService.triggerSync(1L)).thenReturn(SyncResult.failure("boom", List.of(), null));

        try {
            syncScheduler.register(config(true, "*/1 * * * * *"));
            // 2 次失败即熔断
            verify(dataSourceService, timeout(4000).atLeastOnce()).triggerSync(1L);
            Thread.sleep(2500);
            int callsAtSuspend = org.mockito.Mockito.mockingDetails(dataSourceService)
                    .getInvocations().size();
            assertThat(syncScheduler.isScheduled(1L)).isFalse();

            Thread.sleep(2500);
            assertThat(org.mockito.Mockito.mockingDetails(dataSourceService).getInvocations().size())
                    .as("熔断后不得再发起真实同步（避免重试风暴）")
                    .isEqualTo(callsAtSuspend);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            syncScheduler.unregister(1L);
            real.shutdown();
        }
    }

    @Test
    @DisplayName("★ 成功一次即清零连续失败计数（偶发失败不会累积成熔断）")
    void scheduleSync_shouldResetFailureCount_onSuccess() {
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "0 0 3 * * *"));
        when(dataSourceService.triggerSync(1L)).thenReturn(
                SyncResult.failure("boom", List.of(), null),
                SyncResult.failure("boom", List.of(), null),
                new SyncResult(3, List.of(Map.of("id", 1)), null),
                SyncResult.failure("boom", List.of(), null));
        syncScheduler.register(config(true, "0 0 3 * * *"));

        syncScheduler.scheduleSync(1L);
        syncScheduler.scheduleSync(1L);
        assertThat(syncScheduler.failureCount(1L)).isEqualTo(2);

        syncScheduler.scheduleSync(1L);
        assertThat(syncScheduler.failureCount(1L)).as("成功轮次应清零计数").isZero();

        syncScheduler.scheduleSync(1L);
        assertThat(syncScheduler.failureCount(1L)).isEqualTo(1);
        assertThat(syncScheduler.isScheduled(1L)).isTrue();
    }

    @Test
    @DisplayName("★ 上一轮未结束时跳过本轮，且**不计入失败**（否则长同步会被自己的单飞锁误熔断）")
    void scheduleSync_shouldNotCountFailure_whenPreviousRoundStillRunning() {
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "0 0 3 * * *"));
        when(dataSourceService.isSyncInFlight(1L)).thenReturn(true);

        for (int i = 0; i < 10; i++) {
            syncScheduler.scheduleSync(1L);
        }

        assertThat(syncScheduler.failureCount(1L)).isZero();
        verify(dataSourceService, never()).triggerSync(1L);
    }

    @Test
    @DisplayName("重新注册（配置变更 / 恢复通道）会清零失败计数")
    void register_shouldResetFailureCount() {
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "0 0 3 * * *"));
        when(dataSourceService.triggerSync(1L)).thenReturn(SyncResult.failure("boom", List.of(), null));
        syncScheduler.scheduleSync(1L);
        syncScheduler.scheduleSync(1L);
        assertThat(syncScheduler.failureCount(1L)).isEqualTo(2);

        syncScheduler.register(config(true, "0 0 3 * * *"));

        assertThat(syncScheduler.failureCount(1L)).isZero();
    }

    @Test
    @DisplayName("熔断阈值默认 5，可被配置覆盖")
    void maxConsecutiveFailures_shouldBeConfigurable() {
        assertThat(ReflectionTestUtils.getField(syncScheduler, "maxConsecutiveFailures"))
                .isEqualTo(5);

        ReflectionTestUtils.setField(syncScheduler, "maxConsecutiveFailures", 1);
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "0 0 3 * * *"));
        when(dataSourceService.triggerSync(1L)).thenReturn(SyncResult.failure("boom", List.of(), null));
        syncScheduler.register(config(true, "0 0 3 * * *"));

        syncScheduler.scheduleSync(1L);

        assertThat(syncScheduler.isScheduled(1L)).isFalse();
    }

    @Test
    @DisplayName("★ scheduleSync 只做门禁，实际执行委派给 DataSourceService（消除重复实现）")
    void scheduleSync_shouldDelegateToDataSourceService() {
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "0 0 3 * * *"));
        when(dataSourceService.triggerSync(1L)).thenReturn(SyncResult.empty());

        syncScheduler.scheduleSync(1L);

        verify(dataSourceService).triggerSync(1L);
    }

    @Test
    @DisplayName("★ status=INACTIVE 的数据源不被调度")
    void scheduleSync_shouldSkip_whenStatusInactive() {
        DataSourceConfig inactive = config(true, "0 0 3 * * *");
        inactive.setStatus(DataSourceConfig.STATUS_INACTIVE);
        when(dataSourceMapper.selectById(1L)).thenReturn(inactive);

        syncScheduler.scheduleSync(1L);

        verify(dataSourceService, never()).triggerSync(anyLong());
    }

    @Test
    @DisplayName("★ syncEnabled=false 的数据源不被调度")
    void scheduleSync_shouldSkip_whenSyncDisabled() {
        DataSourceConfig disabled = config(true, "0 0 3 * * *");
        disabled.setSyncEnabled(false);
        when(dataSourceMapper.selectById(1L)).thenReturn(disabled);

        syncScheduler.scheduleSync(1L);

        verify(dataSourceService, never()).triggerSync(anyLong());
    }

    @Test
    @DisplayName("★ 上一轮未结束时本轮被单飞锁拒绝，且调度线程不抛异常")
    void scheduleSync_shouldNotThrow_whenConcurrentTriggerRejected() {
        when(dataSourceMapper.selectById(1L)).thenReturn(config(true, "0 0 3 * * *"));
        when(dataSourceService.triggerSync(1L))
                .thenThrow(new BusinessException("该数据源正在同步中，已拒绝并发触发: id=1"));

        assertThatCode(() -> syncScheduler.scheduleSync(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("配置已删除时任务被自动取消")
    void scheduleSync_shouldUnregister_whenConfigMissing() {
        syncScheduler.register(config(true, "0 0 3 * * *"));
        when(dataSourceMapper.selectById(1L)).thenReturn(null);

        syncScheduler.scheduleSync(1L);

        assertThat(syncScheduler.isScheduled(1L)).isFalse();
        verify(dataSourceService, never()).triggerSync(anyLong());
    }

    // ==================== 辅助 ====================

    private DataSourceConfig config(boolean enabled, String cron) {
        DataSourceConfig config = new DataSourceConfig();
        config.setId(1L);
        config.setSourceName("zz_b05_sched");
        config.setSourceType("JDBC");
        config.setStatus(DataSourceConfig.STATUS_ACTIVE);
        config.setSyncCron(cron);
        config.setSyncEnabled(enabled);
        return config;
    }
}
