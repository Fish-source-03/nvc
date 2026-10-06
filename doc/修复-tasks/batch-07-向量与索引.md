# 批次 07 · 向量化链路与索引重构

> **涵盖问题**：28（提前置 READY）、16（Collection 隔离死代码）、17（Embedding 选型未回填文档 + 单点无降级）、15（BM25Retriever 未升级）、38 部分（`routing.mode` 接线）
> **前置依赖**：**批次 05**（ETL 批量写入 MySQL）、批次 01（测试基建）
> **批次内顺序**：**严格** 7.0 → 7.1 → 7.2 → 7.3 → 7.5
> **可并行**：与批次 03、04、06、09 无文件交集

---

## 批次目标

把向量化链路重构为**事件驱动 + 双状态机**，并让 Embedding 选型与文档一致、失败语义明确且可见、索引构建不阻塞启动、语义路由开关可用。

> ⚠️ **任务 7.0 是本次修复计划中最大的单项改动**，且横跨"数据同步"与"文档上传"两条链路。它必须**作为一个原子改动完成**——拆开做会产生"发了事件但没人消费"的中间状态。

---

## 已确认的决策（直接执行，不要重新决策）

| 项 | 决策 |
|---|---|
| 状态机 | **拆成两个状态**：`INDEXED`（已入库，BM25 可检索）→ `READY`（向量已写入）。**保留 `EMBEDDING` 并插在 INDEXED 之后**，共 8 个状态 |
| 状态层次 | **Chunk 与 Document 都改**：Chunk 补 status 映射；Document 状态由切片**聚合推导** |
| 改造范围 | **两条链路都改**（文档上传 + 数据同步），统一为事件驱动 |
| 旧 Listener | **退役**：`ChunkEmbeddingListener` 职责整体迁到新 Listener |
| BM25 索引 | **发布方尽力更新 + Listener 校验补写**（双保险，需幂等） |
| 事件粒度 | **每文档 / 每数据源同步完成发一次**，事件只带标识，Listener 从 MySQL 分批读 |
| 前端 | **展示两个状态**并明确区分（需改 `StatusTag.vue` + 文档列表页） |
| 存量数据 | **一律做 id 集合核对**（不做"数据量小"的假设），按差集处理；该能力与批次 08 共用 |

---

## 涉及文件

| 文件 | 任务 |
|---|---|
| `agent-qr-common/.../event/ChunksBatchCreatedEvent.java`（新增） | 7.0 |
| `agent-qr-common/.../dlq/DlqMessage.java` | 7.0（扩展事件类型枚举） |
| `agent-qr-knowledge/.../listener/ChunkEmbeddingBatchListener.java`（新增） | 7.0 |
| `agent-qr-knowledge/.../listener/ChunkEmbeddingListener.java` | 7.0（**退役/删除**） |
| `agent-qr-knowledge/.../listener/DataSyncEtlListener.java` | 7.0（改为发事件 + 更新 BM25） |
| `agent-qr-knowledge/.../entity/Chunk.java` | 7.0（**补 status 字段映射**） |
| `agent-qr-knowledge/.../enums/DocumentStatus.java` | 7.0（新增 INDEXED） |
| `agent-qr-knowledge/.../mapper/ChunkMapper.java` | 7.0（状态查询/更新方法） |
| `agent-qr-knowledge/.../service/DocumentQueryService.java` | 7.0（Document 状态聚合推导） |
| `agent-qr-web-frontend/src/components/.../StatusTag.vue` + 文档列表页 | 7.0（展示两个状态） |
| `agent-qr-rag/.../embedding/BatchEmbeddingService.java` | 7.0、7.1 |
| `agent-qr-rag/.../retriever/ChromaRetriever.java` | 7.0（addAll + 枚举 id）、7.1 |
| `agent-qr-rag/.../embedding/EmbeddingDimensionManager.java` | 7.1 |
| `agent-qr-rag/.../config/ChromaConfig.java` | 7.1 |
| `agent-qr-rag/.../provider/ProviderFactory.java` | 7.2 |
| `agent-qr-rag/.../provider/ProviderDecisionEngine.java` | 7.2 |
| `agent-qr-rag/.../provider/ollama/OllamaEmbeddingProvider.java` | 7.2（失败语义/告警） |
| `doc/系统详细设计说明书.md` | 7.0、7.2（限定相关章节） |
| `agent-qr-rag/.../retriever/BM25Retriever.java` | 7.0（增量索引）、7.3 |
| `agent-qr-rag/.../service/ChatQueryService.java` | 7.5 |
| `agent-qr-web/src/main/resources/application-p1.yml` / `-p3.yml` | 7.1、7.2、7.5 |
| `agent-qr-web/src/main/resources/db/p2-schema.sql` | 7.0（kb_chunk.status 默认值） |

**不得修改**：本批次之外的任何文件。

---

## 任务 7.0 — 向量化链路重构（事件驱动 + 双状态机）★ 本批次最大任务

