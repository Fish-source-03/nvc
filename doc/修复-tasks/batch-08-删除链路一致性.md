# 批次 08 · 删除链路一致性修复

> **涵盖问题**：27（软删切片漏出）、31（ChromaRetriever 为空时假成功）、29（孤儿向量扫描漏检）、32（filePath 无消费者）、**R24**（DLQ 向量化重试体与新状态机三处不一致，批次 07 独立验证发现）
> **前置依赖**：**批次 01 任务 1.1**（`DeleteTask` 的 FAILED 状态与查询方法）、**批次 07 任务 7.0.11**（Chroma 枚举 id 能力）、**批次 07 任务 7.0 整体**（新 Listener / `INDEXED` 状态 / UUIDv3 确定性 id —— 8.5 的对齐基线）
> **批次内顺序**：**严格** 8.1 → 8.2 → 8.3 → 8.4 → **8.5**（8.5 放最后：它要对齐的是 7.0 之后的最终形态）
> **可并行**：与批次 02、03、04、06、09 无文件交集

---

## 批次目标

让文档删除在 MySQL、ChromaDB、磁盘文件三处都真正生效，且失败可见。

> ⚠️ **本批次含一处回退风险**：任务 8.1 会给 `ChunkMapper.selectByDocumentId` 加上 `deleted = 0`，而任务 8.3 的孤儿扫描**需要看到已软删的切片**。**8.3 必须新增专用的查询方法，不得复用或修改 8.1 改过的方法**，否则会回退 8.1 的修复。

---

## 涉及文件

| 文件 | 任务 |
|---|---|
| `agent-qr-knowledge/.../mapper/ChunkMapper.java` | 8.1（**独占**）、8.3（**新增方法，不改已有方法**） |
| `agent-qr-compensation/.../service/DocumentDeleteServiceV2.java` | 8.2 |
| `agent-qr-compensation/.../scanner/OrphanVectorScanner.java` | 8.3 |
| `agent-qr-rag/.../retriever/ChromaRetriever.java` | 8.3（需新增列举能力） |
| `agent-qr-compensation/.../listener/DocumentDeleteListener.java` | 8.4 |
| `agent-qr-knowledge/.../service/FileStorageService.java` | 8.4（如需调整可见性） |
| `agent-qr-web/.../scheduler/DlqRetryScheduler.java` | **8.5**（R24：`retryEmbed` / `retryChromaWrite` / `resolveChunks` / `submitVectorizationAndWrite`） |

**不得修改**：本批次之外的任何文件。

---

## 任务 8.1 — 软删切片不应漏出（问题 27）

> 问题详情：`doc/问题清单/27-知识库软删切片漏出.md`

- [ ] **8.1.1** 给 `ChunkMapper.selectByDocumentId` 补 `deleted = 0`
  - 当前为裸 `@Select`，MyBatis-Plus 的 `@TableLogic` **对自定义 `@Select` 不生效**
  - 该查询经 `DocumentQueryService.getChunks` → `KnowledgeController` 暴露为 API
  - 后果：文档删除后，其切片仍可被查看

- [ ] **8.1.2** 排查同类问题
  - 对 `ChunkMapper` / `DocumentMapper` 中**所有**手写 `@Select` 做一次审查，确认是否都带了 `deleted = 0`
  - `@TableLogic` 的"只对自动 SQL 生效"是容易反复踩的坑

### 补充测试

- [ ] 用例：文档删除后，`GET /api/knowledge/documents/{id}/chunks` 返回空或 404（**这条用例能拦住原缺陷**）
- [ ] 用例：未删除文档的切片查询结果与修复前一致（回归）
- [ ] 用例：`ChunkMapper` 中所有手写 SQL 都含 `deleted` 条件（可用反射/扫描测试固化）

### 验收标准

- [ ] `selectByDocumentId` 含 `deleted = 0`
- [ ] 同类手写 SQL 已排查
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要修改 `ChunkMapper` 的其他方法签名（其他模块依赖）
- ❌ 不要删除 `ChunkIndexableTextProvider` 的 `LambdaQueryWrapper` 用法（它已正确过滤）

---

## 任务 8.2 — ChromaRetriever 为空时不应"假成功"（问题 31）

> 问题详情：`doc/问题清单/31-DocumentDeleteServiceV2在ChromaRetriever为空时假成功.md`
> **前置：批次 01 任务 1.1 已完成（`STATUS_FAILED` 可用）。**

