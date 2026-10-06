# 批次 05 · 数据源同步链路修复

> **涵盖问题**：24（增量同步不完整）、23（连接器异常被吞）、21（大数据源同步性能，复盘偏差 4）、22（SyncScheduler 死代码）
> **前置依赖**：批次 01（测试基建 + 01/02 的兜底链路）
> **批次内顺序**：**严格** 5.1 → 5.2 → 5.3
> **可并行**：与批次 03、04、06、08、09 无文件交集

---

## 批次目标

让数据同步链路"能定时、失败可见、增量有效"，并完成 **MySQL 侧的写入性能优化**。

> **范围说明（本批次已收窄）**：原属于本批次的"事件驱动解耦向量化"与"ChromaDB 批量写入"**已移入批次 07 任务 7.0**。
> 原因：那两项会重写 `ChunkEmbeddingListener` 与 `DataSyncEtlListener` 的向量化调用点，与文档上传链路的重构是同一件事，拆开做会产生中间状态。
> 因此**方案文档承诺的"总耗时 6-8 分钟"需要批次 05 + 批次 07 都完成后才能验证**。

> **顺序说明（与直觉相反，请注意）**：
> - 5.1（连接器）必须最先——否则 5.3 开启定时调度后，"增量只支持单表"会从"不触发"变成**周期性静默发生**，比原来更难发现。
> - 5.2（性能）必须先于 5.3——否则每次同步耗时 50 分钟，调度会持续处于"上一轮没跑完"的状态。
> - 5.3（调度）最后做，且**必须包含并发控制**。

---

## 涉及文件

| 文件 | 任务 |
|---|---|
| `agent-qr-datasource/.../connector/JdbcConnector.java` | 5.1、5.2（**同一文件，合并修改**） |
| `agent-qr-datasource/.../connector/RestApiConnector.java` | 5.1 |
| `agent-qr-datasource/.../dto/SyncResult.java` | 5.1 |
| `agent-qr-datasource/.../entity/DataSourceConfig.java` | 5.3 |
| `agent-qr-web/src/main/resources/db/p2-schema.sql` | 5.3（新增调度字段） |
| `agent-qr-datasource/.../scheduler/SyncScheduler.java` | 5.3 |
| `agent-qr-datasource/.../service/DataSourceService.java` | 5.3（消除重复实现） |
| `agent-qr-knowledge/.../listener/DataSyncEtlListener.java` | 5.2（**仅批量写入 MySQL**；submit→事件的改动在批次 07 任务 7.0） |
| `agent-qr-knowledge/.../mapper/ChunkMapper.java` | 5.2（批量 INSERT） |
| `agent-qr-rag/.../embedding/BatchEmbeddingService.java` | 5.2（队列容量参数化） |
| `agent-qr-rag/.../provider/ollama/OllamaEmbeddingProvider.java` | 5.2（批量端点） |
| `agent-qr-web/src/main/resources/application-p2.yml` | 5.2、5.3 |

> **已移出本批次的文件**（属批次 07 任务 7.0）：
> - `agent-qr-rag/.../retriever/ChromaRetriever.java`（批量写入 + 枚举 id）
> - `agent-qr-knowledge/.../listener/ChunkEmbeddingListener.java`（退役）
> - `agent-qr-knowledge/.../listener/DataSyncEtlListener.java` 的 **submit→事件部分**（批量写入部分仍在本批次）

**不得修改**：本批次之外的任何文件。

---

## 任务 5.1 — 连接器：异常语义与增量完整性（问题 24 + 23）

> 问题详情：`doc/问题清单/24-增量同步能力不完整.md`、`doc/问题清单/23-数据源连接器异常被吞导致失败被记为成功.md`
> **两个问题同改 `JdbcConnector`，必须一次改完。**

### 5.1a 异常语义（问题 23）

- [ ] **5.1.1** 让连接器向上传递失败，而不是吞掉异常
  - 当前三个连接器的 `catch (Exception)` 一律只 `log.error` 然后返回"部分/空结果"
  - 调用方据此写 `sync_record=SUCCESS`，导致**失败被记为成功**
  - **建议方案**：给 `SyncResult` 增加 `success` / `errorMessage` 字段（保留部分结果的场景），而非直接抛异常
  - 设计 §8.7.2 原文要求抛 `BusinessException`，若选抛异常方案，需同时处理"部分成功"的表达