> 问题详情：`doc/问题清单/28-ChunkEmbeddingListener提前置READY.md`
> 参考方案：`doc/未来补充/大数据源同步性能改造方案.md` §2.5（事件驱动架构）、§2.6（ChromaDB addAll）、§2.7（BM25 增量）
> **前置**：批次 05 必须已完成（ETL 已改为批量写入 MySQL）

> ### ⚠️ 执行顺序（**编号与依赖顺序不一致，请务必先读这段**）
>
> ```
> 7.0a 双状态机 → 7.0b 事件驱动 → 7.0d 幂等 → 7.0c 存量迁移 → 7.0e BM25
>                                     ↑              ↑
>                                 先解决幂等      再做重跑
> ```
>
> **7.0c（存量迁移）必须在 7.0d（幂等）之后执行**，尽管编号上 d 在后。
> 原因：`ChromaEmbeddingStore` **没有 `upsert`**，对已存在的 id 执行 `add` 会报 `DuplicateIDError`——
> 存量向量已在 Chroma 中，先做重跑**必然失败**。
>
> 每段完成后请先跑该段的「补充测试」，通过后再进入下一段；不要在上一段未验证时并行推进下一段。

### 7.0a 状态机改造（双状态）

- [ ] **7.0.1** `DocumentStatus` 新增 `INDEXED` 状态
  - 插入位置：`CHUNKING` 之后、`EMBEDDING` 之前
  - 最终序列（**8 个状态**）：
    ```
    UPLOADED → PARSING → CHUNKING → INDEXED → EMBEDDING → READY
                                     ↑          ↑           ↑
                                已入库       向量化中     向量已写入
                                BM25 可检索
    （另有 FAILED、DELETING 两个终态）
    ```
  - 各状态的语义需在枚举注释中写清，尤其 `INDEXED` 与 `EMBEDDING` 的区别

- [ ] **7.0.2** `Chunk` 实体补 `status` 字段映射
  - **当前 `Chunk.java` 没有 status 字段**，而 `kb_chunk.status` 列存在（靠 DB 默认值 `'READY'`）
  - 补上 `@TableField` 映射，并明确其取值：`PENDING` / `INDEXED` / `READY`

- [ ] **7.0.3** 调整 `kb_chunk.status` 的默认值
  - 当前 `DEFAULT 'READY'`（`p2-schema.sql:152`）
  - 改造后写入时应是"已入库"= `INDEXED`，**默认值需改为 `INDEXED`**
  - **注意**：改默认值影响新写入行；存量行的处理见 7.0c

- [ ] **7.0.4** Document 状态由切片**聚合推导**
  - 推导规则：
    | 条件 | Document 状态 |
    |---|---|
    | 全部 chunk 为 `READY` | `READY` |
    | 存在 chunk 为 `INDEXED` | `INDEXED` |
    | 存在 chunk 为 `EMBEDDING`/`PENDING` | `EMBEDDING` |
  - **需明确推导的触发时机**并在报告中说明（chunk 状态变更时回调？还是查询时实时计算？）
  - 实时计算更简单且不会不一致，但每次列表查询有额外开销——**请评估后选择**

- [ ] **7.0.5** 前端展示两个状态
  - `StatusTag.vue` 增加 `INDEXED` 的映射与样式
  - 文档列表页区分展示："完全就绪（READY）"/"部分就绪，关键词可搜（INDEXED）"/"处理中"
  - **理由**：用户需要知道"为什么有些内容现在搜不到"

### 7.0b 事件驱动改造

- [ ] **7.0.6** 新增 `ChunksBatchCreatedEvent`（放在 `agent-qr-common` 的 event 包）
  - **粒度：每文档 / 每数据源同步完成发一次**
  - 事件只带**标识**，不携带 chunk 列表（Listener 从 MySQL 分批读，避免大 payload）
  - 字段建议：`documentId`（文档上传链路）或 `datasourceId` + `syncBatchId`（数据同步链路）
  - 放在 common 包的理由：两条链路都要发布/消费它

- [ ] **7.0.7** 新增 `ChunkEmbeddingBatchListener`
  - 消费 `ChunksBatchCreatedEvent`
  - 流程：从 MySQL **分批读取**该批次的 chunk → 更新 BM25 索引（校验补写）→ 向量化 → 写 ChromaDB → 回写 chunk 状态
  - **必须分批读取**（不能一次性加载全部 chunk，尤其数据同步场景可能几十万条）

- [ ] **7.0.8** 两条链路改为发布事件
  - **文档上传链路**：切片写入 MySQL 后发布事件（替代当前 `ChunkEmbeddingListener` 的直接 `submit`）
  - **数据同步链路**：`DataSyncEtlListener` 批量写入 MySQL 后发布事件（替代当前直接 `batchEmbeddingService.submit(chunk)`）
  - 发布前将 chunk 状态置为 `INDEXED`