- [ ] **8.2.1** 区分"依赖不可用"与"无需删除"
  - 当前 `chromaRetriever == null` 时只打 WARN，随后照常置 `DONE` —— **一条向量都没删，任务却记录为完成**
  - 而"无向量 ID"（`chromaIds` 为空）置 `DONE` 是**正确的**
  - 两者必须区分：`chromaRetriever == null` 应走**失败路径**（置 `FAILED` + DLQ 入队 + 告警）

- [ ] **8.2.2** 修正日志措辞
  - 任何"跳过"的日志都不应包含"完成"字样
  - 当前 null 分支之后仍会打印"ChromaDB 物理删除完成"

- [ ] **8.2.3** 补充可观测性（可选但建议）
  - 为"因依赖缺失而跳过"的计数增加指标，便于发现长期异常

- [ ] **8.2.4** 考虑启动期校验
  - 若 `ChromaRetriever` 是删除链路的必需依赖，建议在应用启动时做一次存在性检查并给出明确日志
  - 需权衡是否去掉 `@Autowired(required = false)`（去掉会牺牲模块解耦）

### 补充测试

- [ ] 用例：`chromaRetriever == null` 时任务状态为 `FAILED` 且入 DLQ（**这条用例能拦住原缺陷**）
- [ ] 用例：`chromaIds` 为空时任务状态为 `DONE`（这是正确的完成语义）
- [ ] 用例：删除成功时状态为 `DONE`
- [ ] 用例：删除抛异常时状态为 `FAILED`

### 验收标准

- [ ] null 依赖时走失败路径
- [ ] 日志不再出现自相矛盾的"跳过…完成"
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要移除 `@Autowired(required = false)`（除非 8.2.4 的权衡结论是移除）
- ❌ 不要在失败路径中丢失 DLQ 入队

---

## 任务 8.3 — 孤儿向量扫描（问题 29）

> 问题详情：`doc/问题清单/29-孤儿向量扫描存在结构性漏检.md`

> ⚠️ **回退风险**：本任务**不得修改** `selectByDocumentId`（任务 8.1 刚给它加了 `deleted = 0`）。孤儿扫描需要看到已软删的切片，**必须新增专用查询方法**。

- [ ] **8.3.1** 修正扫描方向（**推荐**）
  - 设计 §10.2 要求"ChromaDB → 对照 MySQL"（从向量库取全部 documentId，找出 MySQL 中已不存在的）
  - 当前实现是反向的（MySQL 未删除切片 → 对照 ChromaDB）
  - **能力已由批次 07 提供**：`ChromaRetriever` 中"从 ChromaDB 枚举 id"的能力已在**批次 07 任务 7.0.11** 实现（当时用于存量数据核对）
  - **本项只需复用**，不要重复实现——若发现该能力不满足本任务需求，先上报再扩展

- [ ] **8.3.2** 若坚持当前方向，必须放宽输入查询
  - 当前 `selectAllReadyChunks` 的 SQL 带 `deleted = 0`，而删除流程**已先把切片软删**
  - 因此在"MySQL 删成功 + Chroma 删失败"这一失败态下，切片已被排除在扫描输入之外——**永远发现不了残留向量**
  - 修复方式：**新增专用方法**（如 `selectDeletedChunksForOrphanScan`），保留原 `selectAllReadyChunks` 不变

- [ ] **8.3.3** 修正清理计数虚高
  - `ChromaRetriever.deleteByDocumentId` 与 `deleteByMetadata` 内部 `catch` 后**不重抛**、store 为 null 时直接 `return`
  - 而 `OrphanVectorScanner` **无条件 `cleaned++`** → 日志显示"清理了 N 条"，实际可能一条都没删
  - 改为：删除方法返回实际删除条数或 boolean，扫描器据实计数

- [ ] **8.3.4** 对齐调度周期
  - 当前 `fixedDelay = 300000`（5 分钟），设计 §10.1 决策 6 要求 30 分钟
  - 二选一对齐，并在设计文档中回填

### 补充测试

- [ ] 用例：构造"MySQL 已软删 + Chroma 残留"的场景，扫描器**能发现**该残留（**这条用例能拦住原缺陷**）
- [ ] 用例：无残留时不产生误删
- [ ] 用例：删除失败时 `cleaned` 计数不增加
- [ ] 用例：已软删但 Chroma 已清理的记录不被重复处理

