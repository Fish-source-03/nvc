# 05 · CQRS 读写分离不生效（无 `@Transactional(readOnly = true)`）

> **严重程度**：🟠 中高
> **所属模块**：agent-qr-common（ReadWriteRoutingDataSource / ReadWriteDataSourceAspect）、agent-qr-web（CqrsDataSourceConfig）、各业务 Service
> **设计依据**：《系统详细设计说明书》§8.13 CQRS 读写分离路由、§8.13.3 Service 层约束
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未运行服务）

---

## 一、问题描述

CQRS 读写分离的机制是：`ReadWriteDataSourceAspect` 拦截 `@Transactional` 方法，读取其 `readOnly` 属性并写入 ThreadLocal；`ReadWriteRoutingDataSource.determineCurrentLookupKey()` 依据该标志决定路由到 `read` 还是 `write` 数据源。

核查发现：**全仓库没有任何一处业务代码使用 `@Transactional(readOnly = true)`**。因此 ThreadLocal 恒为 false，`determineCurrentLookupKey()` 恒返回 `"write"`，**读库永远不会被命中**。整套 CQRS 基础设施（含 P3 新增的两个类、一套独立读库连接池）处于空转状态。

此外，"从库不可用时回退主库"的标志位只被用于打印日志，不参与任何装配决策。

---

## 二、推断依据

### 依据 1：全仓 `readOnly` 命中均在 CQRS 基础设施自身

```bash
grep -rn "readOnly" --include=*.java . | grep -v target
# 共 16 处命中，全部位于：
#   agent-qr-common/.../datasource/ReadWriteDataSourceAspect.java（注释、实现）
#   agent-qr-common/.../datasource/ReadWriteRoutingDataSource.java（注释、实现）
# 无任何一处出现在 Service / Mapper 层的 @Transactional 注解中
```

其中 `ReadWriteDataSourceAspect.java:28-30` 仍在**注释里**描述"`@Transactional(readOnly = true)` → 读库"，说明设计意图明确，但没有任何调用方配合。

### 依据 2：路由逻辑恒走写库

`agent-qr-common/.../datasource/ReadWriteRoutingDataSource.java:96-100`

```java
protected Object determineCurrentLookupKey() {
    boolean readOnly = Boolean.TRUE.equals(READ_ONLY_HOLDER.get());
    return readOnly ? "read" : "write";
}
```

`READ_ONLY_HOLDER` 仅由 `ReadWriteDataSourceAspect.java:53` 的 `setReadOnly(...)` 写入，而该切面只对带 `@Transactional` 的方法生效，且取值来自注解的 `readOnly()` 属性——该属性默认 false，且无任何方法显式设为 true。

### 依据 3：现有事务注解全为默认值

```bash
grep -rn "@Transactional" --include=*.java . | grep -v target
```

命中位置（`DocumentCommandService.java:43,86,118`、`RefreshTokenService.java:41,69,102`、`DataQualityService.java:58`、`FeedbackService.java:39`）**全部为裸 `@Transactional`**（即 `readOnly = false`）。查询侧服务 `DocumentQueryService.java:29` 连 `@Transactional` 都没有，因此连切面都不会进入。

### 依据 4："从库回退主库"是空承诺

`agent-qr-web/.../web/config/CqrsDataSourceConfig.java:37,63-68`

```java
// 字段注释声称：从库不可用时回退主库
private boolean readReplicaFallbackToPrimary;

// 实际使用处
log.info(...readReplicaFallbackToPrimary...);   // 仅参与日志输出
```

该字段未参与任何 Bean 装配或路由决策，因此即使读库宕机，也不会发生"回退"——只会因为读库永不被使用而"看起来没问题"。

---

## 三、影响范围

1. **P3 的读扩展能力为零**：所有查询压力仍然压在写库上，读库连接池只存在于启动日志中。
2. **性能承诺不成立**：设计文档"读写分离"作为 P3 的架构能力列出，实际未生效。
3. **掩盖问题**：因为读库不被使用，读库配置错误（见文档 04 的 localhost 地址）也不会暴露任何异常——两个缺陷互相掩盖。
4. **部署成本白付**：多维护一套读库连接池与一份从库数据库。

---

## 四、修复方向

1. **给查询侧 Service 或 Mapper 方法加 `@Transactional(readOnly = true)`**：这是设计 §8.13.3 明确要求的约定，属于"约定未被遵守"而非"机制有缺陷"。重点目标：`DocumentQueryService`、`StatisticsQueryService`、`CatalogController` 对应的查询服务、`DataSourceService` 的 list/get 方法。
2. **建议在 Service 层统一约定**：读方法一律 `readOnly = true`，并在 Code Review 中检查——否则容易再次漂移。
3. **让 fallback 真正生效**：在 `CqrsDataSourceConfig` 装配读库 Bean 时加入连通性探测，或至少在 `determineCurrentLookupKey` 返回 `read` 前校验读库可用性。
4. 若短期不打算启用读库，应在 `application-p3.yml` 中关闭 `agent-qr.cqrs.enabled`，避免"配置看起来已开启"的误导。

---

## 五、核查边界

- 静态分析，未运行服务，未观测实际 SQL 路由目标。
- 未连接数据库确认读库实例是否存在、是否与写库同源。
- 若项目中存在通过 `TransactionTemplate` 或编程式事务设置 readOnly 的路径，本次 grep（按注解文本匹配）可能遗漏——已用 `readOnly` 全词检索交叉验证，未发现遗漏。