- [ ] **7.0.9** `ChunkEmbeddingListener` **退役**
  - 删除该类，其职责整体迁到 `ChunkEmbeddingBatchListener`
  - ⚠️ **迁移时必须逐一核对不遗漏**：
    - 文档状态流转（`CHUNKING` → `EMBEDDING` → `READY`）
    - DLQ 入队（`CHROMA_WRITE` 类型）
    - 失败时回写 `FAILED`
    - `successCount` 的计数语义修正（当前统计的是"提交成功"而非"向量化成功"）
    - **元数据修正**：当前用 `doc.getFileName()` 作 `document_title`，应改为 `doc.getTitle()`

- [ ] **7.0.10** 扩展 DLQ 的事件类型枚举
  - 批次 01 任务 1.2.1 已建立枚举机制，此处按需扩展（如 `EMBED_BATCH`）
  - 同步在 `progress.md 4.2 事件契约清单` 登记

### 7.0c 存量数据核对与迁移

> ⚠️ **执行时机：必须在 7.0d（幂等）完成之后**——见任务开头的「执行顺序」说明。
>
> **决策：一律做 id 集合核对**，不做"数据量小"的假设。

- [ ] **7.0.11** 实现"从 ChromaDB 枚举 id"的能力
  - 在 `ChromaRetriever` 中补充（ChromaDB REST API 的 `get` 支持拉取 metadata）
  - **该能力将被批次 08 的孤儿向量扫描复用**（见批次 08 任务 8.3.1），实现时请一并考虑

- [x] **7.0.12** 执行 id 集合核对 —— **已于 2026-10-06 实测完成**（结果见 `progress.md` 4.4）

  | 项 | 数量 | 明细 |
  |---|---|---|
  | MySQL 有效切片 | 19 | — |
  | ChromaDB 向量 | 6 | — |
  | **两边都有**（保持 `READY`） | **6** | `11771, 11772, 11773, 11774, 11779, 11791` |
  | **仅在 MySQL**（置 `INDEXED` 重跑） | **13** | `7362, 7386-7397` |
  | **仅在 Chroma**（孤儿，归批次 08） | **0** | — |

  ⚠️ **实现时的关键细节**：向量的 id 是 **UUID**，**chunkId 存在 `embedding_metadata` 的 `chunk_id` 键**里。
  也就是说核对**不能**直接比对"向量 id ↔ chunk.id"，必须读 metadata 中的 `chunk_id`：
  ```
  SELECT string_value FROM embedding_metadata WHERE key = 'chunk_id'
  ```
  这一点在设计文档 §10.2 的描述里是缺失的（它只说 `getAllDocumentIds()`），实现时请注意。

- [ ] **7.0.13** 执行迁移
  - 按 7.0.12 的差集结果更新 `kb_chunk.status`
  - **重跑前必须先解决幂等**（见 7.0.15）

### 7.0d ChromaDB 批量写入与幂等

> ⚠️ **本段必须先于 7.0c 完成**——它是存量重跑能成功执行的前提，见任务开头的「执行顺序」说明。

- [ ] **7.0.14** 改用 `addAll()` 批量写入
  - 已确认可用：`ChromaEmbeddingStore.addAll(List<String>, List<Embedding>, List<TextSegment>)`（`langchain4j-chroma-1.16.3-beta26`）
  - 替代当前 `ChunkEmbeddingListener.java:105`、`DataSyncEtlListener.java:130` 的单条 `add(...)`

- [ ] **7.0.15** 保证写入**幂等**（**已确认的方案：每次写入前先 `removeAll` 再 `addAll`**）
  - **`ChromaEmbeddingStore` 没有 `upsert`**（已 javap 确认），只有 `add` / `addAll` / `removeAll`
  - 而 ChromaDB 对**已存在的 id 执行 add 会报 DuplicateIDError**
  - **影响范围不止本任务**：批次 01 修复后 DLQ 会真的重放向量化任务，届时同样会冲突
  - **已确认的做法**：**常规写入路径就做"先删后写"**，不区分"常规"与"重跑"
    - 理由：一劳永逸——存量重跑、DLQ 重放、失败重试全部安全
    - 新数据上 `removeAll` 是无操作，语义无害
    - 代价：每次批量写多一次 HTTP 往返（批量场景下占比小）
  - ⚠️ **注意 `removeAll` 的入参**：需传 ChromaDB 侧的向量 id（**UUID**），而非 chunkId。
    因此实现时需要先能查到"这些 chunkId 对应哪些向量 id"——依赖 7.0.11 的枚举能力（见 7.0.12 的说明）

### 7.0e BM25 增量索引（双保险）

> **决策：发布方尽力更新 + Listener 校验补写。**

- [ ] **7.0.16** `BM25Retriever` 新增批量添加方法
  - 参考方案文档 §2.7 的 `addBatchToIndex(List<Chunk>)`
  - **需幂等**：按 chunkId 判断是否已入索引，避免重复索引