### 验收标准

- [ ] 扫描能覆盖"MySQL 删成功 + Chroma 删失败"的场景
- [ ] `selectByDocumentId` 未被修改（任务 8.1 的修复未被回退）
- [ ] 计数反映实际清理条数
- [ ] 上述测试通过

### 禁止事项

- ❌ **不要修改 `selectByDocumentId`**（会回退任务 8.1）
- ❌ **不要修改 `selectAllReadyChunks`**（其他链路依赖其语义）
- ❌ 不要删除 `DuplicateCleanupScanner`（另一个独立功能）

---

## 任务 8.4 — 删除时清理物理文件（问题 32）

> 问题详情：`doc/问题清单/32-文档删除事件filePath无消费者导致物理文件不清理.md`

- [ ] **8.4.1** 在 `DocumentDeleteListener` 中接上文件清理
  - 事件中的 `filePath` 被发布方正常填充，但**消费方从未读取**（设计 §7.2.6 说明其用途为"清理上传文件"）
  - 读取 `event.getFilePath()` 并调用 `FileStorageService.delete(...)`
  - **注意**：`FileStorageService` 在 knowledge 模块，compensation 已单向依赖 knowledge，可行；若方法可见性不足需调整

- [ ] **8.4.2** 决定删除顺序与失败处理
  - 建议：文件删除放在 MySQL 逻辑删除之后、与 ChromaDB 删除并列
  - 失败时同样入 DLQ（复用 `DELETE` 类型 — 注意 DLQ 的 DELETE 重试体已在批次 01 修复）

- [ ] **8.4.3** 保证幂等性
  - 文件可能已被人工删除，删除方法应对"文件不存在"静默处理

- [ ] **8.4.4** 处理 `chunkIds` 字段（次要）
  - 该字段在消费方同样未被使用
  - 二选一：明确其用途，或从事件中移除（避免"事件字段越加越多但没人读"）

### 补充测试

- [ ] 用例：文档删除后，`uploads/` 下对应的物理文件被删除
- [ ] 用例：文件已不存在时删除操作不抛异常（幂等）
- [ ] 用例：文件删除失败时入 DLQ
- [ ] 用例：`filePath` 为空时不调用删除

### 验收标准

- [ ] 删除文档后物理文件确实被清理
- [ ] 幂等性成立
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要修改 `FileStorageService` 的存储路径规则（`yyyy/MM` 分目录是正确的）
- ❌ 不要删除 `DocumentCommandService` 中已废弃的 `deleteDocument`（设计 §5.2.1 要求保留）

---

## 任务 8.5 — DLQ 向量化重试体与新状态机对齐（R24）

> **来源**：批次 07 任务 7.0 的独立验证发现（2026-10-07，见 `progress.md` 风险 **R24**，已由用户确认归入本批次）
> **背景**：7.0 引入双状态机（`INDEXED`→`EMBEDDING`→`READY`）与 `removeAll`+`addAll` 幂等写入后，`DlqRetryScheduler` 的向量化重试体仍是**旧设计**，三处不一致（均经独立验证实测确认，非推测）

### 问题（三处）

- **① 状态断链**：`submitVectorizationAndWrite` 只写 `chroma_id`、**从不回写 `chunk.status`**。改造前 `kb_chunk.status` 默认值为 `READY`，重放后"看起来就绪"；7.0 把默认值改为 `INDEXED`，且全仓库唯一的 `READY` 写入点在新 Listener 的批处理路径 → **走 DLQ 恢复的切片向量已落库、状态却永久停在 `INDEXED`**，文档聚合恒为"部分就绪"（直到该文档再次产生新事件才被"顺手"修正）
- **② 非幂等写入**：重放走单条 `chromaEmbeddingStore.add(...)`，其 id 来自 `Utils.randomUUID()`（javap 字节码确认）—— **不报 `DuplicateIDError`，而是每次重放都写入新随机 id 的重复向量**，并覆盖 `chunk.chroma_id`，使 UUIDv3 确定性 id 方案在该切片上失效 → **静默孤儿向量**（会破坏任务 8.3 依赖的 id 集合一致性）
- **③ 重灌整文档**：`resolveChunks` 优先按 documentId 取 `selectByDocumentId`（**不过滤 status，含已 `READY` 的切片**）→ 一次重放会把该文档**全部**切片重新向量化一遍

### 步骤