- [ ] **5.1.2** 调用方根据失败标志写入正确的同步状态
  - `SyncScheduler` 与 `DataSourceService` 需依据 `SyncResult.success` 写 `sync_record` 状态

- [ ] **5.1.3** 日志脱敏
  - `JdbcConnector` 把完整 JDBC URL 写入日志，URL 可能内嵌凭据
  - 改为脱敏输出（仅保留 host/port/db）

### 5.1b 增量完整性（问题 24）

- [ ] **5.1.4** JDBC 增量支持多表
  - 当前 `incrementalSync` 只读 `config.get("tableName")`（单表），而 `fullSync` 遍历 `tableNames`
  - 后果：多表数据源中，非 `tableName` 指定的表在首同步后**永久不再更新**
  - 改为与 `fullSync` 对齐，遍历全部表，每表各自维护游标

- [ ] **5.1.5** REST 增量补翻页循环
  - 当前增量只发**一次** GET，无翻页；`fullSync` 有 `X-Next-Cursor` 循环
  - 复用 `fullSync` 的翻页逻辑

- [ ] **5.1.6** REST 全量返回真实游标
  - 当前全量固定返回 `nextCursor = null`，导致下次仍走全量，增量路径实际不可达
  - 全量完成后应记录最后一次的分页游标供下次增量使用

- [ ] **5.1.7** `maxPages` 上限可配置 + 告警
  - 当前 `RestApiConnector.java:66` 硬编码 `maxPages = 100`，命中上限时**静默截断**
  - 改为可配置，且命中上限时记录 WARN 并在同步记录中标记"结果被截断"

- [ ] **5.1.8** 定义 `connectionConfig` 的 schema
  - 当前键名（`tableName`/`tableNames`/`cursorField`/`bucketName`）靠约定，无校验
  - 建议用 DTO + `@Valid` 替代裸 `Map<String,Object>`

### 补充测试

- [ ] 用例：连接器失败时，`SyncResult.success == false` 且 `errorMessage` 非空
- [ ] 用例：调用方在失败时写入 `sync_record.status = FAILED`
- [ ] 用例：JDBC 多表增量对所有表都生成游标条件
- [ ] 用例：REST 增量在存在 `X-Next-Cursor` 时继续翻页
- [ ] 用例：REST 全量返回非 null 游标（当上游提供时）
- [ ] 用例：命中 `maxPages` 上限时有告警标记

### 验收标准

- [ ] 同步失败不再被记为成功
- [ ] JDBC 增量覆盖全部表
- [ ] REST 增量可翻页，且全量后能进入增量模式
- [ ] 日志不含完整 JDBC URL
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要在 `SyncResult` 中删除已有的 `records` / `nextCursor` 字段（下游依赖）
- ❌ 不要改变 `S3Connector` 的增量逻辑（它按 `lastModified` 过滤，**实现优于设计**，是正确的）

---

## 任务 5.2 — 同步性能改造（问题 21，复盘偏差 4）

> 问题详情：`doc/问题清单/21-大数据源同步性能四项瓶颈未改造.md`
> 参考方案：`doc/未来补充/大数据源同步性能改造方案.md`（已给出完整设计与压测方案）

### 四项瓶颈（方案文档 §二）

- [ ] **5.2.1** ① JDBC 连接器改流式读取
  - 当前 `JdbcConnector.java:110,118-124` 全量 `SELECT *` 装入 `ArrayList`
  - 改为 `setFetchSize` + 逐批消费（全仓库当前 `fetchSize` 零命中）

- [ ] **5.2.2** ② ETL 批量写入 MySQL
  - 当前 `DataSyncEtlListener.java:116` 逐条 `chunkMapper.insert(chunk)`（60 万次 SQL 往返）
  - 另有 `:224` 逐字段 `chunkStructuredMapper.insert(cs)`，比复盘描述更放大
  - 改为批量写入（1000 条/批），需在 `ChunkMapper` 新增批量 INSERT 方法