- [ ] **7.0.17** 发布方尽力更新
  - ETL / 切片环节写完 MySQL 后，**立即**更新 BM25 索引（此时 `INDEXED` 成立）
  - 更新失败不阻断主流程，记录 WARN

- [ ] **7.0.18** Listener 校验补写
  - `ChunkEmbeddingBatchListener` 收到事件后，校验这批 chunk 是否已在 BM25 索引中，缺失则补写
  - 保证即使发布方更新失败，索引最终一致

### 补充测试（**本任务测试量大，请逐条覆盖**）

- [ ] 用例：chunk 写入 MySQL 后状态为 `INDEXED`；向量化完成后为 `READY`（**这条用例能拦住原缺陷**）
- [ ] 用例：全部 chunk 为 READY 时，Document 状态聚合为 `READY`；存在 INDEXED 时为 `INDEXED`
- [ ] 用例：向量化失败时 chunk 不置 `READY`，Document 不置 `READY`
- [ ] 用例：**同一批 chunk 重复向量化不报错**（幂等，**这条用例能拦住 DLQ 重放的冲突**）
- [ ] 用例：`ChunksBatchCreatedEvent` 被正确发布（两条链路各一条）
- [ ] 用例：Listener 分批读取，不会一次性加载全部 chunk
- [ ] 用例：发布方 BM25 更新失败时，Listener 能补写成功
- [ ] 用例：重复执行 BM25 索引更新不产生重复文档
- [ ] 用例：`document_title` 元数据取自 `getTitle()` 而非 `getFileName()`
- [ ] 用例：前端状态映射正确（8 个状态都有对应展示）

### 验收标准

- [ ] 8 个状态可完整流转，`INDEXED` 与 `EMBEDDING` 语义清晰
- [ ] 两条链路都走事件驱动，`ChunkEmbeddingListener` 已删除
- [ ] 存量数据已按 id 集合核对并迁移（结果记录在 `progress.md`）
- [ ] ChromaDB 写入使用 `addAll` 且**可重复执行**
- [ ] BM25 索引双保险生效
- [ ] 前端能区分"完全就绪"与"部分就绪"
- [ ] 上述测试全部通过

### 禁止事项

- ❌ **不要拆开做**（两条链路必须一起切，否则会出现"发了事件没人消费"）
- ❌ 不要在未解决幂等（7.0.15）的情况下执行存量重跑——会因重复 id 报错
- ❌ 不要在批次 05 完成前开始（ETL 侧还在逐条写入）
- ❌ 不要删除 `ChunkEmbeddingListener` 中尚未迁移的逻辑（逐条核对后再删）
- ❌ 不要一次性加载全部 chunk（数据同步场景可能几十万条）
- ❌ 不要修改 ChromaDB collection 的 `hnsw:space=cosine` 设置（`ChromaConfig` 中的距离度量是对的）

---

## 任务 7.1 — 让 Collection 隔离真正生效（问题 16）

> 问题详情：`doc/问题清单/16-EmbeddingDimensionManager为死代码导致Collection隔离不生效.md`

- [ ] **7.1.1** 接通调用链
  - `EmbeddingDimensionManager` 当前只被 `BatchEmbeddingService` 的字段注入，而唯一使用它的 `getEffectiveCollectionName()` **零调用方**
  - 让实际写入/检索路径使用该结果（涉及 `ChunkEmbeddingListener`、`DataSyncEtlListener`、`ChromaRetriever` 的 Collection 名来源统一）
  - **注意**：`ChunkEmbeddingListener` 由任务 7.4 修改，7.1 与 7.4 需协调（建议 7.1 只改 `ChromaRetriever` / `ChromaConfig` 侧，7.4 再改 Listener）

- [ ] **7.1.2** 实现真实的维度检测
  - 当前 `ensureCollection()` **恒返回 true，从不查询 ChromaDB**
  - 改为调用 `chromaClient` 查询 Collection 是否存在及其维度；不匹配时按策略处理（新建带维度后缀的 Collection / 报错 / 触发重建）

- [ ] **7.1.3** 让配置项生效
  - `application-p3.yml` 中 `agent-qr.embedding.collection-prefix` 与 `auto-dimension-check` 当前**无任何 Java 读取点**
  - 接线（用 `@Value` 读取）——这是已确认的 38 号决策「能接线就接线」
  - **补充说明**：`collection-prefix` 的注释已如实标注「暂未使用」，属**预留配置而非缺陷**，接通即可；`auto-dimension-check` 的注释声称"启动时自动检测"，属**误导性配置**，必须真正生效（依赖 7.1.2 的实现）

