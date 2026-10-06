# 22 · SyncScheduler 为死代码导致定时同步链路缺失

> **严重程度**：🔴 高
> **所属模块**：agent-qr-datasource（SyncScheduler、DataSourceConfig）
> **设计依据**：《系统详细设计说明书》§8.7.5 SyncScheduler（同步调度器）、§15.3 多源数据接入与 ETL 处理时序图
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

设计 §8.7.5 要求 `SyncScheduler` 实现"Cron 定时 + `TaskScheduler` 动态注册"的调度能力，作为 §15.3 时序图中数据同步链路的**起点**。

实际 `SyncScheduler` 存在三个缺失：

1. **无任何调度能力**：没有 `@Scheduled`、没有 `TaskScheduler`、没有 cron 字段；
2. **无任何调用方**：全仓库无代码调用 `scheduleSync(...)`；
3. **无配套的持久化支撑**：`DataSourceConfig` 实体与 `data_source_config` 建表脚本中都没有 cron / 调度周期列。

即：`SyncScheduler` 实际上只是一个"手动触发一次同步"的普通方法，"定时同步"这个 P2 的核心能力**不存在**。

---

## 二、推断依据

### 依据 1：类上没有调度注解

`agent-qr-datasource/src/main/java/org/example/agent_qr/datasource/scheduler/SyncScheduler.java:29-31,55`

```java
@Slf4j
@Component
public class SyncScheduler {
    ...
    public void scheduleSync(Long datasourceId) {       // 普通方法，无 @Scheduled
```

全仓库检索 `@Scheduled` 的 5 处命中（见文档 01）**不含本类**；`TaskScheduler`、`CronTrigger` 全仓零命中。

### 依据 2：无任何调用方

```bash
grep -rn "scheduleSync" --include=*.java . | grep -v target
# 仅命中方法定义处
```

`DataSourceService.triggerSync(...)`（`:149-234`）是另一份**约 90% 重复**的实现，由 `POST /api/datasource/{id}/sync` 手动触发。两者逻辑重复但互不调用。

### 依据 3：实体与数据库均无调度字段

`agent-qr-datasource/.../entity/DataSourceConfig.java:20-79` 无 cron / 调度周期 / 下次执行时间字段；

`agent-qr-web/src/main/resources/db/p2-schema.sql:45-62` 的 `data_source_config` 建表语句同样没有这些列。

因此即便想配置定时同步，**也没有存储位置**。

### 依据 4：设计文档要求的其他行为也缺失

| 设计要求 | 实际 |
|---|---|
| 校验 `status != ACTIVE` 则跳过 | `SyncScheduler.java:56-60` 只判 `config == null` |
| 单次同步并发控制 | 无锁、无单飞去重 |
| Cron 表达式配置 | 实体与表均无该字段 |

### 依据 5：时序图的起点不存在

§15.3 的多源数据接入时序图以"定时器 Cron 触发"为起点。由于依据 1-3，该起点在实现中不存在——链路实际只能由人工点击按钮启动。

---

## 三、影响范围

1. **P2 核心能力缺失**：SRS §5.2 要求"知识库数据保持准实时（不超过 30 分钟延迟）"。没有定时同步，数据更新完全依赖人工触发——即便解决了文档 21 的性能问题，准实时目标仍不达成。
2. **前端也无法定时**：前端 `DataSourceView` 只有手动"同步"按钮，无调度配置界面。
3. **代码重复埋雷**：`SyncScheduler.scheduleSync` 与 `DataSourceService.triggerSync` 两份实现并存，未来修改容易只改一处。
4. **与文档 01 无关**：即使补上 `@EnableScheduling`，本类因为没有 `@Scheduled` 注解仍不会被执行——两个问题是独立的。

---

## 四、修复方向

1. **补齐调度能力**（按设计 §8.7.5）：
   - 给 `DataSourceConfig` 增加 `syncCron` / `syncEnabled` 字段（同步修改 `data_source_config` 表）；
   - 在 `SyncScheduler` 中用 `TaskScheduler` 动态注册 Cron 任务，应用启动时按数据库配置批量注册；
   - 增加 `status != ACTIVE` 校验与单次同步的并发控制（同一数据源互斥）。
2. **消除重复实现**：让 `SyncScheduler.scheduleSync` 调用 `DataSourceService.triggerSync`（或反向），只保留一份同步逻辑。
3. **前置依赖**：需先解决文档 01 的 `@EnableScheduling` 缺失。
4. **补充前端入口**：在数据源管理页增加调度周期配置项（当前 UI 无此能力）。

---

## 五、核查边界

- 静态分析，未运行服务，未确认是否存在外部脚本/定时任务调用 `/sync` 接口（如 crontab、K8s CronJob）——仓库内无相关配置。
- 未确认外部调度系统（如 XXL-Job）是否已接入——pom 中无相关依赖。
- 未评估 `TaskScheduler` 动态注册在当前 Spring Boot 3.5.15 下的具体 API 形态。