- [ ] **5.2.3** ③ 向量化队列容量参数化
  - 当前 `BatchEmbeddingService.java:46` **硬编码** `new LinkedBlockingQueue<>(2000)`
  - **先参数化**（改为 `@Value` 读取），再按方案文档调整为 10000
  - **注意（范围变更）**：方案文档 §2.5 的"非阻塞降级"部分**已并入批次 07 任务 7.0**（事件驱动改造后，ETL 不再直接 `submit`，"阻塞生产者"的问题已从根本上消除）。本项只做**容量参数化**。
  - 队满时的处理策略由批次 07 的 7.0 决定

- [ ] **5.2.4** ④ ChromaDB 批量写入 —— **已移入批次 07 任务 7.0d**
  - 原位置：`ChunkEmbeddingListener.java:105`、`DataSyncEtlListener.java:130` 的单条 `add(...)`
  - **移动原因**：这两个调用点都会在批次 07 任务 7.0 的事件驱动改造中被重写，在同一处一次改完更安全
  - `addAll` 的可用性**已确认**：`ChromaEmbeddingStore.addAll(List<String>, List<Embedding>, List<TextSegment>)` 存在（`langchain4j-chroma-1.16.3-beta26`）
  - **本项在批次 05 中不执行**

- [ ] **5.2.5** ⑤ 可选优化：`OllamaEmbeddingProvider.embedBatch` 是否改用批量端点（**方案文档未覆盖；实测驱动，先测后改**）

  **背景（已实测确认，2026-10-06）**：

  | 项 | 实测结果 |
  |---|---|
  | Ollama 版本 | 0.35.0 |
  | 旧端点 `/api/embeddings`（当前使用） | ✅ 仍可用，返回 `{"embedding":[...]}` |
  | 新端点 `/api/embed`（批量） | ✅ 可用，接受 `input` 数组，返回 `{"embeddings":[[...],[...]]}` |
  | 向量维度 | 2560 |

  **注意（两条依据，方向一致）**：
  - P2 的过程文档写「Ollama 原生不支持批量」（`doc/p2-tasks/agent-qr-rag.md:45`），所以循环调用**在当时是有意为之**，不是缺陷
  - 而**设计文档 §17.8 的意图恰好相反**：它称"调用 `embedBatch(texts)` **一次处理整批**"，复杂度为"**O(N/B) 次 API 调用**"——即**设计本就期望真批量**
  - 两者叠加的结论：**改用批量端点是"对齐 §17.8 的设计意图"**，不是新增优化；但**收益仍需实测**（§17.8 的复杂度论断未考虑并发已存在）
  - **旧端点未被移除，不改也不会坏**——兼容性无忧，纯性能取向

  **当前实现的实际开销分析**：

  - `embedBatch`（`:67-73`）是逐条循环调 `embed` → 每批 32 条 = **32 次 HTTP 请求**（用批量端点则为 1 次）
  - **但并发已经存在**：`BatchEmbeddingService.consumerCount = Runtime.getRuntime().availableProcessors()`（`:64,71-78`），即 N 核并发消费——**不是串行排队**
  - 所以"逐条"的代价被并发抵消了一部分，实际收益需实测

  **📊 实测数据（已于 2026-10-06 完成，对等条件对比）**：

  | 实验 | 并发度 | A. 当前实现（逐条） | B. 批量端点 | 提速 |
  |---|---|---|---|---|
  | 第一次 | 8 线程 × 8 条 | 18.2 s（64 请求） | 6.6 s（8 请求） | **2.7 倍** |
  | 第二次 | **16 线程 × 16 条**（贴近真实） | 36.6 s（256 请求） | 24.7 s（16 请求） | **1.5 倍** |
  | 参考（非对等） | 单线程 | 72.0 s | 6.1 s | 11.7 倍 |

  > ⚠️ **结论对并发度高度敏感**：8 线程 2.7 倍、16 线程降到 1.5 倍。
  > 原因是 Ollama **能并行处理并发请求**（实测：8 并发下单条均摊从 2173ms 降到 334ms，约 6.5 倍），
  > 而当前实现已经是 `consumerCount = CPU 核数` 的并发消费——**并发已经拿回了一部分吞吐**。
  > **单线程下的 11.7 倍是不可用的参考值**，不要据此决策。

  **按 1.5 倍推算的端到端收益**（取决于向量化占比）：

  | 向量化占总同步耗时 | 改造后总耗时 | 端到端收益 |
  |---|---|---|
  | 20% | 0.933 | 省 6.7% |
  | 40% | 0.867 | 省 13.3% |
  | 60% | 0.800 | 省 20% |

  **决策方式（先测后改，不要预设）**：

  - [ ] **5.2.5.1** 实测：在 5.2.1-5.2.3 完成后，测量**向量化环节占总同步耗时的比例**（本项仍需实测——②③ 未完成时，JDBC 全量装载与逐条 INSERT 会掩盖真实占比）
  - [ ] **5.2.5.2** 判断（**判据维持原样**：占比 >40% 才改）：
    - 占比高（>40%）→ 改造 `embedBatch` 改用 `/api/embed`（可省总耗时 13%~20%）
    - 占比低 → **不改造**，在报告中记录实测数据与结论即可
    - **注意**：原判据经实测验证是合理的，不需要放宽——1.5 倍的收益不足以支撑"无论占比多少都改"
  - [ ] **5.2.5.3** 若改造，必须在报告中说明**是否观察到"返回数量与输入不一致"**的情况
    - 真批量端点可能部分失败/截断，这与当前循环实现的行为不同
    - 该结论将决定批次 07 任务 7.2.5a 中 size 检查分支的去留

  **未覆盖的因素（如实记录）**：
  - 本地 Ollama 的 HTTP/网络开销几乎为零，所以"256 次请求 vs 16 次请求"的差距**未能体现**
  - **若 Ollama 部署在远程**，批量化的收益会明显大于本次实测

  **禁止事项**：
  - ❌ 不要因为"批量端点存在"就默认改造 —— 收益需实测
  - ❌ 不要移除 `OllamaEmbeddingProvider.embed(String)` 单条方法（降级路径与 7.2.5 的失败语义可能依赖它）

