# 32 · 文档删除事件 filePath 无消费者导致物理文件不清理

> **严重程度**：🟡 中
> **所属模块**：agent-qr-knowledge（DocumentCommandService）、agent-qr-compensation（DocumentDeleteListener）
> **设计依据**：《系统详细设计说明书》§7.2.6 `DocumentDeleteRequestedEvent`（filePath「用于清理上传文件」）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

`DocumentDeleteRequestedEvent` 携带 `filePath` 字段，设计 §7.2.6（说明书 2946-2947 行）明确说明该字段**用于清理上传的物理文件**。

实际：事件发布方正常填充了 `filePath`，但**消费方从未读取它**——全仓库对 `getFilePath()` 的调用只出现在 knowledge 模块内部，compensation 侧的 `DocumentDeleteListener` 只使用 `documentId` 与 `chromaIds`。

同时，唯一会调用 `fileStorageService.delete(...)` 的方法是已废弃的 `deleteDocument`（标注 `@Deprecated`）。

后果：文档删除后，`uploads/` 目录下的**物理文件永久残留**，磁盘占用只增不减。

---

## 二、推断依据

### 依据 1：事件字段设计意图明确

`agent-qr-common/.../event/DocumentDeleteRequestedEvent.java` 的字段定义含 `filePath`；设计 §7.2.6 的字段表注明其用途为"清理上传文件"。

### 依据 2：发布方正常填充

`agent-qr-knowledge/.../service/DocumentCommandService.java:109-111` 在 `requestDeleteDocument` 中构造并发布事件，携带 `filePath`。

### 依据 3：消费方未使用

`agent-qr-compensation/.../listener/DocumentDeleteListener.java:52-74`：方法体内只处理 `documentId` 与 `chromaIds`，**无任何 `getFilePath()` 调用**。

```bash
grep -rn "getFilePath" --include=*.java . | grep -v target
# 仅 2 处命中，均在 agent-qr-knowledge 模块内
```

### 依据 4：唯一的文件删除调用位于废弃方法中

`agent-qr-knowledge/.../service/DocumentCommandService.java:117-128` 的 `deleteDocument` 标注 `@Deprecated`（设计 §5.2.1 要求保留但不使用），该方法内有 `fileStorageService.delete(...)` 调用。

即：**新的删除链路（事件驱动）没有接上文件清理，旧的删除链路（已废弃）才有**。这条能力在从 v1 迁移到 v2 时丢失了。

### 依据 5：`chunkIds` 字段同样未被消费

除 `filePath` 外，事件中的 `chunkIds` 字段在消费方也未被使用（监听器只用了 `documentId` 与 `chromaIds`）。这属于复盘报告 §3 问题 2 所指的"事件字段定义与消费方实际使用脱节"的又一实例。

---

## 三、影响范围

1. **磁盘持续增长**：删除操作不减磁盘占用，长期运行后 `uploads/` 目录会积累大量孤儿文件。对大文件（PDF、Office 文档）尤为明显。
2. **隐私/合规风险**：用户以为删除了文档，实际文件仍在服务器磁盘上。若涉及敏感内容，属未完成的数据删除。
3. **与文档 29 叠加**：MySQL 侧删除成功、Chroma 侧可能残留、文件侧确定残留——删除操作只在数据库层面"看起来"完成了。
4. **无任何提示**：日志中不会出现相关告警，问题完全静默。

---

## 四、修复方向

1. **在 `DocumentDeleteListener` 中接上文件清理**：读取 `event.getFilePath()`，调用 `FileStorageService.delete(...)`（需确认该方法的可见性与所在模块——若在 knowledge 模块，需注意 compensation → knowledge 的依赖方向是否允许，当前 compensation 已单向依赖 knowledge，可行）。
2. **顺序考虑**：文件删除应放在 MySQL 逻辑删除之后、或与 ChromaDB 删除并列，失败时同样入 DLQ（复用 `DELETE` 类型）。
3. **幂等性**：文件可能已被人工删除，删除方法应对"文件不存在"静默处理。
4. **历史清理**：建议提供一次性运维脚本，清理 `uploads/` 下已无对应数据库记录的孤儿文件。
5. **顺带处理 `chunkIds`**：确认该字段是否仍需保留在事件中——若无人使用，应删除或明确其用途（避免"事件字段越加越多但没人读"）。

---

## 五、核查边界

- 静态分析，未检查 `uploads/` 目录的实际文件数量与孤儿文件占比。
- 未确认 `FileStorageService.delete(...)` 的方法签名与所属模块（这决定修复方案 1 的实现方式）。
- 未确认是否存在外部的文件清理脚本或运维任务——仓库内无相关配置。
