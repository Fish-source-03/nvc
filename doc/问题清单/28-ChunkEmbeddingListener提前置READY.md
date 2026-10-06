# 28 · ChunkEmbeddingListener 在向量化完成前提前置 READY

> **严重程度**：🟠 中高
> **所属模块**：agent-qr-knowledge（ChunkEmbeddingListener、BatchEmbeddingService）
> **设计依据**：《系统详细设计说明书》§7.3 监听器设计、§15.1 文档上传异步处理时序图
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

`ChunkEmbeddingListener` 在完成向量化任务**提交**后，立即把文档状态置为 `READY` 并发布 `EmbeddingCompletedEvent`。但 `BatchEmbeddingService.submit(...)` 是**异步**的——提交成功不等于向量化成功，更不等于已写入 ChromaDB。

同时，循环内累加的 `successCount` 统计的是"**提交成功**"的数量，而非"向量化成功"的数量，该值被写入日志、并通过事件传给统计模块。

后果：文档在上传后很快显示 `READY`，但此时向量库可能尚未写入甚至永远写不进去（异步任务失败时）。用户看到"已就绪"却检索不到内容——且系统没有任何机制纠正这个状态。

---

## 二、推断依据

### 依据 1：submit 是异步提交，随后立即置 READY

`agent-qr-knowledge/src/main/java/org/example/agent_qr/knowledge/listener/ChunkEmbeddingListener.java:91-137`

```java
int successCount = 0;
...
        batchEmbeddingService.submit(chunk)      // :95  异步入队
        ...
        successCount++;                          // :123 统计的是"提交成功"
...
// 7. 更新状态为 READY
documentMapper.updateStatus(documentId, DocumentStatus.READY.name());   // :133
log.info("文档处理完成: id={}, 成功切片数={}", documentId, successCount);
...
eventPublisher.publishEvent(new EmbeddingCompletedEvent(this, documentId, successCount));  // :137
```

从 `submit` 到 `updateStatus(READY)` 之间**没有任何等待或回调**，状态更新与异步任务的实际执行完全解耦。

### 依据 2：`submit` 的语义是入队

`agent-qr-rag/.../embedding/BatchEmbeddingService.java` 的 `submit(...)` 把切片放入阻塞队列（容量 2000，见文档 21），由消费者线程异步处理。入队成功不代表处理成功。

### 依据 3：异步失败不影响已置的状态

`BatchEmbeddingService` 的消费循环（`:99-127`）在失败时记录日志并（按设计）做失败降级，但**不会回写文档状态**——即 `ChunkEmbeddingListener` 置的 `READY` 不会被撤销。

### 依据 4：`successCount` 语义错误会传导到统计模块

`EmbeddingCompletedEvent(this, documentId, successCount)` 被 `StatisticsUpdateListener` 消费。因此统计模块记录的"切片数"实际是"提交数"，在向量化失败时**虚高**。

### 依据 5：另一处元数据错误

`ChunkEmbeddingListener.java:89-90` 使用 `doc.getFileName()` 而非 `doc.getTitle()` 作为写入 ChromaDB 的 `document_title` 元数据。检索结果中展示的"文档标题"实际是文件名（含扩展名、时间戳等），与设计意图不符。

### 依据 6：与设计时序图不符

§15.1 文档上传异步处理时序图要求"向量化完成 → 更新状态为 READY"。当前实现是"向量化提交 → 更新状态为 READY"，时序图上少了一个等待环节。

---

## 三、影响范围

1. **状态失真**：`READY` 不再表示"可检索"，而是"已提交"。用户按状态判断文档可用性会得到错误结论。
2. **静默失败**：向量化失败时（如 Ollama 宕机），文档永久停留在 `READY` 但内容检索不到，且无告警（叠加文档 02 的 DLQ 空壳，失败也不会重试）。
3. **统计虚高**：`EmbeddingCompletedEvent` 的计数被统计模块采信，看板数据不实。
4. **排查困难**：用户反馈"文档显示就绪但搜不到"时，从状态字段无法定位原因。

---

## 四、修复方向

> **本问题已升级为一次全链路重构**（项目负责人确认）。原"就地把 READY 延后"的思路被更彻底的方案取代：
> **拆成两个状态 + 向量化改为事件驱动**。
>
> 完整任务见 `doc/修复-tasks/batch-07-向量与索引.md` 的**任务 7.0**。

### 已确认的方案

1. **双状态机**（解决"READY 语义不实"）
   - `DocumentStatus` 新增 `INDEXED`，插在 `CHUNKING` 与 `EMBEDDING` 之间，共 **8 个状态**：
     ```
     UPLOADED → PARSING → CHUNKING → INDEXED → EMBEDDING → READY
                                      ↑          ↑           ↑
                                 已入库       向量化中     向量已写入
                                 BM25 可检索
     ```
   - `INDEXED` 的含义：数据已写入 MySQL，**BM25 可检索**；`READY`：向量已写入 ChromaDB，**语义检索可用**
   - **Chunk 与 Document 都改**：`Chunk` 实体补 `status` 字段映射（当前表有列但实体未映射）；Document 状态由切片**聚合推导**
   - 前端展示两个状态（"完全就绪" vs "部分就绪，关键词可搜"）

2. **向量化改为事件驱动**（解决"提交即置位"的根因）
   - 新增 `ChunksBatchCreatedEvent`（每文档 / 每数据源同步完成发一次，只带标识）
   - 新增 `ChunkEmbeddingBatchListener`（从 MySQL 分批读 → 更新 BM25 索引 → 向量化 → 写 ChromaDB → 回写状态）
   - **两条链路都改**（文档上传 + 数据同步）
   - **`ChunkEmbeddingListener` 退役**（本问题的原发地）

3. **原修复方向中的其余各项，已并入任务 7.0 的迁移清单（7.0.9）**
   - 修正计数语义（`successCount` 统计的是"提交成功"）
   - 修正元数据（`getFileName()` → `getTitle()`）
   - 失败回写 `FAILED`
   - 保留 DLQ 入队

4. **必须同时解决的幂等约束**
   - `ChromaEmbeddingStore` **无 `upsert`**，重复 id 会报 `DuplicateIDError`
   - 新链路必须保证向量化可重复执行（否则失败重试与存量重跑都会失败）

### 原方案中被保留的部分

- "在**确认写入 ChromaDB 成功后**才置 `READY`" —— 这是 `READY` 的核心语义，新方案完全保留
- "引入中间状态" —— 由 `INDEXED` + `EMBEDDING` 两个状态共同承担，比原设想更精确

---

## 五、核查边界

- 静态分析，未运行上传流程，未观测状态变更时序。
- 未确认 `BatchEmbeddingService` 是否已有机理在失败时通知上游（从代码看仅有日志与 DLQ 入队，无状态回写）。
- 未评估改动为"完成后再置 READY"后对上传响应时间与用户体验的影响（可能需要在 UI 上增加中间态展示）。
