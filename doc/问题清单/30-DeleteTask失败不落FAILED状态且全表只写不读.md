# 30 · DeleteTask 失败不落 FAILED 状态且全表只写不读

> **严重程度**：🟡 中
> **所属模块**：agent-qr-compensation（DocumentDeleteServiceV2、DeleteTask、DeleteTaskMapper）
> **设计依据**：《系统详细设计说明书》§10.4 DocumentDeleteServiceV2（失败应 `updateStatus(taskId,"FAILED")`）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

`DocumentDeleteServiceV2.asyncPhysicalDelete` 在 ChromaDB 物理删除失败时，只执行了 `incrementRetryCount` 与 DLQ 入队，**没有把任务状态置为 `FAILED`**。

同时，`DeleteTask` 实体中定义的 `STATUS_FAILED` 常量**全仓库无任何引用**，且**没有任何代码读取 `delete_task` 表**（只有写入与状态更新，没有查询）。因此：

- 失败的任务永久停留在 `PENDING`；
- 运维无法通过该表了解删除任务的执行情况；
- 该表对系统而言是"只写不读"的黑洞。

设计 §10.4（说明书 5525 行）明确要求失败时调用 `deleteTaskMapper.updateStatus(taskId, "FAILED")`。

---

## 二、推断依据

### 依据 1：失败分支缺少状态更新

`agent-qr-compensation/src/main/java/org/example/agent_qr/compensation/service/DocumentDeleteServiceV2.java:66-83`

```java
try {
    if (chromaRetriever != null) {
        chromaRetriever.deleteByIds(chromaIds);
    } else {
        log.warn("ChromaRetriever 未初始化，跳过物理删除: documentId={}", documentId);
    }

    // 3. 标记完成
    deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_DONE);      // :73
    ...
} catch (Exception e) {
    log.error("ChromaDB 物理删除失败: documentId={}, error={}", documentId, e.getMessage(), e);
    deleteTaskMapper.incrementRetryCount(task.getId());                       // :78
    // DLQ 入队
    deadLetterQueue.enqueue("DELETE", documentId, payload, e);                // :82
    // ← 缺 updateStatus(task.getId(), STATUS_FAILED)
}
```

方法开头的 Javadoc（`:41`）明确写着"失败 → incrementRetryCount + DLQ 入队"，**设计文档要求的状态更新在 Javadoc 与实现中都不见了**——说明是有意简化还是遗漏需确认，但结果与设计文档 §10.4 不符。

### 依据 2：`STATUS_FAILED` 常量无引用

`agent-qr-compensation/.../entity/DeleteTask.java:42`

```java
public static final String STATUS_FAILED = "FAILED";
```

```bash
grep -rn "STATUS_FAILED" --include=*.java .
# 仅命中定义处，无使用
```

### 依据 3：`delete_task` 表只写不读

```bash
grep -rn "delete_task" . --include=*.java --include=*.xml
# 仅命中 DeleteTask 实体的 @TableName 与 Mapper 的写入/更新方法
```

`DeleteTaskMapper` 提供 `updateStatus`、`incrementRetryCount` 等写方法，但**没有任何 select 方法**；全仓无任何地方查询该表。

### 依据 4：表结构与设计一致

`agent-qr-web/src/main/resources/db/p2-schema.sql:34-42` 的 `delete_task` 建表语句与设计一致，字段齐备——**问题不在表设计，而在使用方式**。

补充：`DocumentDeleteServiceV2.java:52` 把 `chromaIds` 用 `String.join(",", ...)` 存为逗号分隔串，而 `DeleteTask.java:28-29` 的注释描述为 JSON 数组——注释与实现不符（虽不影响功能）。

---

## 三、影响范围

1. **不可观测**：删除任务失败后，`delete_task` 表里只有状态为 `PENDING` 的记录，与"正在执行"无法区分。
2. **无重试依据**：即便将来实现 DLQ 重试（文档 02），也没有"哪些任务需要重试"的查询能力——当前只能靠 `dlq_message` 表。
3. **运维盲区**：无法统计删除成功率、失败原因分布。
4. **与文档 02、29 叠加**：删除链路的三个环节（DLQ 重试空壳、孤儿扫描漏检、任务表不可观测）都不具备兜底与可观测能力。

---

## 四、修复方向

1. **失败分支补状态更新**：`catch` 中增加 `deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_FAILED);`
2. **补查询接口**：
   - `DeleteTaskMapper` 增加按状态/文档 ID 查询的方法；
   - 在 `MaintenanceController`（已存在）中暴露"查询失败任务列表"的运维端点，供人工排查与补偿。
3. **修正注释**：`DeleteTask.java:28-29` 的 JSON 数组描述与实际逗号分隔串对齐（或改为真正存 JSON）。
4. **纳入看板**：建议在统计模块或运维页面展示删除任务的成功/失败计数。

---

## 五、核查边界

- 静态分析，未连接数据库确认 `delete_task` 表的实际数据分布。
- 未确认 DLQ 重试机制在修复后是否应直接从 `delete_task` 取待重试任务（这取决于文档 02 的修复方案选择）。
- 未评估"删除任务失败"在当前实际部署中的发生频率。