- [ ] **7.1.4** 处理历史数据（**已确认的策略：保持 collection 不变，按差集补写**）
  - **决策**：**不重建** `enterprise_knowledge`，只把缺失的向量补上
  - **依据（实测）**：
    - collection 配置**正确**：`dimension=2560`（与 `qwen3-embedding:4b` 一致）、`space=cosine`
    - **孤儿向量 0 条**——没有需要清理的残留
    - 待补仅 **13 条**（详见 7.0.12 / `progress.md` 4.4）
  - **因此本项与 7.0c 是同一件事**：7.0c 的差集迁移完成后，本项即达成
  - **不要重建 collection**（会丢弃现有 6 条有效向量，无必要）
  - **需要确认的边界**：本项只针对**存量**；若 7.1 的 Collection 隔离改造改变了命名规则，需保证**新旧命名能同时读到**（或迁移后再切）

### 补充测试

- [ ] 用例：不同 Embedding 模型产生不同的 Collection 名
- [ ] 用例：`collection-prefix` 配置生效
- [ ] 用例：`ensureCollection` 在 Collection 不存在时返回 false（而非恒 true）
- [ ] 用例：`auto-dimension-check = false` 时跳过维度检测

### 验收标准

- [ ] `getEffectiveCollectionName()` 有实际调用方
- [ ] `ensureCollection()` 会真实查询 ChromaDB
- [ ] 两个配置键生效
- [ ] 历史数据的处理策略已在报告中说明
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要删除 `enterprise_knowledge` 的历史数据
- ❌ 不要在未确认数据迁移方案前改变 Collection 命名规则

---

## 任务 7.2 — Embedding 选型回填与失败可见（问题 17）

> 问题详情：`doc/问题清单/17-Embedding选型未回填文档且为单点无降级.md`

> ⚠️ **背景（勿误解）**：Embedding 的选型是**本地 Ollama + `qwen3-embedding:4b`**，这是**有意为之的架构决策**，配置与代码注释均有标注。
> **DeepSeek 不提供 Embedding API**，因此本文档初版提出的"实现 `DeepSeekEmbeddingProvider`"是**不可行的方向，已废弃**。
> 本任务只处理两个衍生事项：① 设计文档未回填 ② Embedding 单点故障的失败可见性。

### 7.2a 回填设计文档（问题 39 的 B11 项）

- [ ] **7.2.1** 回填 §6.2.2：`embedding.provider` 默认值 `deepseek` → `ollama`
- [ ] **7.2.2** 重写 §6.2.4：把"DeepSeekEmbeddingProvider（DeepSeek Embedding 实现）"改为实际的选型说明
  - 记录实际方案：本地 Ollama 服务 + `qwen3-embedding:4b`
  - 说明**为何不走云端 Embedding API**（DeepSeek 不提供该能力）
- [ ] **7.2.3** 更新 §6.0 包结构与三阶段映射表 P1 列
  - `DeepSeekEmbeddingProvider` → `OllamaEmbeddingProvider`
  - 核对 `provider.ollama` 包的实际内容与文档一致
- [ ] **7.2.4** 更新 §17.9 Provider 切换决策流程中关于 Embedding 的描述

> **注意**：本任务对设计文档的修改应**限定在 Embedding 选型相关的章节**。其余文档回填统一由批次 11 处理，避免并行修改同一文件产生冲突。若批次 11 已在执行，请协调后再改。

### 7.2b 单点故障的失败可见性

- [ ] **7.2.5** 把失败语义改为「整批失败」（**已确认的决策 —— 注意这是对设计的变更，不是缺陷修复**）

  > ⚠️ **重要前提**：当前"批量失败 → 降级逐条重试"的行为是**按设计实现的**。
  > 设计 §17.8 d 步（说明书 6850-6852 行）明确写着：
  > ```
  > → 成功：分发向量到各 CompletableFuture
  > → 失败：降级为逐条 embed() 重试
  > ```
  > 所以 `retrySingle` **不是缺陷**。本任务执行的是**项目负责人已确认的设计变更**——把该语义由"降级逐条重试"改为"整批失败"。请勿把它当作 bug 修复来理解。

  - **变更理由**：`OllamaEmbeddingProvider.embedBatch`（`:67-73`）当前是**逐条循环调用 `embed`**，所以"批量失败后逐条重试"等于**把同样的失败调用再做 N 次**；在服务整体不可用时既浪费又会刷出 N 条重复错误日志
  - **变更内容**：批量失败时，把整个批次的 future 统一以异常完成（`completeExceptionally`），**移除逐条降级重试路径**（`retrySingle` 的调用点 `:121-123`、`:155-157`）
  - **需同步回填设计文档**：§17.8 d 步的"失败：降级为逐条 embed() 重试"应改为"失败：整批失败"（本任务范围内可一并改，或记入批次 11）
  - **若批次 05 未改造 `embedBatch`**（仍是循环），此变更的收益最明显；**若批次 05 改用了真批量端点**，此变更的收益变小但仍成立（重复调用依然无意义），需在报告中说明