### 5.2.6 事件契约 —— **已移至批次 07 任务 7.0b**

- 方案文档 §5.1 提出的 `ChunksBatchCreatedEvent` **由批次 07 任务 7.0b（7.0.6）定义与发布**
- **移动原因**：该事件的两条发布链路（文档上传 + 数据同步）与唯一的消费方（`ChunkEmbeddingBatchListener`）都在批次 07，放在一起定义更内聚
- **本批次只需**：`DataSyncEtlListener` 在批量写入 MySQL 后**预留发布点**（先不接事件，由批次 07 接入）
  - 或若批次 07 紧随其后执行，可直接一并实现——**请与批次 07 协调，避免两处各写一份**

### 补充测试

- [ ] 用例：JDBC 流式读取不将全部结果装入内存（可用小 `fetchSize` + 大数据集验证）
- [ ] 用例：批量 INSERT 的 SQL 与参数数量正确
- [ ] 用例：队列容量从配置读取（修改配置后生效）
- [ ] 性能验证（复盘经验 3 要求）：构造 10000 条数据做一次同步，记录耗时

### 验收标准

- [ ] ①（JDBC 流式）与 ②（ETL 批量写入）改造完成
- [ ] ③ 队列容量不再是硬编码
- [ ] 10000 条数据的同步耗时较改造前有改善（需给出实测数据）
- [ ] 上述测试通过

> **注意**：方案文档承诺的"总耗时 6-8 分钟 + 同步完成即可检索"需要 **批次 05 的 ①② + 批次 07 的 7.0** 都完成才能验证。本批次只能验证 MySQL 侧的改善。

### 禁止事项

- ❌ 不要跳过 5.1 直接做本任务（`JdbcConnector` 同文件，且异常语义会影响流式改造的失败处理）
- ❌ 不要把批量大小设为无上限（方案文档建议 1000 条/批）
- ❌ 不要移除 DLQ 入队逻辑（批量失败时的降级入口需保留）

