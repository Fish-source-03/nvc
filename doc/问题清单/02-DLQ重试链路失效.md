# 02 · DLQ 重试链路失效（重试体为空壳 + 事件类型契约不一致）

> **严重程度**：🔴 高
> **所属模块**：agent-qr-web（DlqRetryScheduler）、agent-qr-common（DeadLetterQueue）、agent-qr-knowledge（事件入队方）
> **设计依据**：《系统详细设计说明书》§7.1、§10.1 决策 5、§17.3（死信队列指数退避重试）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行服务）

---

## 一、问题描述

DLQ（死信队列）在本项目中被设计为事件驱动链路的"可恢复降级锚点"。核查发现该链路存在两个叠加缺陷：

1. **重试体是空壳**：`DlqRetryScheduler` 的四个 `retryXxx` 方法只打印日志便直接调用 `updateRetryResult(msgId, true)` 将消息标记为成功，**不重放任何业务动作**。由于 `DeadLetterQueue` 在收到 success 后会物理删除该行记录，失败任务被静默清除。
2. **事件类型契约不一致**：调度器只识别 `PARSE/CHUNK/EMBED/DELETE` 四种类型，而实际入队方会写入 `"ETL"` 与 `"CHROMA_WRITE"`，全部落入 `default` 分支——该分支同样以"成功"处理并删除消息。

两者叠加的后果：**ETL 与向量写入的失败既不重试、不告警，也不留痕**。

---

## 二、推断依据

### 依据 1：四个重试方法均为空壳

`agent-qr-web/src/main/java/org/example/agent_qr/web/scheduler/DlqRetryScheduler.java:74-103`

```java
private void retryParse(DlqMessage msg) {
    log.info("DLQ 重试解析: msgId={}, documentId={}", msg.getId(), msg.getDocumentId());
    if (parserService != null) {
        // 重新解析逻辑（简化：标记成功，实际项目需从 payload 提取参数）
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);
    } else { ... }
}

private void retryChunk(DlqMessage msg) {
    log.info("DLQ 重试切片: msgId={}, documentId={}", msg.getId(), msg.getDocumentId());
    deadLetterQueue.updateRetryResult(msg.getId(), true, null);
}

private void retryEmbed(DlqMessage msg) { /* 同上，仅打日志 + 标记成功 */ }

private void retryDelete(DlqMessage msg) {
    log.info("DLQ 重试删除: msgId={}, documentId={}", msg.getId(), msg.getDocumentId());
    if (documentDeleteServiceV2 != null) {
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);   // 未调用 asyncPhysicalDelete
    } else { ... }
}
```

代码注释已自述"简化：标记成功"，但设计文档 §7.1（说明书 2881-2894 行）明确要求按类型重放业务动作。

### 依据 2：标记成功即删除记录

`agent-qr-common/src/main/java/org/example/agent_qr/common/dlq/DeadLetterQueue.java:77-79`

```java
public void updateRetryResult(Long id, boolean success, Throwable error) {
    // success == true → 直接从 dlq_message 表删除
```

### 依据 3：未知事件类型被静默当成功删除

`DlqRetryScheduler.java:56-65`

```java
switch (msg.getEventType()) {
    case "PARSE" -> retryParse(msg);
    case "CHUNK" -> retryChunk(msg);
    case "EMBED" -> retryEmbed(msg);
    case "DELETE" -> retryDelete(msg);
    default -> {
        log.warn("DLQ 未知事件类型: {}, msgId={}", msg.getEventType(), msg.getId());
        deadLetterQueue.updateRetryResult(msg.getId(), true, null);   // 当成功删除
    }
}
```

### 依据 4：实际入队类型与约定不符

| 入队位置 | 实际 eventType | 后果 |
|---|---|---|
| `agent-qr-knowledge/.../listener/DataSyncEtlListener.java:86` | `"ETL"`（配置缺失） | 落 default 分支，当成功删除 |
| `agent-qr-knowledge/.../listener/DataSyncEtlListener.java:140` | `"CHROMA_WRITE"`（写入 ChromaDB 失败） | 同上 |
| `agent-qr-knowledge/.../listener/DataSyncEtlListener.java:149` | 向量化失败 | 同上 |
| `agent-qr-knowledge/.../listener/DataSyncEtlListener.java:171` | `"ETL"`（外层兜底） | 同上 |
| `agent-qr-knowledge/.../listener/ChunkEmbeddingListener.java:113` | `"CHROMA_WRITE"` | 同上 |

约定的四种类型 `PARSE/CHUNK/EMBED/DELETE` 与 `DlqMessage` 实体注释（`agent-qr-common/.../dlq/entity/DlqMessage.java:30`）一致，但两个入队方均未遵守。

### 依据 5：退避算法本身是正确的（问题不在算法）

`DeadLetterQueue.java:50-64,116-118` 的指数退避（3^n、max-retries 4、耗尽置 DEAD）与设计 §17.3 一致，`dlq_message` 建表语句（`agent-qr-web/src/main/resources/db/p2-schema.sql:8-19`）也齐备。**缺陷仅在于"重试执行"这一环**。

---

## 三、影响范围

1. **恢复能力归零**：所有经 DLQ 降级的失败（文档解析、切片、向量化、ChromaDB 写入、物理删除、ETL）都不会被重试。
2. **故障被掩盖**：`dlq_message` 表被静默清空，运维查表看不到积压，误以为链路健康。
3. **与文档 01 叠加**：即使补上 `@EnableScheduling`，当前实现也只是让空壳被周期性调用一次，问题依旧。
4. **直接削弱复盘报告"经验 1"**：复盘报告要求每个 Listener 具备独立 try-catch + 降级，本项目 try-catch 与入队都已实现，唯独"重试"没有——降级三段中缺了最关键的一段。

---

## 四、修复方向

1. **补齐重试体**：`retryParse` 应解析 payload 并重新触发解析；`retryDelete` 应调用 `documentDeleteServiceV2` 的实际删除方法；`retryChunk`/`retryEmbed` 同理。
2. **统一事件类型契约**：将 `ETL`、`CHROMA_WRITE` 纳入 `DlqMessage` 的合法类型枚举（并新增对应重试分支），或在入队侧改为约定的四种类型之一。建议前者，因为入队侧的类型语义更清晰。
3. **default 分支改为保守处理**：未知类型不应标记成功删除，应保留记录并告警（静默删除是"数据丢失"而非"降级"）。
4. **补充契约测试**：这正是复盘报告 §3 问题 2 指出的"事件 Schema 靠文档约定"的典型案例，建议对 `eventType` 建立共享枚举。

---

## 五、核查边界

- 静态分析，未运行服务，未实际观测重试行为。
- 未连接数据库确认 `dlq_message` 表当前的实际数据分布。
- `DlqMessage` 的 payload 格式未在代码中定义 schema，评估重试体实现难度时需另行确认。