- [ ] **7.2.5a** `vectors.size() != batch.size()` 分支的处置 —— **与批次 05 任务 5.2.5 联动，不要单独决定**

  **这个分支的含义**：`executeBatch` 后续的配对是**按索引**的（`batch.get(i).getFuture().complete(vectors.get(i))`）。若返回数量与输入不符：
  - `vectors` 较短 → 循环中途 `IndexOutOfBoundsException`，且**已在前面完成、后面的 future 永久挂起**
  - 更糟的是若改成按内容匹配 → **张冠李戴**，A 的向量写给 B，且完全静默

  所以它本质是**防静默错配的安全阀**：宁可整批失败，也不能写错。

  **当前是否可达**：**不可达**。`OllamaEmbeddingProvider.embedBatch` 是纯循环无条件 `add`——要么全成功（size 恒等），要么中途抛异常（走不到 size 检查）。

  **但批次 05 可能让它变为可达**：
  - 批次 05 任务 5.2.5 已确认 **`/api/embed` 批量端点存在且可用**（Ollama 0.35.0），是否改造**由实测结果决定**
  - **已实测的部分**：对等条件下批量端点提速 **1.5 倍**（16 线程，贴近真实配置）——收益存在但不算大
  - **若批次 05 改用真批量端点** → 该分支从死代码变为活代码，**必须保留**（真批量端点的部分失败/截断行为需防御）
  - **若批次 05 不改造** → 该分支永久不可达，可移除并统一为整批失败

  **执行要求**：
  - [ ] 先确认批次 05 任务 5.2.5 的实测结论（见 `progress.md` 产出物登记）
  - [ ] 按上表决定"保留"或"移除"，并在报告中写明依据
  - [ ] **不要**在批次 05 结论未定时自行移除或保留

- [ ] **7.2.5b** 整批失败后的下游处理（**已确认的决策：整批入一次 DLQ**）
  - **决策**：Embedding 整批失败时，**把整个批次作为一条记录入 DLQ**，由 DLQ 的退避重试机制处理
  - **理由**：
    - 批次 01 已修复 DLQ 重试体，具备真实重试能力
    - 失败可见（`dlq_message` 表有记录，运维可查）
    - 服务恢复后能自动重试，无需人工介入
    - 与项目"每个环节都有熔断降级锚点"的经验一致
  - ⚠️ **需要配套改动（跨批次）**：
    - **DLQ 的 `EMBED` 重试体需支持"整批重放"**——批次 01 任务 1.2.3 建的重试体是**按单条设计**的，需扩展
    - **事件契约需登记批量语义**——在 `progress.md 4.2 事件契约清单` 中补充
    - **DLQ 的 payload 需能承载批次标识**（而非单条 chunk 标识）
  - ⚠️ **与 7.4.4 的关系**：整批失败时**同时**回写 chunk/文档状态（参见 7.0a 的状态机）。入 DLQ 与状态回写**不冲突，两者都做**

- [ ] **7.2.6** 让单点故障可见（**本任务的核心**）
  - Embedding 服务不可用时必须有**明确的 WARN/ERROR 日志或告警**，而不是仅靠失败堆栈
  - 建议在连续失败达到阈值时输出聚合告警（避免整批失败时刷出 N 条重复日志——与 7.2.5 的改动配合）
  - 已确认的处置是"整批失败 + 失败可见"，**不是引入熔断器**；但"可见"这条是硬要求

- [ ] **7.2.7**（不实施，仅记录）评估是否需要引入第二来源
  - 已确认**不引入**第二个 Embedding 来源（本地模型路线的固有取舍）
  - 本项仅需在设计文档中**记录该单点风险**（作为已知取舍），不需要实现
  - 若后续有高可用需求，再另行决策

### 7.2c 消除配置冗余（归属问题 38）

- [ ] **7.2.8** 处理两个语义重叠的配置项（**已确认：保留生效的那个，删除不生效的**）
  - `embedding.provider`（`application-p1.yml:15`）—— 被 `ProviderFactory.java:29` 读取，**实际生效** → **保留**
  - `agent-qr.provider.preferred-embedding`（`application-p3.yml:49`）—— 有 getter `getPreferredEmbedding()`（`ProviderDecisionEngine.java:122`），但**不参与** `decideEmbeddingProvider()` → **删除**
  - **理由**：保留生效的那个，避免留一个"改了没反应"的键（这正是问题 38 要消除的误导模式）
  - 若 `getPreferredEmbedding()` 因此失去调用方，一并清理

### 补充测试

- [ ] 用例：`ollama.embedding.model` 配置生效（修改后模型名随之变化）
- [ ] 用例：**批量失败时整批 future 均以异常完成，且不再逐条调用 `embed`**（可用 Mock 验证 `embed` 的调用次数——**这条用例能拦住原缺陷**）
- [ ] 用例：Embedding 服务不可用时产生明确的失败日志/告警
- [ ] 用例：连续失败达阈值时输出聚合告警（若 7.2.6 采用聚合策略）
- [ ] 用例：`embedding.provider` 与 `preferred-embedding` 的关系符合 7.2.8 的决策

### 验收标准