- [ ] **8.5.1** 让重试体复用 7.0 的批处理路径（**首选**）
  - `retryEmbed` / `retryChromaWrite` 的向量化动作改为**发布 `ChunksBatchCreatedEvent`**（或直接调用 `ChunkEmbeddingBatchListener` 的处理方法），复用其"keyset 分页 + `removeAll`+`addAll` + 状态回写"的完整逻辑
  - ⚠️ 若选择发布事件，注意避免与 Listener 的 `@EventListener` 形成**环路**（DLQ 重试 → 事件 → Listener → 失败再入队）
- [ ] **8.5.2** 若保留独立实现，必须补齐三项
  - 写入前 `removeAll`（**UUID 入参**，复用 `findVectorIdsByChunkIds` / `vectorIdFor`）
  - 写入成功后**回写 `status = READY`**
  - `resolveChunks` 只取**未就绪**切片（`status <> 'READY'`）
- [ ] **8.5.3** 补测试（三条分别对应三处问题）

### 补充测试

- [ ] 用例：DLQ 重放（EMBED）后，相关切片 `status=READY` 且 `chroma_id` 为 UUIDv3 确定性值（**拦问题 ①**）
- [ ] 用例：对同一批切片**重复重放**，ChromaDB 向量 id 集合不变、无新增随机 UUID（**拦问题 ②**）
- [ ] 用例：重放**不会**重新处理已 `READY` 的切片（**拦问题 ③**）

### 验收标准

- [ ] 重放后切片状态正确流转到 `READY`
- [ ] 重放幂等（重复执行不产生重复/孤儿向量）
- [ ] 重放只处理未就绪切片
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要保留 `Utils.randomUUID()` 作为向量 id（与 7.0 的 UUIDv3 确定性方案冲突）
- ❌ 不要修改 `DocumentDeleteServiceV2` 的删除逻辑（那是 8.2 的范围）
- ❌ 不要移除 DLQ 的退避重试机制（批次 01 的成果）

---

## 批次验收

- [ ] 任务 8.1、8.2、8.3、8.4、**8.5** 全部完成
- [ ] **回退检查**：任务 8.3 未修改 `selectByDocumentId`
- [ ] 端到端验证：上传文档 → 删除文档 → 确认 MySQL 切片软删、ChromaDB 向量删除、磁盘文件删除三者一致
- [ ] 项目可编译，`mvn test` 通过
- [ ] 已更新 `progress.md`

## 回归验证建议

1. 上传一个文档，删除后通过切片接口查询，确认返回空
2. 手动在 ChromaDB 中保留向量（模拟删除失败），运行孤儿扫描，确认能发现并清理
3. 删除文档后检查 `uploads/` 目录，确认文件已清理
4. 断开 ChromaRetriever 依赖，确认任务状态为 FAILED 而非 DONE
5. **（8.5）DLQ 重放闭环**：构造一次向量化失败（如临时停掉 Ollama）→ 等待 DLQ 重放 → 确认：① 相关切片状态流转到 `READY` ② ChromaDB 无新增随机 id 的重复向量 ③ 已 `READY` 的切片未被重新处理

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-08-删除链路一致性.md 的全部任务。

严格按 8.1 → 8.2 → 8.3 → 8.4 → 8.5 顺序，不得调整。

关键约束（回退风险）：
  任务 8.1 会给 ChunkMapper.selectByDocumentId 加上 deleted = 0；
  任务 8.3 的孤儿扫描需要看到已软删的切片。
  因此 8.3 必须【新增专用查询方法】，严禁修改 selectByDocumentId 或 selectAllReadyChunks。

任务 8.5（R24，批次 07 独立验证发现，用户确认归入本批次）：
  DlqRetryScheduler 的向量化重试体与 7.0 的新方案三处不一致——
  ①重放不回写 chunk.status（切片永久停在 INDEXED）
  ②重放用随机 UUID 单条 add、无 removeAll（静默产生孤儿向量）
  ③重放取回含 READY 的整文档切片（重灌一遍）
  首选方案：让重试体复用 7.0 的批处理路径（发 ChunksBatchCreatedEvent 或直调 Listener 方法），
  注意避免 DLQ ↔ Listener 的事件环路。详见任务 8.5。

前置：批次 01 任务 1.1、批次 07 任务 7.0（含 7.0.11）必须已完成。

只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
不输出任何 API Key、Token、密码、连接串原文。
```