---

## 任务 5.3 — 定时同步（问题 22）

> 问题详情：`doc/问题清单/22-SyncScheduler为死代码导致定时同步链路缺失.md`

- [ ] **5.3.1** 扩展 `DataSourceConfig` 与建表脚本
  - 当前实体与 `data_source_config` 表**均无** cron / 调度周期 / 下次执行时间字段
  - 新增 `syncCron` / `syncEnabled` 字段，同步修改 `db/p2-schema.sql`

- [ ] **5.3.2** 实现调度能力
  - 用 `TaskScheduler` 动态注册 Cron 任务（设计 §8.7.5 要求）
  - 应用启动时按数据库配置批量注册

- [ ] **5.3.3** 补状态校验与并发控制
  - 当前只判 `config == null`，需增加 `status != ACTIVE` 校验
  - **必须实现同一数据源的单飞锁**（防止上一轮未结束就触发下一轮）

- [ ] **5.3.4** 消除重复实现
  - `SyncScheduler.scheduleSync` 与 `DataSourceService.triggerSync` 逻辑约 90% 重复
  - 令前者调用后者（或反向），只保留一份同步逻辑

- [ ] **5.3.5** 前端入口（可选）
  - 若需支持配置调度周期，在数据源管理页增加对应字段（当前 UI 无此能力）
  - 若不做，需在报告中说明

### 补充测试

- [ ] 用例：`status != ACTIVE` 的数据源不被调度
- [ ] 用例：同一数据源的并发触发被拒绝（单飞锁生效）
- [ ] 用例：Cron 表达式变更后任务被重新注册
- [ ] 用例：`syncEnabled = false` 时任务不注册

### 验收标准

- [ ] 定时同步可实际触发（验证方式：设一个短周期 Cron，观察 `sync_record` 新增记录）
- [ ] 并发控制生效
- [ ] 状态校验生效
- [ ] 无重复的同步逻辑
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要修改批次 01 已修复的 `@EnableScheduling` 与 DLQ 逻辑
- ❌ 不要在本任务中再次调整同步性能（那是 5.2）
- ❌ 若 5.2 未完成，**不要开启定时调度**（50 分钟一轮会造成任务堆积）

---

## 批次验收

- [ ] 任务 5.1、5.2、5.3 全部完成
- [ ] 端到端验证：配置一个数据源 → 全量同步 → 增量同步 → 定时触发，全流程正常
- [ ] MySQL 侧的性能改善已实测（给出改造前后对比数据）
- [ ] `DataSyncEtlListener` 的批量写入已完成，**向量化调用点保持原样待批次 07 改造**
- [ ] 项目可编译，`mvn test` 通过
- [ ] 已更新 `progress.md`

## 回归验证建议

1. 断开数据源连接，触发同步，确认 `sync_record` 记录为 FAILED（而非 SUCCESS）
2. 配置一个多表 JDBC 数据源，执行全量+增量，确认所有表都更新
3. 构造 10000 条数据做全链路同步，对比改造前后耗时
4. 重复触发同一数据源的同步，确认被单飞锁拒绝

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-05-数据源同步.md 的全部任务。

严格按 5.1 → 5.2 → 5.3 顺序，不得调整。
关键：
  5.1（连接器）必须先于 5.3，否则定时调度会让"增量只支持单表"变成周期性静默发生。
  5.2（性能）必须先于 5.3，否则每轮同步 50 分钟会造成调度任务堆积。
  任务 5.1 与 5.2 都改 JdbcConnector，请注意不要互相覆盖。

本批次【已收窄】：事件驱动解耦向量化与 ChromaDB 批量写入已移入批次 07 任务 7.0。
本批次只做 MySQL 侧的写入优化（JDBC 流式 + ETL 批量 INSERT）+ 队列容量参数化。
不要把 DataSyncEtlListener 的"submit → 发事件"改造做了 —— 那是批次 07 的范围。

参考方案文档：doc/未来补充/大数据源同步性能改造方案.md

只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
不输出任何 API Key、Token、密码、连接串原文（JDBC URL 需脱敏）。
```
