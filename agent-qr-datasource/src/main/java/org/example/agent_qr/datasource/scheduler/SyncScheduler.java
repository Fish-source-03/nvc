package org.example.agent_qr.datasource.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.datasource.service.DataSourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 数据同步调度器（设计 §8.7.5：Cron 定时 + {@link TaskScheduler} 动态注册）。
 * <p>
 * <b>批次 05 · 任务 5.3 改造前的状况（问题 22）</b>：本类既无 {@code @Scheduled}
 * 也无 {@code TaskScheduler}，全仓库无调用方，实体与建表脚本也没有 cron 字段——
 * "定时同步"这一 P2 核心能力实际不存在，链路只能由人工点击按钮启动。
 * </p>
 * <h3>本类职责（改造后）</h3>
 * <ol>
 *   <li><b>注册</b>：应用启动时（{@link ApplicationReadyEvent}）按数据库配置批量注册
 *       Cron 任务；数据源配置新增/修改/删除时由 {@link DataSourceService} 触发增量
 *       {@link #register(DataSourceConfig)} / {@link #unregister(Long)}。</li>
 *   <li><b>门禁</b>：任务触发时校验状态与开关，并做并发控制。</li>
 *   <li><b>委派</b>：真正的同步执行只有一份实现——{@link DataSourceService#triggerSync(Long)}。
 *       本类不再复制"查配置 → 找连接器 → 同步 → 写记录 → 发事件"的流程（任务 5.3.4）。</li>
 * </ol>
 * <h3>并发控制（任务 5.3.3）</h3>
 * <p>
 * 同一数据源的单飞锁由 {@link DataSourceService#triggerSync(Long)} 持有——
 * 手动触发与定时触发共享同一把锁，上一轮未结束时本轮被拒绝，避免调度任务堆积。
 * </p>
 * <h3>状态校验与"两个半场自洽"（返工修订）</h3>
 * <p>
 * 注册条件与运行期条件<b>共用同一个判定</b> {@link #isScheduleEnabled(DataSourceConfig)}：
 * <b>非 {@code INACTIVE} + cron 非空 + 未显式停用</b>。
 * </p>
 * <p>
 * 初版曾写成"注册只接受 ACTIVE、运行期放行 ERROR"，两半不自洽：ERROR 数据源重启后
 * 不再注册（永远无法自愈），而未重启时又会每个 tick 重试一次。现统一为
 * "只有 {@code INACTIVE}（操作员显式停用）才停"：{@code ERROR} 只表示
 * "上次同步失败"，是亟需重试的对象。
 * </p>
 * <h3>重试风暴防护（熔断）</h3>
 * <p>
 * "ERROR 放行 + 短周期 cron"若不加限制会造成失败重试风暴（实测 1 秒 cron 下
 * 59 条 FAILED/59 秒，且每次真实发起连接）。因此引入<b>连续失败熔断</b>：
 * 同一数据源连续失败达到 {@code agent-qr.datasource.sync.max-consecutive-failures}
 * （默认 5）次即取消其定时任务并 WARN，最多只产生 N 条 FAILED 记录。
 * </p>
 * <p>
 * <b>恢复通道</b>（熔断后如何自愈）：
 * <ol>
 *   <li>手动同步成功 → {@link DataSourceService} 发现该数据源未注册 → 重新注册并清零计数；</li>
 *   <li>配置更新（PUT）→ {@link DataSourceService#update} 重新注册并清零计数；</li>
 *   <li>应用重启 → 按数据库配置重新注册（status=ERROR 同样注册）。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class SyncScheduler {

    @Autowired
    private DataSourceMapper dataSourceMapper;

    /**
     * 连续失败熔断阈值（返工新增）：连续失败达到该次数即暂停该数据源的定时任务。
     */
    @Value("${agent-qr.datasource.sync.max-consecutive-failures:5}")
    private int maxConsecutiveFailures = 5;

    /** 各数据源的连续失败计数（仅存活于本 JVM；持久化的健康信号是 data_source_config.status） */
    private final Map<Long, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /**
     * 唯一的同步实现（任务 5.3.4：消除 90% 重复逻辑）。
     * <p>此处使用 {@code @Lazy} 打断
     * {@code SyncScheduler → DataSourceService → SyncScheduler} 的循环依赖
     * （配置变更后需要重新注册任务）。</p>
     */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private DataSourceService dataSourceService;

    /**
     * Spring 容器提供的调度器（{@code @EnableScheduling} 由
     * {@code TaskSchedulingAutoConfiguration} 提供 {@code ThreadPoolTaskScheduler}）。
     * <p>声明为可选注入，缺失时回退自建单线程调度器，避免因 Bean 缺失导致应用启动失败。</p>
     */
    @Autowired(required = false)
    private TaskScheduler taskScheduler;

    /** 已注册的定时任务：数据源 ID → Future */
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * 应用启动完成后按数据库配置批量注册定时同步任务（设计 §8.7.5）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerAllScheduledSyncs() {
        List<DataSourceConfig> configs =
                dataSourceMapper.selectList(new LambdaQueryWrapper<>());
        int registered = 0;
        for (DataSourceConfig config : configs) {
            if (isScheduleEnabled(config)) {
                register(config);
                registered++;
            }
        }
        log.info("定时同步任务批量注册完成: 数据源总数={}, 已注册={}, cron 示例={}",
                configs.size(), registered,
                configs.stream()
                        .filter(c -> c.getSyncCron() != null && !c.getSyncCron().isBlank())
                        .findFirst()
                        .map(DataSourceConfig::getSyncCron)
                        .orElse("-"));
    }

    /**
     * 注册（或重新注册）指定数据源的定时同步任务。
     * <p>
     * 内部先取消旧任务，因此 <b>Cron 表达式变更后调用本方法即可生效</b>（任务 5.3.2）。
     * </p>
     *
     * @param config 数据源配置
     */
    public void register(DataSourceConfig config) {
        if (config == null || config.getId() == null) {
            return;
        }
        unregister(config.getId());
        if (!isScheduleEnabled(config)) {
            log.debug("数据源不满足定时同步条件（INACTIVE / cron 为空 / 已停用），跳过: "
                            + "id={}, status={}, syncCron={}, syncEnabled={}",
                    config.getId(), config.getStatus(), config.getSyncCron(), config.getSyncEnabled());
            return;
        }
        try {
            ScheduledFuture<?> future = scheduler().schedule(
                    () -> scheduleSync(config.getId()),
                    new CronTrigger(config.getSyncCron()));
            scheduledTasks.put(config.getId(), future);
            log.info("已注册定时同步: id={}, sourceName={}, cron={}",
                    config.getId(), config.getSourceName(), config.getSyncCron());
        } catch (Exception e) {
            log.error("定时同步注册失败（Cron 表达式非法？）: id={}, cron={}, error={}",
                    config.getId(), config.getSyncCron(), e.getMessage());
        }
    }

    /**
     * 取消指定数据源的定时同步任务。
     *
     * @param datasourceId 数据源 ID
     */
    public void unregister(Long datasourceId) {
        if (datasourceId == null) {
            return;
        }
        // 取消 = 重新开始：连续失败计数一并清零（熔断后的重新注册从 0 起算）
        consecutiveFailures.remove(datasourceId);
        ScheduledFuture<?> future = scheduledTasks.remove(datasourceId);
        if (future != null) {
            future.cancel(false);
            log.info("已取消定时同步: id={}", datasourceId);
        }
    }

    /**
     * 调度指定数据源的同步任务。
     * <p>
     * <b>本方法只做门禁，不复制同步逻辑</b>——实际执行统一委派给
     * {@link DataSourceService#triggerSync(Long)}（任务 5.3.4）。
     * </p>
     *
     * @param datasourceId 数据源配置 ID
     */
    public void scheduleSync(Long datasourceId) {
        DataSourceConfig config = dataSourceMapper.selectById(datasourceId);
        if (config == null) {
            log.warn("同步调度失败：数据源不存在, id={}", datasourceId);
            unregister(datasourceId);
            return;
        }
        if (!isScheduleEnabled(config)) {
            log.warn("同步调度跳过：数据源未启用定时同步, id={}, status={}, syncEnabled={}",
                    datasourceId, config.getStatus(), config.getSyncEnabled());
            return;
        }
        // 上一轮尚未结束：本轮直接跳过。**不计入失败**——长同步 + 短周期 cron 下，
        // 每一跳都会被单飞锁拒绝，若把拒绝当失败会立刻误触熔断。
        if (dataSourceService.isSyncInFlight(datasourceId)) {
            log.warn("上一轮同步尚未结束，跳过本轮调度: id={}", datasourceId);
            return;
        }
        try {
            SyncResult result = dataSourceService.triggerSync(datasourceId);
            if (result != null && result.isSuccess()) {
                resetFailures(datasourceId);
                log.info("定时同步完成: id={}, sourceName={}, totalRows={}",
                        datasourceId, config.getSourceName(), result.getTotalRows());
            } else {
                recordFailure(datasourceId,
                        result != null ? result.getErrorMessage() : "同步结果为空");
            }
        } catch (BusinessException e) {
            if (dataSourceService.isSyncInFlight(datasourceId)) {
                log.warn("上一轮同步尚未结束，跳过本轮调度: id={}", datasourceId);
                return;
            }
            // 连接配置解析错误等业务异常：计入失败，由熔断器兜住重试风暴
            recordFailure(datasourceId, e.getMessage());
        } catch (Exception e) {
            log.error("同步调度执行异常: id={}", datasourceId, e);
            recordFailure(datasourceId, e.getMessage());
        }
    }

    /**
     * 累计一次失败；达到阈值即熔断（取消定时任务 + WARN），避免失败重试风暴。
     * <p>
     * 熔断后由"手动同步成功 / 配置更新 / 应用重启"三条恢复通道重新注册。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @param reason       失败原因
     */
    private void recordFailure(Long datasourceId, String reason) {
        int failures = consecutiveFailures.merge(datasourceId, 1, Integer::sum);
        if (failures >= maxConsecutiveFailures) {
            consecutiveFailures.remove(datasourceId);
            unregister(datasourceId);
            log.error("数据源连续 {} 次同步失败，已暂停其定时同步（熔断）: id={}, 最近失败原因={}。"
                            + "修复后可通过「手动同步成功」或「重新保存数据源配置」恢复定时调度。",
                    failures, datasourceId, reason);
        } else {
            log.warn("定时同步失败 {}/{} 次: id={}, reason={}",
                    failures, maxConsecutiveFailures, datasourceId, reason);
        }
    }

    /**
     * 清零连续失败计数（成功轮次 / 重新注册 / 恢复通道调用）。
     *
     * @param datasourceId 数据源 ID
     */
    public void resetFailures(Long datasourceId) {
        if (datasourceId != null) {
            consecutiveFailures.remove(datasourceId);
        }
    }

    /**
     * 当前连续失败计数（供测试与运维断言）。
     *
     * @param datasourceId 数据源 ID
     * @return 连续失败次数
     */
    public int failureCount(Long datasourceId) {
        return consecutiveFailures.getOrDefault(datasourceId, 0);
    }

    /**
     * 判断数据源当前是否已注册定时任务（供测试与运维断言）。
     *
     * @param datasourceId 数据源 ID
     * @return 已注册返回 true
     */
    public boolean isScheduled(Long datasourceId) {
        ScheduledFuture<?> future = scheduledTasks.get(datasourceId);
        return future != null && !future.isDone() && !future.isCancelled();
    }

    /**
     * 定时同步的统一判定：注册与运行期<b>共用本方法</b>，保证两个半场自洽。
     * <p>
     * 条件：非 {@code INACTIVE}（操作员显式停用）+ cron 非空 + 未显式停用。
     * {@code ACTIVE} 与 {@code ERROR} 都可以被调度——{@code ERROR} 表示"上次同步失败"，
     * 正是需要重试的对象；若在此排除它，一次瞬时故障就会让数据源永远不再被调度。
     * 失败重试的上界由连续失败熔断（{@link #maxConsecutiveFailures}）负责。
     * </p>
     *
     * @param config 数据源配置
     * @return 允许调度返回 true
     */
    private boolean isScheduleEnabled(DataSourceConfig config) {
        return config != null
                && !DataSourceConfig.STATUS_INACTIVE.equals(config.getStatus())
                && config.getSyncCron() != null
                && !config.getSyncCron().isBlank()
                && !Boolean.FALSE.equals(config.getSyncEnabled());
    }

    /**
     * 获取调度器：优先使用 Spring 容器 Bean，缺失时自建单线程调度器。
     *
     * @return 调度器
     */
    private TaskScheduler scheduler() {
        TaskScheduler current = taskScheduler;
        if (current == null) {
            synchronized (this) {
                if (taskScheduler == null) {
                    ThreadPoolTaskScheduler fallback = new ThreadPoolTaskScheduler();
                    fallback.setPoolSize(1);
                    fallback.setThreadNamePrefix("sync-scheduler-");
                    fallback.setRemoveOnCancelPolicy(true);
                    fallback.initialize();
                    taskScheduler = fallback;
                    log.warn("容器未提供 TaskScheduler，已回退为自建单线程调度器");
                }
                current = taskScheduler;
            }
        }
        return current;
    }
}