- [ ] §6.2.2 / §6.2.4 / §6.0 / 映射表 已回填为实际选型
- [ ] **批量失败时整批失败，无逐条降级重试**（数量不匹配分支的处置已在报告中说明）
- [ ] 整批失败后的下游处理已与批次 01 的 DLQ 对齐并记录
- [ ] Embedding 失败有明确的可观测信号
- [ ] 配置冗余已消除或已在注释中说明分工
- [ ] 上述测试通过

### 禁止事项

- ❌ **不要实现 `DeepSeekEmbeddingProvider`** —— DeepSeek 不提供 Embedding API，该方向已废弃
- ❌ 不要把 `decideEmbeddingProvider()` 的硬编码当作缺陷去"修复成"通用逻辑——代码 javadoc 已说明"仅 Ollama 可用"，这是有意设计
- ❌ 不要修改 `qwen3-embedding:4b` 的模型选型（除非有明确的新决策）
- ❌ 不要删除 `OllamaEmbeddingProvider`（它是当前唯一实现）
- ❌ **不要实现 LLM 那样的熔断器** —— 已确认的决策是"整批失败 + 失败可见"，不是引入熔断（若要加熔断需另行决策）
- ❌ 不要让整批失败静默 —— 即使整批失败，也必须有明确日志/告警（7.2.6）
- ❌ 不要在本任务中改动批次 11 负责的其他文档章节

---

## 任务 7.3 — BM25Retriever v2（问题 15）

> 问题详情：`doc/问题清单/15-BM25Retriever未升级为磁盘索引与异步构建.md`
> **依赖任务 7.0 完成**（增量索引的触发链已在 7.0e 建立）。

- [ ] **7.3.1** 索引持久化
  - 当前使用 `ByteBuffersDirectory`（堆内存），每次启动全量重建
  - 改为 `FSDirectory` 指向磁盘目录；索引已存在时直接加载而非重建

- [ ] **7.3.2** 异步构建
  - 当前 `@PostConstruct` **同步阻塞**启动
  - 移到 `@Async("indexBuilderExecutor")`——该线程池**已存在但无消费者**（`AsyncConfigV2.java:95`），正是为此预留
  - 增加构建状态标志：构建完成前 BM25 路返回空而非阻塞

- [ ] **7.3.3** 分页加载
  - 当前 `findAllIndexable()` 一次性全量加载
  - 改为分页/游标形式

- [ ] **7.3.4** 与 7.0e 的衔接（**本项已被 7.0 覆盖，此处只做核对**）
  - 增量索引入口（`addBatchToIndex`）与双保险触发链已在**任务 7.0e**（7.0.16 - 7.0.18）实现
  - 本项只需确认：磁盘索引改造后，7.0e 的增量入口仍正常工作（**回归验证**）
  - **不要重复实现增量逻辑**

- [ ] **7.3.5** 构建失败应可见
  - 当前 `catch` 仅 `log.error`，失败后 BM25 路**静默失效**，混合检索退化为单路
  - 失败时应置降级标志并在健康检查/日志中暴露

- [ ] **7.3.6** 类名对齐（可选）
  - 设计称 v2 类为 `BM25RetrieverV2`，实际仍为 `BM25Retriever`
  - 二选一：改名或回填文档

### 补充测试

- [ ] 用例：索引已存在时启动不重建（可用文件 mtime 或 mock 验证）
- [ ] 用例：构建期间 BM25 检索返回空但不抛异常
- [ ] 用例：**磁盘索引改造后，7.0e 的增量添加仍生效**（回归）
- [ ] 用例：构建失败时降级标志被置位

### 验收标准

- [ ] 使用磁盘索引
- [ ] 构建异步、不阻塞启动
- [ ] 7.0e 的增量索引入口在磁盘索引下仍工作
- [ ] 构建失败可见
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要使用 `indexBuilderExecutor` 之外的线程池（该池已为此预留）
- ❌ 不要移除 `SmartChineseAnalyzer`（中文分词能力需保留）
- ❌ **不要重复实现增量索引逻辑**（已在 7.0e 完成）

---

## 任务 7.4 — （已并入任务 7.0）

> 原 7.4「向量化完成后再置 READY（问题 28）」的内容**已全部并入任务 7.0**：
>
> | 原任务项 | 现位置 |
> |---|---|
> | 7.4.1 改为"完成后置 READY" | 7.0a（双状态机）+ 7.0c（回写状态） |
> | 7.4.2 修正计数语义 | 7.0.9（迁移清单中已列出） |
> | 7.4.3 修正元数据（getFileName → getTitle） | 7.0.9（迁移清单中已列出） |
> | 7.4.4 失败时回写状态 | 7.0a / 7.0c + 与 7.2.5 的整批失败语义联动 |
>
> **任务编号 7.4 保留占位**，避免与 `progress.md` 中既有引用错位。

---

## 任务 7.5 — 语义路由开关接线（问题 38 的一部分）

> 问题详情：`doc/问题清单/38-配置项与代码实现脱节.md`

- [ ] **7.5.1** 让 `agent-qr.routing.mode` 生效
  - 当前 `application-p3.yml:41-44` 声明了该键（注释还写了 `keyword | semantic | auto`），但**无任何 Java 读取它**
  - 实际路由选择硬编码在 `ChatQueryService.java:328-351`（V2 优先 → V1 → 全局）
  - 改为按配置值选择路由方式

- [ ] **7.5.2** 补充路由模式的日志
  - 启动时输出当前生效的路由模式，便于运维确认配置是否生效

### 补充测试

- [ ] 用例：`routing.mode=keyword` 时使用 V1 关键词路由
- [ ] 用例：`routing.mode=semantic` 时使用 V2 语义路由
- [ ] 用例：`routing.mode=auto` 时按 V2 优先 → V1 降级的顺序选择

### 验收标准

- [ ] 配置键被实际读取
- [ ] 三种模式行为符合预期
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要删除 `DomainRouterV2` 的降级链（V2 不可用时回退 V1 是必要的，`auto` 模式应保留该行为）
- ❌ 不要修改 `catalog` 模块中的同名类（那是域描述生成，职责不同）

---

## 批次验收

- [ ] 任务 7.0、7.1、7.2、7.3、7.5 全部完成（7.4 已并入 7.0）
- [ ] **7.0 作为原子改动完成**：两条链路都已切换为事件驱动，`ChunkEmbeddingListener` 已删除
- [ ] 存量数据核对结果已记录在 `progress.md`（数量 + 差集明细）
- [ ] ChromaDB 写入**可重复执行**（幂等，7.0.15）
- [ ] 项目可编译，`mvn test` 通过
- [ ] 已更新 `progress.md`

## 回归验证建议

1. **状态机**：上传一个文档，观察状态流转 `CHUNKING → INDEXED → EMBEDDING → READY`；在 `INDEXED` 阶段做一次关键词检索，确认 BM25 能命中
2. **两状态展示**：确认前端在 `INDEXED` 阶段显示"部分就绪"而非"就绪"或"处理中"
3. **事件驱动**：确认两条链路（文档上传 + 数据源同步）都通过 `ChunksBatchCreatedEvent` 触发向量化
4. **幂等**：对同一批 chunk 重复触发向量化，确认不报 DuplicateIDError
5. **BM25 双保险**：人为让发布方的索引更新失败，确认 Listener 能补写成功
6. **存量迁移**：核对 `kb_chunk.status` 与 ChromaDB 中的实际向量是否一致
7. 重启应用，确认 BM25 索引不阻塞启动（启动日志中索引构建为异步）
8. 切换 `routing.mode` 配置，确认路由方式随之变化

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-07-向量与索引.md 的全部任务。

严格按 7.0 → 7.1 → 7.2 → 7.3 → 7.5 顺序，不得调整（7.4 已并入 7.0）。
关键：
  7.0 是本批次最大任务，且必须【作为一个原子改动完成】——两条链路一起切，
      拆开会产生"发了事件但没人消费"的中间状态。
  7.1（Collection 隔离）必须先于 7.2，否则维度冲突。

【7.0 内部的执行顺序（注意：编号与依赖顺序不一致）】
  7.0a → 7.0b → 7.0d → 7.0c → 7.0e
                     ↑      ↑
                 先解决幂等  再做重跑
  原因：ChromaEmbeddingStore 无 upsert，重复 id 会报 DuplicateIDError。
        不先解决幂等就做存量重跑，一定失败。
  每段完成后跑该段的补充测试，通过后再进下一段。

任务 7.0 的三个必须注意的点：
  1. 幂等（7.0.15）：ChromaEmbeddingStore 无 upsert，重复 id 会报错。
     不解决幂等就做存量重跑，一定失败。
  2. 存量核对（7.0.11-7.0.13）：一律做 id 集合核对，不要用"数据量小"做假设。
     核对所需的能力与批次 08 共用，实现时一并考虑。
  3. 旧 Listener 退役（7.0.9）：删除前逐条核对迁移清单，不要漏掉 DLQ 入队与元数据修正。

任务 7.2 的背景：Embedding 选型（本地 Ollama + qwen3-embedding:4b）是有意为之，
DeepSeek 不提供 Embedding API —— 不要实现 DeepSeekEmbeddingProvider，该方向已废弃。

任务 7.2.5 的失败语义已确认：整批失败，不做逐条降级重试。
注意：当前的"降级逐条重试"是【按设计实现】的（设计 §17.8 明确要求），
本次是【设计变更】而非缺陷修复 —— 请连同设计文档一起改，不要只当 bug 删掉。
不要实现熔断器 —— 那不是本次的决策。

任务 7.2.5a 需先确认批次 05 任务 5.2.5 的实测结论，不要在结论未定时自行决定 size 分支的去留。

只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
不输出任何 API Key、Token、密码、连接串原文。
```
