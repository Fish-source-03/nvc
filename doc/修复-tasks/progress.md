# 修复进度记录

> **用途**：主 agent 记录修复进度、验收证据、遗留问题；子 agent 完成后由主 agent 更新
> **创建日期**：2026-10-06
> **最后更新**：2026-10-07（批次 03 完成，经独立测试子 agent 验证）
> **配套文件**：`README.md`（执行规则）、`batch-01` ~ `batch-11`（任务指令）
> **问题详情**：`doc/问题清单/`（43 份）

---

## 一、状态标记约定

| 标记 | 含义 |
|---|---|
| ⬜ | 未开始 |
| 🔄 | 进行中 |
| ✅ | 已完成并通过验收 |
| ⚠️ | 部分完成 / 有条件通过 |
| ⛔ | 阻塞（需上报或等待前置） |
| ➖ | 有意不修（需在"有意不修项"中说明） |

---

## 二、批次整体状态

| # | 批次 | 涵盖问题 | 任务数 | 状态 | 开始 | 完成 | 备注 |
|---|------|---------|--------|------|------|------|------|
| 01 | 兜底链路（★最先） | 01, 02, 30, 37(基建) | 4 | ✅ | 2026-10-06 | 2026-10-06 | 含测试基础设施 |
| 02 | 部署链路 | 03, 04, 05, **R8** | 4 | ✅ | 2026-10-06 | 2026-10-06 | 含高危顺序陷阱（已遵守）；R8 已修复并**两次重建实测**持久化生效 |
| 03 | 权限链路 | 41, 07, 06, 08, 09, 33(部分), 39-B2 | 6 | ✅ | 2026-10-06 | 2026-10-07 | 含硬约束 3（已遵守）；**独立测试子 agent 验证通过**（含证伪核验） |
| 04 | 检索过滤 | 20, 19, 12, 13 | 4 | ⬜ | | | 灰度开关默认关闭 |
| 05 | 数据源同步 | 24, 23, 22, 21(①②③) | 3 | ⬜ | | | **已收窄**为 MySQL 侧优化 |
| 06 | 文档解析 | 10, 11 | 2 | ⬜ | | | 同文件合并改 |
| 07 | 向量化链路与索引重构 | 28, 16, 17, 15, 38(部分), 21(④) | 5 | ⬜ | | | **最大单项**：7.0 事件驱动+双状态机 |
| 08 | 删除链路一致性 | 27, 31, 29, 32 | 4 | ⬜ | | | 含回退风险；复用 7.0.11 |
| 09 | 独立修复 | 18, 25, 33(断裂1), 36, 40, 42, 43 | 7 | ⬜ | | | 无顺序要求 |
| 10 | 决策类修复 | 14, 26, 34, 35, 38(剩余) | 5 | ⬜ | | | 工作量最大 |
| 11 | 文档回填与收尾 | 39, 37(收尾) | 2 | ⬜ | | | 最后执行 |

**总览统计**

| 指标 | 数值 |
|------|------|
| 批次总数 | 11 |
| 任务总数 | 46（任务级；批次 07 的任务 7.0 内含 18 个子项；批次 02 新增任务 2.4 = R8；批次 03 新增任务 3.6 = 问题 39-B2） |
| 已完成 | 3（批次 01、02、03） |
| 进行中 | 0 |
| 阻塞 | 0 |
| 完成率 | 27%（3/11 批次） |

---

## 三、逐步执行清单

> 说明：此处只列**任务级**状态；任务内部的子步骤请对应 batch 文件中的 checkbox。

### 批次 01 · 兜底链路 ★

- [x] 任务 1.0 测试基础设施（问题 37 起步）—— **其他批次补测试的前提**
- [x] 任务 1.1 DeleteTask 状态流转（问题 30）
- [x] 任务 1.2 DLQ 重试链路（问题 02）
- [x] 任务 1.3 开启定时任务（问题 01）
- 批次状态：✅

### 批次 02 · 部署链路

- [x] 任务 2.1 profile 副作用修正 + CQRS 开关（问题 04，**必须先做**）
- [x] 任务 2.2 Dockerfile 模块清单（问题 03）
- [x] 任务 2.3 CQRS 读写分离生效（问题 05，**必须在 2.1 之后**）
- [x] 任务 2.4 ChromaDB 数据持久化修正（**R8**，无批次内顺序依赖，**必须在批次 07 之前**）
- 批次状态：✅

### 批次 03 · 权限链路

- [x] 任务 3.1 统一 AccessDeniedException 处理者（问题 41，**必须先做**）
- [x] 任务 3.2 刷新令牌保留 ABAC（问题 07，**必须先于 3.3**）
- [x] 任务 3.3 用户列表鉴权 + 口令外泄（问题 06 + 33 断裂2）
- [x] 任务 3.4 登出接口（问题 08 + 33 断裂4）
- [x] 任务 3.5 Chat 域鉴权（问题 09 + 33 断裂3）
- [x] 任务 3.6 恢复 canModifyUser admin 直通（问题 39-B2；3.6.2 评估结论：**不增加防自提权约束**，依据已由独立验证证实）
- 批次状态：✅

### 批次 04 · 检索过滤

- [ ] 任务 4.1 operator 生效（问题 20，**必须先做**）
- [ ] 任务 4.2 域过滤空集守卫（问题 19，**必须先于 4.3**）
- [ ] 任务 4.3 LLM 结构化过滤链路（问题 12）
- [ ] 任务 4.4 聚合查询不完整（问题 13）
- 批次状态：⬜

### 批次 05 · 数据源同步（已收窄）

- [ ] 任务 5.1 连接器异常语义 + 增量完整性（问题 24 + 23）
- [ ] 任务 5.2 性能改造：**仅 ①JDBC 流式 + ②ETL 批量写入 + ③队列参数化**（问题 21）
- [ ] 任务 5.3 定时同步（问题 22，**必须最后做**）
- 批次状态：⬜

### 批次 06 · 文档解析

- [ ] 任务 6.1 PDF 表格结构化（问题 10，复盘偏差 1）
- [ ] 任务 6.2 PDF 流式解析与内存保护（问题 11）
- 批次状态：⬜

### 批次 07 · 向量化链路与索引重构 ★

- [ ] **任务 7.0 向量化链路重构（问题 28）—— 最大单项，必须原子完成**
  - [ ] 7.0a 双状态机（Chunk + Document + 前端）
  - [ ] 7.0b 事件驱动（新事件 + 新 Listener + 旧 Listener 退役）
  - [ ] 7.0c 存量数据核对与迁移（id 集合核对）
  - [ ] 7.0d ChromaDB 批量写入与**幂等**
  - [ ] 7.0e BM25 增量索引（双保险）
- [ ] 任务 7.1 Collection 隔离生效（问题 16）
- [ ] 任务 7.2 Embedding 选型回填与失败可见（问题 17）
- [ ] 任务 7.3 BM25Retriever v2（问题 15）
- [ ] 任务 7.4 （已并入 7.0，占位保留）
- [ ] 任务 7.5 语义路由开关接线（问题 38 部分）
- 批次状态：⬜

#### ⚠️ 批次 07 任务 7.0 的执行要求（主 agent 派发时必读）

> 任务 7.0 的规模**明显大于其他批次**——它同时改了数据模型（状态机）、架构（事件驱动）、数据迁移（存量核对）和三条写入路径。
> 派发时请遵守以下三条：

**① 单独派发，不要与 7.1-7.5 混在一起**

- 7.0 是一个**原子改动**，涉及 `Chunk` / `DocumentStatus` / 两条 Listener 链路 / `ChromaConfig` / 前端状态组件
- 与 7.1-7.5 合并派发会导致子 agent 上下文过载，且 7.0 未完成时 7.1（Collection 隔离）的验证环境不成立
- **建议**：7.0 一个子 agent，7.1-7.5 另派

**② 分段推进，每段自检后再进下一段**

- **实际执行顺序为 `7.0a → 7.0b → 7.0d → 7.0c → 7.0e`**（注意 c 与 d 的先后，理由见③）
- 每段完成后先跑该段的"补充测试"，通过后再进入下一段
- 不要在上一段未验证时并行推进下一段——5 段之间存在数据依赖

**③ ⚠️ 7.0c（存量迁移）必须在 7.0d（幂等）完成之后执行——尽管编号上 d 在后**

- **这是编号与依赖顺序不一致的地方，请特别注意**
- 原因：`ChromaEmbeddingStore` **没有 `upsert`**，对已存在的 id 执行 `add` 会报 `DuplicateIDError`
- 若先做 7.0c 的重跑，**必然失败**（存量向量已在 Chroma 中，重跑会撞 id）
- **正确顺序**：7.0a → 7.0b → **7.0d（先解决幂等）** → 7.0c（再执行迁移） → 7.0e
- 已在 `batch-07-向量与索引.md` 的任务 7.0c / 7.0d 中同步标注该顺序约束

**派发指令模板**（可直接复制）：

```
执行 doc/修复-tasks/batch-07-向量与索引.md 的【任务 7.0】。

这是本次修复计划中最大的单项改动，必须【作为一个原子改动完成】——
两条链路（文档上传 + 数据源同步）必须一起切换，拆开会产生"发了事件但没人消费"的中间状态。

【执行顺序（注意：编号与依赖顺序不一致）】
  7.0a 双状态机 → 7.0b 事件驱动 → 7.0d 幂等 → 7.0c 存量迁移 → 7.0e BM25
                                        ↑              ↑
                                    先解决幂等      再做重跑
  原因：ChromaEmbeddingStore 无 upsert，重复 id 会报 DuplicateIDError。
        不先解决幂等就做存量重跑，一定失败。

【每段完成后跑该段的补充测试，通过后再进下一段】

【三个必须注意的点】
  1. 幂等：写入前先 removeAll 再 addAll（对新数据是无操作）
  2. 存量核对：一律做 id 集合核对，不要用"数据量小"做假设
  3. 旧 Listener 退役：删除前逐条核对迁移清单（DLQ 入队、getFileName→getTitle、计数语义）

完成后按 README 第五节的格式报告，并更新 progress.md。
```

### 批次 08 · 删除链路一致性

- [ ] 任务 8.1 软删切片不漏出（问题 27）
- [ ] 任务 8.2 ChromaRetriever 为空不假成功（问题 31）
- [ ] 任务 8.3 孤儿向量扫描（问题 29）—— **不得回退 8.1**
- [ ] 任务 8.4 删除时清理物理文件（问题 32）
- 批次状态：⬜

### 批次 09 · 独立修复

- [ ] 任务 9.1 RRF 去重键（问题 18）
- [ ] 任务 9.2 CharsetDetector（问题 25）
- [ ] 任务 9.3 知识库筛选参数（问题 33 断裂1）
- [ ] 任务 9.4 生产 API 路径双前缀（问题 36）
- [ ] 任务 9.5 模块依赖显式化（问题 40）
- [ ] 任务 9.6 删除并发与重复保护（问题 42）
- [ ] 任务 9.7 ETL 半结构化路径（问题 43）
- 批次状态：⬜

### 批次 10 · 决策类修复

- [ ] 任务 10.1 质检规则 CRUD 与动态加载（问题 35）
- [ ] 任务 10.2 后端 STOMP 服务端（问题 34）
- [ ] 任务 10.3 接入真实交叉编码器（问题 14）
- [ ] 任务 10.4 质检失败明细方案 B（问题 26）
- [ ] 任务 10.5 剩余死配置接线（问题 38 收尾）
- 批次状态：⬜

### 批次 11 · 文档回填与收尾

- [ ] 任务 11.1 设计文档回填（问题 39）
- [ ] 任务 11.2 测试体系收尾（问题 37）
- 批次状态：⬜

---

## 四、关键产出物登记

> 供下游批次消费的产出物，完成时在此登记。

### 4.1 测试基础设施（批次 01 任务 1.0 产出）

| 项 | 内容 |
|---|---|
| 测试框架 | JUnit 5 + Mockito + AssertJ（由根 `pom.xml:52-62` 的 `spring-boot-starter-test` 提供，12 个后端模块自动继承，**无需逐模块声明**） |
| 运行命令 | 全量 `./mvnw test`；单模块 **`./mvnw -pl <module> -am test`**（⚠️ 必须带 `-am`，否则会因本地仓库构件过期而编译失败，见风险 R11）；前端 `cd agent-qr-web-frontend && npx vitest run` |
| 测试类命名约定 | Java：`<被测类名>Test.java`，与被测类**同包**，位于 `<module>/src/test/java/...`；用例名 `方法名_should预期_when条件`；前端：`<模块名>.spec.ts` |
| 可用依赖 | JUnit 5、Mockito（mockito-junit-jupiter）、AssertJ、spring-modulith-starter-test；**未引入** Testcontainers / 嵌入式数据库 |
| 前端环境限制 | jsdom 环境因依赖不兼容不可用（`ERR_REQUIRE_ESM`），纯函数测试用 `// @vitest-environment node` 规避；DOM 组件测试不可用，归批次 11 任务 11.2 |
| 约定全文 | 见 `batch-01-兜底链路.md` 末尾「测试约定」章节 |

**已完成测试类（累计：批次 01 / 02 / 03 + 批次外 R7）**

| 模块 | 测试类 | 用例数 | 覆盖 |
|---|---|---|---|
| agent-qr-common | `ResultTest` | 4 | 冒烟 + `success(String)` 重载歧义锁定 |
| agent-qr-compensation | `DocumentDeleteServiceV2Test` | 3 | 删除失败落 FAILED / 成功落 DONE / 空 ID 短路 |
| agent-qr-web | `DlqRetrySchedulerTest` | 20 | 未知类型不删除、6 类事件分支覆盖、重试体实际动作、异常标记失败、幂等 |
| agent-qr-web | `AgentQrApplicationTest` | 3 | `@EnableScheduling` 存在性 + 5 处 `@Scheduled` 调度点防误删 |
| （批次 02 新增） | `ReadWriteRoutingDataSourceTest`(6) / `ReadWriteDataSourceAspectTest`(4) / `CqrsDataSourceConfigTest`(9) / `CqrsQueryServiceReadOnlyConventionTest`(2) | 21 | CQRS 路由键与真实连接委派、切面 ThreadLocal 清理、Fallback 装配决策、只读约定守护 |
| （批次 03 新增） | auth 3 类（`AbacAccessDeniedHandlerTest` 4 / `RefreshTokenServiceTest` 10 / `AbacEvaluatorTest` 5）、web 4 类（`AdminUsersAccessTest` 11 / `AuthRevokeEndpointTest` 2 / `ChatDomainGuardTest` 8 / `AccessDeniedResponseTest` 2）、rag 2 类（`HybridRetrieverPermissionTest` 5 / `ChatQueryServiceDomainTest` 3） | 50 | 403 统一响应与唯一处理者、刷新令牌保留 ABAC、admin 鉴权/口令外泄/筛选、登出撤销、域鉴权入口+检索双保险、admin 直通 |
| （批次外 R7） | `ChromaConfigTest` | 3 | ChromaDB 1.0.0 路径与 404 判定 |
| agent-qr-web-frontend | `format.spec.ts` | 4 | vitest 链路冒烟 |

> 全量基线：**104 用例**（截至批次 03，已由独立测试子 agent 复跑确认：13 模块 BUILD SUCCESS / 0 失败 / 0 错误 / 0 跳过）。

### 4.2 事件契约清单（批次 01 任务 1.2.1/1.2.3b 定稿；**批次 07 任务 7.0b 将扩展批量事件**）

> 常量定义于 `agent-qr-common/.../dlq/entity/DlqMessage.java`，入队方与重试方共用。

| 事件类型 | eventType 常量值 | 入队位置 | 重试体 | payload 契约与备注 |
|---|---|---|---|---|
| 文档解析 | `EVENT_PARSE` = `PARSE` | `DocumentParseListener.handleDocumentUploaded` catch | `DlqRetryScheduler.retryParse` | `{"documentId":N,"filePath":"...","fileType":"..."}`；filePath 为**未转义**原始路径，解析器采用宽松正则（兼容 Windows 反斜杠） |
| 切片 | `EVENT_CHUNK` = `CHUNK` | `ChunkEmbeddingListener.handleDocumentParsed` 外层 catch | `retryChunk` | `{"documentId":N}`；重试体从 `kb_document` 取回 filePath/fileType 重新解析，并先软删残留切片避免重复 |
| 向量化 | `EVENT_EMBED` = `EMBED` | `ChunkEmbeddingListener`（2 处）、`DataSyncEtlListener` | `retryEmbed` | `{"chunkId":N,"documentId":N}` 或 `{"chunkId":N,"datasourceId":N,"batchId":"..."}`；**批次标识优先**（documentId > datasourceId > chunkId）整批重放 ← 1.2.3b 的批量语义预留 |
| 物理删除 | `EVENT_DELETE` = `DELETE` | `DocumentDeleteListener`、`DocumentDeleteServiceV2` catch | `retryDelete` | 兼容两种 chromaIds 形态：逗号串 `"a,b"` 与 JSON 数组 `["a","b"]`；含 DONE 幂等判断 |
| ETL | `EVENT_ETL` = `ETL` | `DataSyncEtlListener`（2 处） | `retryEtl` | `{"datasourceId":N,"batchId":"...","recordCount":N}`；⚠️ **缺 passedData 无法重放**，重试体显式标记失败并留 TODO（批次 07/10 需要"可重放批次快照"） |
| ChromaDB 写入 | `EVENT_CHROMA_WRITE` = `CHROMA_WRITE` | `ChunkEmbeddingListener`、`DataSyncEtlListener` | `retryChromaWrite` | `{"chunkId":N,...}`；切片已有 chromaId 时**幂等跳过** |
| 未知类型（兜底） | — | — | `handleUnknownEventType` | **保留记录不删除**，每轮扫描持续 error 告警直至人工处理（原实现为静默删除） |
| （批次 07 新增） | 待 7.0b 填写 | | | 预期含 `ChunksBatchCreatedEvent` 驱动的批量向量化事件 |

### 4.3 运行环境事实（2026-10-06 实测）

| 项 | 实测值 | 来源 | 影响的任务 |
|---|---|---|---|
| Ollama 版本 | 0.35.0 | `ollama --version` | 5.2.5 |
| 旧端点 `/api/embeddings` | ✅ 仍可用（当前代码使用） | 直接 POST | 5.2.5 |
| 新端点 `/api/embed` | ✅ 可用（接受 `input` 数组） | 直接 POST | 5.2.5 |
| Embedding 模型 | `qwen3-embedding:4b`（4.0B / Q4_K_M） | `GET /api/tags` | 17 |
| **向量维度** | **2560** | `/api/tags` 的 `embedding_length` + 实测返回长度 | 16、17 |

> 验证提示：首次请求可能因**模型冷加载**返回 `HTTP 000`，需给足超时（≥60s）或先预热。

**Embedding 性能基准（2026-10-06 实测）**

| 场景 | 耗时 | 备注 |
|---|---|---|
| 单条调用（无并发） | **2173 ms** | 固定开销占主导 |
| 批量 `/api/embed` 32 条 | **6143 ms** | 每条均摊 192 ms |
| 1 / 4 / 8 并发单条请求 | 2173 / 2539 / 2669 ms | **Ollama 能并行处理并发请求**（8 并发约 6.5 倍） |
| 对等对比 @8 线程 × 8 条 | 18.2s（逐条） vs 6.6s（批量） | **2.7 倍** |
| **对等对比 @16 线程 × 16 条**（贴近真实配置） | 36.6s（逐条） vs 24.7s（批量） | **1.5 倍** |

> **关键**：批量化的收益**随并发度升高而衰减**——因为当前实现已是 16 线程并发，已拿回部分吞吐。
> **不要引用"11.7 倍"**（那是单线程下的数字，非对等条件）。

### 4.4 存量数据核对结果（**已于 2026-10-06 实测完成**）

> 方式：**id 集合核对**（非数量对比），符合既定决策。
> 数据来源：MySQL（容器 `agent-qr-mysql`，端口 3308）+ ChromaDB（容器 `agent-qr-chromadb`，端口 8000）。

| 项 | 数量 | 明细 |
|---|---|---|
| `kb_chunk` 有效切片（`deleted=0`） | **19** | |
| `kb_chunk` 全部行数 | 11788 | 历史上做过大规模同步，11769 条已软删 |
| ChromaDB 向量（`embedding_metadata.key='chunk_id'`） | **6** | |
| **两边都有**（存量保持 `READY`） | **6** | `11771, 11772, 11773, 11774, 11779, 11791` |
| **仅在 MySQL**（置 `INDEXED` 重跑） | **13** | `7362, 7386, 7387, 7388, 7389, 7390, 7391, 7392, 7393, 7394, 7395, 7396, 7397` |
| **仅在 Chroma**（孤儿向量，归批次 08） | **0** | 当前无孤儿向量 |

**关键观察**：较老的 id（`7362`、`7386-7397`）**没有向量**，较新的 id（`11771+`）**有向量**——这与 `rag.embedding.write-to-chromadb` 注释中"P1: 关闭 ChromaDB 写入"的说法吻合，**该开关在历史上确实起过作用**。

**其他实测确认**：

| 项 | 值 | 结论 |
|---|---|---|
| collection name | `enterprise_knowledge`（id `7fbaddfc-...`） | ✅ 存在 |
| collection **dimension** | **2560** | ✅ 与 `qwen3-embedding:4b` 一致 |
| collection **space** | **cosine** | ✅ `ChromaConfig.ensureCosineDistance()` 生效 |
| 向量 id 格式 | **UUID** | chunkId 存于 `metadata.chunk_id`（对问题 18 的修复方案有直接影响） |
| `dlq_message` 行数 | 0 | 符合"DLQ 从未真正重试过"的现状 |

### 4.4b `kb_chunk_structured` 数据覆盖（问题 04 灰度前提）

| 项 | 值 |
|---|---|
| 总行数 | **135** |
| 去重切片数 | **15** |
| **域分布** | **仅 `HR` 一个域** |
| 字段名 | `username`、`real_name`、`department`、`title`、`clearance_level`、`email`、`phone`、`role`、`create_time`、`update_time` |

**结论**：批次 04 任务 4.3 的 LLM 结构化过滤**具备数据前提，可以开启灰度**。
**但注意**：只有 `HR` 域的查询会有候选集；其他域的精确查询会走"空候选集 → 返回空"路径（即任务 4.2 的预期行为）。
**灰度验证时只能用 HR 域的问题来验证**（如"月薪大于 X 的 HR 员工"类查询）。

### 4.5 配置键接线状态（批次 07、10 产出）

| 配置键 | 原状态 | 处理方式 | 完成批次 |
|---|---|---|---|
| `agent-qr.reranker.model` | 死配置 | 接线 | 10.3 |
| `agent-qr.embedding.collection-prefix` | 死配置 | 接线 | 7.1 |
| `agent-qr.embedding.auto-dimension-check` | 死配置 | 接线 | 7.1 |
| `agent-qr.routing.mode` | 死配置 | 接线 | 7.5 |
| `agent-qr.cache.max-size` / `ttl-hours` | 死配置 | 接线 | 10.5.1 |
| `VITE_SSE_TIMEOUT` / `VITE_TOKEN_REFRESH_AHEAD` / `VITE_SSE_MAX_RECONNECT` | 死配置 | 接线 | 10.5.2 |
| `rag.embedding.write-to-chromadb` | 死配置 + 注释矛盾 | 待定 | 10.5.3 |
| `spring.datasource.{write,read}.hikari.*`（p3） | 死配置（被静默忽略，实测 maxPoolSize=10≠声明 20） | **待决策**（R10） | 10.5 候选 |

### 4.6 批次 02 产出与遗留物（2026-10-06）

| 项 | 内容 | 处置建议 |
|---|---|---|
| ChromaDB 备份 | `D:\Javacode\agent-qr-chroma-backup-20261006`（47MB；`chroma.sqlite3` 10211328 字节，sha256 与容器内源文件逐字节一致；**位于仓库外**，不污染 git） | **保留至批次 07 完成**——作为 7.0c 存量迁移的安全网 |
| 验证镜像 | `agent-qr-backend:batch02-verify`（747MB，`docker build` 成功的实物证据；⚠️ 构建于 2.3 注解之前，仅证明构建机制正确，**如需部署须重新构建**） | 可删除（占空间）；留作证据亦可 |
| 环境残留（未动） | 已退出容器 `agent-qr-backend`（3 个月前）、遗留卷 `agent_qr_chroma_data`（与现用 `agent-qr-chroma-data` 并存） | 待用户确认后清理，本批次**未擅自处置** |

### 4.7 依赖兼容性实测结论（2026-10-06，批次 06 前置调研）

| 项 | 结论 |
|---|---|
| 背景 | 问题 10 决策走 `tabula-java`（方案文档指定版本 **1.0.5**），但该版本 POM 声明 **PDFBox 2.0.24**，而项目使用 **PDFBox 3.0.3**（大版本 API 差异），存在"是否兼容"的疑问 |
| 调研 1（仓库） | Maven Central：`technology.tabula:tabula` 最新发布版确为 **1.0.5**（2021-08）；**无 1.0.6 发布**；Sonatype 快照仓库**无**该制品；GitHub master 已是 `1.0.6-SNAPSHOT` 且已升级 PDFBox 3.0.4（**未发布到任何仓库**） |
| 调研 2（实验） | 独立实验项目 `D:/Javacode/tabula-compat-test`（**仓库外**，自生成带边框表格 PDF，无外部数据）：tabula 1.0.5 + PDFBox 3.0.3 运行时——`ObjectExtractor` 构造 ✅、页面解析（PDFStreamEngine 真实遍历内容流）✅、`BasicExtractionAlgorithm` 提取到 1 表 ✅、**`SpreadsheetExtractionAlgorithm` 提取到 1 表（rows=3, cols=3）** ✅ |
| 依赖调解 | `mvn dependency:tree` 确认调解后 classpath **只有 PDFBox 3.0.3**（2.0.24 被排斥），无版本混装 |
| **最终结论** | **tabula 1.0.5 与 PDFBox 3.0.3 实测兼容**，批次 06 按原决策直接引入 `technology.tabula:tabula:1.0.5`，**无需**排除传递依赖 / 降级 PDFBox / 更换方案 |
| 备注 | ① 实验覆盖基础表格场景，复杂真实 PDF 仍建议批次 06 实现后回归验证；② tabula 1.0.5 的 `Table` **无 `getCols()` 方法**，列数需从 `getRows().get(0).size()` 推断（实验中踩过） |

---

## 五、待确认事项

> 执行过程中需要人工决策或需上报的事项。

> 批次 01、02 的事项已确认或已实测完成；批次 03 执行中**新增 1 项待确认（#14）**（#13 仍待用户决策）。

| # | 事项 | 状态 | 结论 |
|---|---|---|---|
| 1 | `kb_chunk_structured` 数据覆盖率 | ✅ **已实测** | **135 行 / 15 切片，仅 `HR` 一个域**。批次 04 的灰度**具备数据前提**，但**只能用 HR 域的问题验证**。详见本文件 4.4b |
| 2 | DeepSeek Embedding API | ✅ 已澄清 | **DeepSeek 不提供 Embedding API**。选型为本地 Ollama + `qwen3-embedding:4b`，属有意决策 |
| 2b | Embedding 失败语义 | ✅ 已确认 | **整批失败**，移除逐条降级重试（`retrySingle` 调用点） |
| 2c | 整批失败后 DLQ vs FAILED | ✅ **已确认** | **整批入一次 DLQ**，由 DLQ 退避重试。⚠️ 需扩展 `EMBED` 重试体以支持**整批**重放（批次 01 的重试体原是按单条设计） |
| 2d | Ollama 端点与模型事实 | ✅ 已实测 | Ollama **0.35.0**；旧端点 `/api/embeddings` 仍可用；新端点 `/api/embed` 可用；维度 **2560** |
| 2e | `embedBatch` 是否改用批量端点 | 🟡 **部分实测** | **(b) 已实测：端到端提速 1.5 倍**（16 线程对等条件，256 条嵌入 36.6s → 24.7s）。**结论对并发度高度敏感**：8 线程 2.7 倍、16 线程 1.5 倍、单线程 11.7 倍（不可用）。<br>**(a) 向量化占总同步耗时比例仍需实测**——须等批次 05 的 ①②③ 完成后才能测准。详见 batch-05 任务 5.2.5 |
| 2f | size 分支去留 | ⬜ 待 2e 的 (a) | 若 2e 判定改造 → 保留；若不改造 → 永久不可达，可移除 |
| 3 | Reranker 部署形态 | ✅ **已确认** | **本地部署推理服务**（如 Xinference / TEI 加载 bge-reranker-v2-m3），与 Ollama/ChromaDB 的本地化风格一致。⚠️ 本机当前**无任何 reranker 服务**，需先部署 |
| 4 | `write-to-chromadb` 语义 | ✅ **已确认** | **接线为"跳过向量化"开关**：关闭时 chunk 只到 `INDEXED`，不做向量化。语义与双状态机自洽 |
| 5 | `canModifyUser` admin 直通 | ✅ **已确认** | **以设计为准，恢复 admin 直通**（设计 §3.2.9 原文 `if (user.isAdmin()) return true;`）。⚠️ 需评估是否补充"防自提权"约束 |
| 6 | Collection 迁移策略 | ✅ **已确认** | **保持 collection 不变，按差集补写 13 条**。依据：collection 配置正确（2560/cosine）、孤儿向量 0 条、待补仅 13 条 |
| 7 | PDF 表格路径 | ✅ **已确认** | **tabula-java**（方案文档推荐）。Tika 依赖应一并移除 |
| 8 | 两个 Embedding 配置项 | ✅ 已定 | 保留 `embedding.provider`（实际生效），删除 `preferred-embedding`（不参与决策） |
| 9 | 存量 kb_chunk 差异 | ✅ **已核对** | 19 vs 6；**补 13 条**；孤儿 0 条。详见 4.4 |
| 10 | 幂等实现方式 | ✅ **已确认** | **每次写入前先 `removeAll` 再 `addAll`**——一劳永逸覆盖重跑/DLQ 重放/失败重试 |
| 11 | Document 聚合触发时机 | ✅ 已定 | **查询时实时聚合计算**（变更回调需维护一致性，实时计算更简单且不会不一致） |
| 12 | R8（ChromaDB 持久化隐患）归属 | ✅ **已确认** | **归入批次 02 新增任务 2.4**（2026-10-06）。须在**批次 07 之前**完成——7.0c 的存量迁移依赖当前 6 条向量，容器重建会使 4.4 的核对基线作废。**已于批次 02 完成并两次重建实测** |
| 13 | R10：`application-p3.yml` 的 `hikari:` 子块死配置（连接池参数被静默忽略，声明 20 实为 10）归属 | 🟡 **待用户决策** | 批次 02 新发现（实测确认）。选项：① 并入批次 10 任务 10.5（死配置收尾）② 单独提前修复。**不阻塞批次 07**；修复方式为去掉嵌套 `hikari` 层（`spring.datasource.write.maximum-pool-size`） |
| 14 | R12：批次 03 强制 domain 后的**前端联动缺口**（域选择器默认「全部域」→ 400 / axios 对 403 显示「网络连接失败」/ SSE 缺域静默） | 🟡 **待用户决策** | 独立测试子 agent 已核实属实（代码 + curl 双证据）。选项：① 立即补一个前端小修复任务（改 `ChatInput.vue` 默认域 + `api/index.ts` 403 分支）② 归入批次 11 收尾 ③ 暂不处理。**建议 ①**——前端问答入口当前不可用，属用户可直接感知的功能缺口 |

---

## 六、风险与回退记录

> 执行中发现的实际风险、回退操作、以及对其他批次的影响。

| # | 日期 | 批次 | 事件 | 影响 | 处理 |
|---|---|---|---|---|---|
| R1 | 2026-10-06 | 01 | DELETE 重试经 `DocumentDeleteServiceV2.asyncPhysicalDelete`（`@Async` fire-and-forget）提交，提交即标记 DLQ 成功 | 若异步删除再次失败，`asyncPhysicalDelete` 会创建新的 DeleteTask 并**重新入队新消息**（退避计数从 0 重置）；持续失败时将无限重试、`delete_task` 表持续增长，而非 4 次后进 DEAD | 按 batch-01 指定实现（"调用 documentDeleteServiceV2 的实际删除方法"）。**建议批次 08 评估改为同步语义**（`ChromaRetriever.deleteByIds` 本身失败会抛异常，可直接驱动 DLQ 退避） |
| R2 | 2026-10-06 | 01 | EMBED/CHUNK 重放按批次标识整批执行，可能重复写入切片/向量 | `ChromaEmbeddingStore` 无 upsert，重复写入会产生新的 UUID 记录（检索出现重复） | 幂等由批次 07 任务 7.0d（写入前 `removeAll`）解决；`retryChunk` 已先 `softDeleteByDocumentId` 降低切片重复 |
| R3 | 2026-10-06 | 01 | 入队 payload 由 `String.format` 拼接，**Windows 路径反斜杠未转义**，payload 非法 JSON | 严格 JSON 解析器会失败 | 重试体采用**宽松正则解析**规避（已验证可解析 `C:\uploads\a.pdf`）；建议批次 07 统一改用 Jackson 序列化 |
| R4 | 2026-10-06 | 01 | ETL 事件 payload 缺 `passedData`，无法重放 | `retryEtl` 只能显式标记失败，4 次退避后进 DEAD（不再静默丢失，但无法自愈） | 已留明确 TODO；批次 07/10 需引入"可重放的批次快照" |
| R5 | 2026-10-06 | 01 | `Result.success(String)` 与 `success(T data)` 重载歧义（T=String 时命中 message 重载，data 为 null） | 期望携带 String 数据的调用方会静默拿到 null | 非批次 01 范围（既有 API 缺陷）；已在 `ResultTest` 锁定行为防回归，**建议后续单独修复**（如需可加 `successData(T)` 或调整重载） |
| R6 | 2026-10-06 | 01 | 前端 jsdom 测试环境因依赖不兼容不可用（`html-encoding-sniffer` → `@exodus/bytes` 触发 `ERR_REQUIRE_ESM`） | 需要 DOM 的组件测试无法运行 | 纯函数测试用 `// @vitest-environment node` 规避；**归批次 11 任务 11.2 收尾** |
| R7 | 2026-10-06 | 01（**批次外，已修复**） | **`ChromaConfig` 的 collection 检查/创建 REST 路径缺少 tenant/database 段**，在 ChromaDB 1.0.0 上返回 404/400；且 `.onStatus(is4xxClientError)` 把 400 一并误判为"collection 不存在" | ① 每次启动打印"不存在 + 创建失败"两条告警；② **"确保 cosine" 的防护静默失效**——若 collection 被重建，将由 langchain4j 以默认 **L2** 创建（实测 ChromaDB 1.0.0 默认 `space=l2`），检索效果下降且无任何告警 | **已修复**（2026-10-06 经确认，批次外）：① 路径补全为 `/api/v2/tenants/{tenant}/databases/{database}/collections`；② 命名空间固定 `default`/`default`（与 langchain4j `ChromaClientV2` 字节码默认值一致，避免与读写命名空间分裂）；③ 404 判定收窄为仅 404，其余 4xx 向上抛出告警。新增 `ChromaConfigTest`（3 用例）；启动验证：由"不存在 + 创建失败"变为"**已存在 (id=7fbaddfc-4cd8-4651-b987-827e81e31257)，跳过创建**" |
| R8 | 2026-10-06 | 01（**批次外**）→ **已转入批次 02 任务 2.4** | **ChromaDB 数据目录与挂载卷不匹配**：docker-compose.yml 设 `PERSIST_DIRECTORY=/chroma/chroma` 并把卷挂载于该路径，但 ChromaDB 1.0.0（Rust 版）实际写入 **`/data`**（47MB 数据在此，`/chroma/chroma` 仅 4KB） | **容器重建/删除即丢失全部向量数据**（当前 6 条历史向量 + collection 配置） | **未修复，已确认归入批次 02（部署链路）**为任务 2.4（2026-10-06 决策）。归入理由：① 与任务 2.1 共用 `docker-compose.yml`（避免两次改动同一文件）；② 同属"容器化交付链路可靠"主题；③ **须在批次 07 之前完成**——7.0c 存量迁移依赖当前 6 条向量，容器重建会使 4.4 的核对基线作废 |
| R9 | 2026-10-06 | 01（观察，属已知问题 16） | 启动日志显示 `EmbeddingDimensionManager` 计算出的 collection 名为 `kb_ollama_ollama`，而实际读写使用 `enterprise_knowledge`——两者不一致 | 印证问题 16（Collection 隔离为死代码）：维度管理器计算的名字未被任何读写路径采用 | 属**批次 07 任务 7.1（Collection 隔离生效）**范围，本次不改 |
| R10 | 2026-10-06 | 02（**新发现，未修**） | **`application-p3.yml` 的 `hikari:` 子块是死配置**（实测确认）：`HikariDataSource` 无嵌套 `hikari` 属性，`spring.datasource.write.hikari.maximum-pool-size: 20` 等键被**静默忽略**，实测绑定为 Hikari 默认值（maxPoolSize=10 / minIdle=-1）。声明的连接池参数从未生效 | 连接池容量不符预期（声明 20 实为 10）；属批次 02 主题（CQRS 配置副作用）但**不在 batch-02 任务清单内**，按 README 第八节第 4 条**上报不自行决策** | **待用户决策**：并入批次 10 任务 10.5（死配置收尾）一并修复，或单独提前修复。正确写法：`spring.datasource.write.maximum-pool-size`（去掉嵌套 `hikari` 层级）。**注：不修也不影响批次 07 前置** |
| R11 | 2026-10-06 | 02（操作陷阱） | 本地 `~/.m2` 中已安装的 `org.example:agent-qr-*` 构件**早于批次 01**，因此 `./mvnw -pl <module> test` 会因找不到 `DlqMessage.EVENT_*` 等新常量而编译失败（实测） | 单模块测试命令会误报失败，浪费排查时间 | **规避**：一律用 `./mvnw -pl <module> -am test`（-am 让 reactor 以源码构建依赖模块），或先 `./mvnw install -DskipTests` 刷新本地仓库。已登记供各批次参考 |
| R12 | 2026-10-07 | 03（**独立验证发现，待决策**） | **强制 domain 校验的前端联动缺口**：① `ChatInput.vue` 域选择器默认「全部域」→ 不传 domain → 后端 400；② `api/index.ts` axios 拦截器对 HTTP 403 只提示「网络连接失败」；③ 缺域 400 走 HTTP 200+body code=400，而前端 SSE（fetchEventSource）只看 HTTP 状态 → **静默无响应** | 前端用户视角：问答入口不可用/无反馈。后端策略本身系按 batch-03 建议执行（强制 domain），副作用在前端侧 | **待用户决策**（见「待确认事项 #14」）：补前端小修复 / 归入后续批次 |
| R13 | 2026-10-07 | 03（**既有缺陷**，独立验证确认） | `JwtAuthenticationFilter` 不校验 `tokenType`：用 Refresh Token 充当 Bearer 时 `principal.getRole()` 为 null → `getRole().toUpperCase()` 抛 NPE → 客户端收到 403 空 body（应为 401） | 误用凭证时服务端 ERROR 日志 + 语义错误的 403；**非批次 03 引入** | 建议单独立项：按 `tokenType` 显式拒绝 Refresh Token |
| R14 | 2026-10-07 | 03（独立验证发现） | **统一 403 响应的未覆盖角落**：匿名请求走 Spring 默认 `Http403ForbiddenEntryPoint`（SecurityConfig 只配了 accessDeniedHandler，未配 authenticationEntryPoint）→ HTTP 403 + **0 字节空 body**，无统一 Result | 未认证请求的响应结构与 3.1「统一 Result」目标不一致（实测：匿名 `GET /api/admin/users`、无 Token `POST /api/auth/revoke`） | 建议后续补 `authenticationEntryPoint`（401+Result）；不阻塞批次 04 |
| R15 | 2026-10-07 | 03（独立验证发现） | `PUT /api/admin/users/{id}/status` **无自保护**：admin 可把自己 `status` 置 0（实测 HTTP 200 且落库）→ 自锁 | 3.6.2「防自提权评估」未覆盖的同类面（自锁而非提权） | 建议随 R13/R14 一并评估处理 |

---

## 七、验收证据汇总

> 每个批次完成后，在此登记验收方式与证据。

| 批次 | 验收项 | 验证方式 | 证据 | 结果 |
|---|---|---|---|---|
| 01 | 1.0 `mvn test` 可执行 + 冒烟测试通过 | `./mvnw -pl agent-qr-common test` | `Tests run: 4, Failures: 0, Errors: 0` | ✅ |
| 01 | 1.0 前端测试链路 | `npx vitest run` | `Test Files 1 passed / Tests 4 passed` | ✅ |
| 01 | 1.1 物理删除失败落 `FAILED` | 单元测试 `DocumentDeleteServiceV2Test` | 3 用例通过；断言 `updateStatus(1L,"FAILED")` + `incrementRetryCount` + 入队并存 | ✅ |
| 01 | 1.1 `STATUS_FAILED` 被引用 | `grep STATUS_FAILED` | `DocumentDeleteServiceV2.java` catch 分支 1 处引用 | ✅ |
| 01 | 1.1 Mapper 新增查询可用 | MySQL 实库执行等价 SQL（容器 3308） | `SELECT ... WHERE status='FAILED' ORDER BY id DESC`、`... WHERE document_id=? ORDER BY id DESC` 均正常返回（表 5 行） | ✅ |
| 01 | 1.2 入队方无裸字符串字面量 | `grep 'enqueue\(\s*"'` | 零命中（含额外补齐的 `DocumentParseListener` / `DocumentDeleteListener`） | ✅ |
| 01 | 1.2 未知类型不被删除 | 单元测试 + **真实启动验证** | `DlqRetrySchedulerTest.unknownEventType_shouldNotBeDeletedFromDlq` 通过；启动后 4 轮调度，探测消息保持 `PENDING`、未被 DELETE | ✅ |
| 01 | 1.2 switch 覆盖全部定义类型 | 参数化测试 `everyDefinedEventType_shouldReachARetryBranch` | 6 种类型（PARSE/CHUNK/EMBED/DELETE/ETL/CHROMA_WRITE）各命中具体分支 | ✅ |
| 01 | 1.2 重试体执行实际业务动作 | 单元测试 + **真实启动验证**（构造不存在文件的 PARSE 死信） | 日志 `DLQ 重试解析: msgId=92` → `error=文件不存在` → `将在 9 秒后重试: retryCount=1` → 再失败 `将在 27 秒后重试: retryCount=2`；DB 中 `retry_count=2, status=PENDING, error_msg 已更新` | ✅ |
| 01 | 1.2 重试体抛异常标记失败 | 单元测试 `retryParse_shouldMarkFailed_whenParserThrows` 等 | `updateRetryResult(id,false,e)` 被调用、`(id,true,null)` 从未被调用（覆盖 PARSE/CHUNK/EMBED/DELETE/ETL 五类） | ✅ |
| 01 | 1.3 `@EnableScheduling` 生效 | 反射测试 + **真实启动验证** | 启动耗时 35.3s、无 Bean 冲突；`[scheduling-1]` 线程稳定每 30 秒执行（19:40:11/41/42/43 共 4 轮） | ✅ |
| 01 | 1.3 其余 4 处 `@Scheduled` 未受影响 | `AgentQrApplicationTest.fiveScheduledJobs_shouldAllExistAndBeAnnotated` | 5 个调度点全部存在且带注解 | ✅ |
| 01 | 批次级：项目可编译 + 全量测试通过 | `./mvnw test`（13 模块） | `Reactor Summary` 全 SUCCESS，`BUILD SUCCESS` | ✅ |
| 01 | 批次级：验证后环境复原 | MySQL 实查 | 探测数据已清理，`dlq_message` 恢复 0 行 | ✅ |
| 02 | 2.1 读写库地址可外部覆盖 | 代码审阅 + 单元测试 | `application-p3.yml:12,26` 改为 `${SPRING_DATASOURCE_WRITE_URL:jdbc:mysql://localhost:3308/...}` / `...READ_URL...:3309`（默认值保留）；`CqrsDataSourceConfigTest` 2 用例（真实 yml + 环境变量源绑定） | ✅ |
| 02 | 2.1 compose 键名与实际绑定键一致 | 代码审阅 + 跨文件一致性测试 | `docker-compose.yml:86-87` 注入 `SPRING_DATASOURCE_WRITE_URL`/`READ_URL`（旧 `SPRING_DATASOURCE_URL` 已移除）；`composeDatasourceEnvVars_shouldMatchP3Placeholders` 守护 | ✅ |
| 02 | 2.1 `readReplicaFallbackToPrimary` 真正参与决策 | **真实启动实测** | 启动日志：`CQRS 读库连通性探测失败: Communications link failure` → `read-replica-fallback-to-primary=true → 读路由回退到写库数据源` → `ReadWriteRoutingDataSource 已初始化: read=jdbc:mysql://localhost:3308/...` | ✅ |
| 02 | 2.2 `docker build` 成功 | 实际构建 | `EXIT_CODE=0`，容器内 `BUILD SUCCESS`（13:51 min），Maven 反应堆 **13/13 全 SUCCESS**；产物 747MB | ✅ |
| 02 | 2.2 上下文含全部 12 模块 + .dockerignore 生效 | 构建日志 | 反应堆逐条列出根 + 12 模块；构建上下文由 615MB 降至 **4.36MB**（`transferring context`） | ✅ |
| 02 | 2.3 `@Transactional(readOnly=true)` ≥3 查询侧 Service | 代码审阅 | **4 个 Service / 13 方法**：`DocumentQueryService`(5)、`StatisticsQueryService`(1)、`DataSourceService`(5)、`KnowledgeCatalogService`(2)；`CqrsQueryServiceReadOnlyConventionTest` 守护（查询方法必须有、写方法严禁有） | ✅ |
| 02 | 2.3 只读/写方法各自路由正确 | 单元测试 | `ReadWriteDataSourceAspectTest`(4) + `ReadWriteRoutingDataSourceTest`(6)：注解→切面→ThreadLocal→路由键→**真实连接委派**全链路；ThreadLocal `finally.clear()` 覆盖异常路径 | ✅ |
| 02 | 2.3 读库不可用降级符合策略 | 单元测试 + 真实启动 | `CqrsDataSourceConfigTest` 覆盖可达/不可达×fallback 开/关 三种装配决策；真实启动见上行 | ✅ |
| 02 | 2.4 卷挂载点与 1.0.0 实际目录一致 | `docker inspect` | `agent-qr-chroma-data -> /data`（原 `/chroma/chroma`）；`PERSIST_DIRECTORY` 保留并注明 1.0.0 起不生效 | ✅ |
| 02 | 2.4 重建后数据仍在（先备份再重建） | 备份校验 + **两次 `--force-recreate` 实测** | 备份 47MB、`chroma.sqlite3` sha256 逐字节一致；两次重建后 collection id `7fbaddfc-…` / `enterprise_knowledge` / dim 2560 / cosine / **6 条向量**全部仍在；卷内可见 sqlite（10211328 字节） | ✅ |
| 02 | 2.4 存量核对基线未破坏 | MySQL + ChromaDB 直查（主 agent 复核） | `kb_chunk` 有效切片 **19**、总行 **11788**、`dlq_message` **0**、Chroma 向量 **6** —— 与 4.4 基线完全一致 | ✅ |
| 02 | 批次级：全量测试 | `./mvnw test`（主 agent 独立执行） | 13 模块全 SUCCESS，**BUILD SUCCESS**，共 **54** 用例 0 失败（新增 21 条全绿） | ✅ |
| 02 | 批次级：功能对等（查询不因路由改动失败） | **真实启动 jar 实测**（主 agent 执行，重新打包后启动） | `Started AgentQrApplication in 32.763s`、**服务级 ERROR 0 条**（唯一 ERROR 为验证用 `GET /api/auth/login` 触发 405 所致）；HTTP 响应正常（200/403）；**调度 4 轮正常执行**；停止后数据基线零变化 | ✅ |
| 02 | 批次级：硬约束 2 未违反 | 执行顺序核验 | 2.1 完成并验证（测试 + compose config 解析）后才执行 2.3；2.3 改动仅新增注解，未改路由组件 | ✅ |
| 03 | 3.1 统一 403 处理者 | 独立验证（全仓扫描 + 运行时响应） | 全仓唯一 `@ExceptionHandler(AccessDeniedException)`（AbacAccessDeniedHandler，@Order(HIGHEST_PRECEDENCE)）；两条已认证路径响应体一致（统一 Result，审计含 uri/method）；`AccessDeniedResponseTest` 以反向注册顺序证明 @Order 生效 | ✅ |
| 03 | 3.2 刷新令牌保留 ABAC | 独立验证（解析真实签名 token claim） | `RefreshTokenServiceTest` 10/10；E2E：admin 刷新后新 Access Token `role=admin` 且 4 项 ABAC 属性完整；禁用用户被拒且**不消费**旧令牌（未轮换） | ✅ |
| 03 | 3.3 鉴权 + 口令外泄 + 筛选 | 独立验证（curl 原始响应） | 普通用户 403 / admin 200；响应 JSON `password` 与 `$2a$` 均 0 命中；`department`/`title`/`keyword` 及组合筛选实测生效；IPage 分页字段齐全 | ✅ |
| 03 | 3.4 登出接口 | 独立验证（真实调用） | `POST /api/auth/revoke` → 200；`token_refresh` 有效行 1→0；旧 Refresh Token 刷新 401；无 Token 403（未加白名单） | ✅ |
| 03 | 3.5 域鉴权双保险 | 独立验证（8 条 E2E + 代码审查） | `/ask` 与 `/ask/stream`：越域 403 / 缺域 400 / 本域 200 / admin 跨域 200；检索层 Step 1.6 独立裁剪（`HybridRetrieverPermissionTest` 5/5），**未触碰**空候选守卫（diff 确认 69 insertions / 0 deletions） | ✅ |
| 03 | 3.6 admin 直通 + 3.6.2 评估 | 独立验证（E2E 落库 + 证伪） | 与设计 §3.2.9 逐行一致；修复者「自编辑敏感字段对 admin 同样被拒」的辩护证据被独立 E2E **证实**（title/role/clearanceLevel/allowedDomains 全 403，非敏感字段可改，改他人 200 且落库） | ✅ |
| 03 | 硬约束 3 未违反 | **独立端到端复现** | admin 登录 → 刷新 → 新 token（role=admin、ABAC 完整）→ `GET /api/admin/users` HTTP 200（未复现"被锁在门外"） | ✅ |
| 03 | 批次级：全量测试 | 独立子 agent 复跑 `./mvnw test` | 13 模块 BUILD SUCCESS，**104 用例 / 0 失败 / 0 错误 / 0 跳过**（与修复者声明一致） | ✅ |
| 03 | 批次级：数据基线 + 清理 | 独立子 agent 复查 | ChromaDB 6 / sys_user 12 / kb_chunk(deleted=0) 19 / dlq_message 0，前后一致；临时账号、令牌、会话全部清理（应用已停止） | ✅ |

---

## 八、更新日志

| 日期 | 更新内容 | 更新者 |
|---|---|---|
| 2026-10-06 | 初始化，共 11 个批次、44 个任务 | 主 agent |
| 2026-10-06 | **依据项目负责人指出的事实更正问题 17**：Embedding 选型（本地 Ollama + `qwen3-embedding:4b`）是**有意决策**、DeepSeek 不提供 Embedding API。问题 17 由"DeepSeekEmbeddingProvider 缺失"改为"选型未回填文档且为单点无降级"，任务 7.2 相应重写（原"实现 DeepSeekEmbeddingProvider"方向已废弃） | 主 agent |
| 2026-10-06 | **同口径复核其他配置类问题**：发现问题 38 把"注释已如实标注的占位符"误判为缺陷。已区分 A 类（误导性，须处理）/ B 类（诚实占位符，接通即可），问题 16、38 与任务 7.1.3、10.5 同步更正 | 主 agent |
| 2026-10-06 | **补充问题 10 的实现路径分歧**：代码注释指向 Tika，补救方案文档推荐 tabula-java。已在问题 10 与任务 6.1.1 中标注需先确认 | 主 agent |
| 2026-10-06 | **确认 Embedding 失败语义**：整批失败，移除 `BatchEmbeddingService` 的逐条降级重试（`embedBatch` 本身即逐条循环，重试等于重复失败调用）。已更新问题 17、任务 7.2.5/7.2.5b 与任务 7.4.4 的联动说明 | 主 agent |
| 2026-10-06 | **实测 Ollama 环境**：版本 0.35.0；旧端点 `/api/embeddings` 仍可用（兼容性无忧）；批量端点 `/api/embed` 可用；向量维度 **2560**。据此调整任务 5.2.5（改为「先测后改」）、任务 7.2.5a（size 分支去留与 5.2.5 联动），并在问题 16、17 中补充确认数据。另发现 P2 文档「Ollama 原生不支持批量」已过时 | 主 agent |
| 2026-10-06 | **发现设计文档自相矛盾（A16）**：§17.8 称 `embedBatch` "一次处理整批"（复杂度 O(N/B)），但 §6.2.4 的示例实现是逐条循环（O(N)）。同时 §17.8 d 步明确要求"失败降级逐条重试"——即 `retrySingle` **是按设计实现的**。已把任务 7.2.5 与问题 17 中的表述由"修复缺陷"改为"**设计变更**"，并要求同步回填设计文档 | 主 agent |
| 2026-10-06 | **确认向量化链路重构方案**（问题 28 + 21），并据此**重构批次划分**：<br>① 双状态机（`INDEXED` 插入 `CHUNKING` 与 `EMBEDDING` 之间，共 8 状态；Chunk 与 Document 都改，Document 聚合推导；前端展示两个状态）<br>② 事件驱动（两条链路都改，旧 `ChunkEmbeddingListener` 退役，事件粒度=每文档/每数据源一次）<br>③ BM25 双保险（发布方尽力 + Listener 校验补写）<br>④ 存量一律做 id 集合核对<br>⑤ 向量化必须幂等（无 upsert，重复 id 报错）<br>**结构调整**：新增批次 07 任务 7.0（原子完成）；批次 05 收窄为 MySQL 侧优化；批次 08 复用 7.0.11 的 Chroma 枚举能力 | 主 agent |
| 2026-10-06 | **新增「批次 07 任务 7.0 的执行要求」**（本文件第三节目次下）：① 单独派发不与其他任务混合 ② 分段推进、每段自检 ③ **7.0c 必须在 7.0d 之后执行**（编号与依赖顺序不一致）。同时在 `batch-07` 的任务 7.0 开头、7.0c/7.0d 段首、子 agent 指令三处同步标注该顺序约束 | 主 agent |
| 2026-10-06 | **剩余待确认事项全部清零**（11 项 → 0 项待决，仅 #2e 待实测）。其中 **#1 与 #9 已通过直连 MySQL(3308) + ChromaDB(8000) 实测完成**：<br>#1 `kb_chunk_structured` **135 行 / 15 切片 / 仅 HR 域** → 批次 04 灰度具备前提（但只能用 HR 域验证）<br>#9 存量核对 **19 vs 6，需补 13 条，孤儿 0 条** → 采用"保持 collection + 按差集补写"<br>**其余已确认**：#3 Reranker 本地部署、#4 write-to-chromadb 接线为跳过向量化、#5 恢复 admin 直通、#6 保持 collection 按差集补写、#7 tabula-java、#8 保留生效配置项、#10 先删后写幂等、#11 实时聚合<br>**连带改动**：批次 03 新增任务 3.6（恢复 admin 直通）、批次 01 新增 1.2.3b（DLQ 批量语义预留）、批次 06/04/07/10 的前置确认项已填入实测结论 | 主 agent |
| 2026-10-06 | **实测 Embedding 性能基准**（#2e 的 (b) 部分），结论**修正了一次乐观外推**：<br>对等条件下批量端点 vs 当前逐条实现：**8 线程 2.7 倍 / 16 线程（贴近真实配置）1.5 倍**；单线程 11.7 倍**不可用作决策依据**（非对等）。<br>另实测确认 **Ollama 能并行处理并发请求**（8 并发下单条均摊 2173ms→334ms），这解释了为何并发度越高、批量化收益越小。<br>**判据维持不变**（占比 >40% 才改），但补充了按 1.5 倍推算的端到端收益表（省 6.7%~20%）。数据已写入 `progress.md` 4.3 与 batch-05 任务 5.2.5 | 主 agent |
| 2026-10-06 | **批次 01 完成**（问题 01、02、30 + 37 基建）。四任务按 1.0→1.1→1.2→1.3 顺序整体交付：<br>① 测试基建落地（6 个测试类 / 34 条用例，前端 vitest 链路打通）<br>② `DocumentDeleteServiceV2` catch 补 `updateStatus(FAILED)` + `DeleteTaskMapper` 新增 `selectByStatus`/`selectByDocumentId`<br>③ DLQ 链路补齐：`DlqMessage` 新增 6 个 `EVENT_*` 常量、7 处入队方统一改用常量、6 个 `retryXxx` 重试体全部执行实际业务动作、未知类型改为**保留记录不删除**<br>④ `@EnableScheduling` 开启，5 处 `@Scheduled` 全部注册<br>**真实验证**（MySQL 3308 + ChromaDB 8000 + Ollama 均在运行，启动 jar 实测）：调度每 30 秒稳定执行、未知类型记录保留、PARSE 死信真实重放并按 3→9→27 秒退避累计失败次数<br>**3 处范围外改动**（详见批次报告）：`DocumentParseListener`/`DocumentDeleteListener` 的字面量替换（验收标准驱动）+ 上述测试文件<br>**新增风险 R1-R6**（见第六节），其中 R1（DELETE 重试 fire-and-forget 导致退避重置）需批次 08 评估 | 主 agent |
| 2026-10-06 | **批次外修复：ChromaConfig 与 ChromaDB 1.0.0 不兼容**（用户确认后执行）。症状：启动时两条告警（"collection 不存在" + "创建失败 404"）。实测根因有三层：<br>① REST 路径缺 tenant/database 段（ChromaDB 1.0.0 要求 `/api/v2/tenants/{t}/databases/{d}/collections`）；<br>② `.onStatus(is4xxClientError)` 把 400 也误判为"不存在"，掩盖了路径错误；<br>③ 数据实际位于 `default`/`default` 命名空间（`default_tenant`/`default_database` 为空），与 langchain4j `ChromaClientV2` 字节码默认值一致——**数据完好，未丢失**（`enterprise_knowledge`，id `7fbaddfc-…`，space=cosine，dimension=2560，6 条向量，与 4.4 记录一致）。<br>**修复后启动日志**：`已存在 (id=7fbaddfc-…)，跳过创建`（无告警）。新增 `ChromaConfigTest` 3 用例。<br>同批登记 R8（ChromaDB 实际写 `/data`、卷挂在 `/chroma/chroma`，容器重建即丢数据 → 待批次 02）与 R9（`EmbeddingDimensionManager` 计算名 `kb_ollama_ollama` 与实际使用的 `enterprise_knowledge` 不一致 → 属批次 07 任务 7.1） | 主 agent |
| 2026-10-06 | **R8 定案并落入批次 02**（用户确认）：<br>① `batch-02-部署链路.md` 新增**任务 2.4「ChromaDB 数据持久化修正」**——含背景实测数据、"**先备份再重建**"的操作告诫（数据在容器可写层，顺序颠倒即不可恢复）、重建后验证持久化的验收标准，以及**必须在批次 07 之前**的时限约束；同步更新该批次的涵盖问题、涉及文件（`docker-compose.yml` 任务列加 2.4）、批次目标、批次验收、回归验证建议与子 agent 指令<br>② `progress.md` 批次状态表（批次 02 任务数 3→4）、执行清单、任务总数（44→45）、待确认事项 #12 与风险记录 R8 同步更新<br>③ `README.md` 批次总览表同步（涵盖问题加 R8、任务数 3→4）<br>**归入批次 02 的三条依据**：与任务 2.1 共用 `docker-compose.yml`（避免两次改动同一文件）；同属"容器化交付链路可靠"主题；批次 02 位于批次 07 之前，可保护 7.0c 的存量迁移基线 | 主 agent |
| 2026-10-06 | **批次 02 完成**（问题 03、04、05 + R8）。四任务按 2.1 → 2.2 → 2.3 严格顺序交付（2.4 穿插执行，`docker-compose.yml` 一次性合并修改）：<br>① **2.1**（问题 04 **路线 A**）：读写库地址外部化（`${SPRING_DATASOURCE_WRITE_URL:…}`，保留 localhost 默认值）；`application.yml` 保留失效键并加说明注释；compose 键名改为 `SPRING_DATASOURCE_{WRITE,READ}_URL`；**`readReplicaFallbackToPrimary` 从"只打日志"改为装配期连通性探测 + 回退决策**（不可达+true→回退写库；不可达+false→拒绝启动）<br>② **2.2**（问题 03）：Dockerfile 改 `COPY . .` + 完善 `.dockerignore`（构建上下文 615MB→**4.36MB**），根治"加模块忘改 Dockerfile"的结构性诱因；实际 `docker build` 成功（反应堆 13/13、13:51 min）<br>③ **2.3**（问题 05）：4 个查询侧 Service / **13 个方法**补 `@Transactional(readOnly=true)`（全仓库首次落地设计 §8.13.3 约定）<br>④ **2.4**（R8）：卷挂载点 `/chroma/chroma` → **`/data`**；严格「**先备份（47MB，sha256 逐字节校验）再重建**」，并**两次 `--force-recreate`** 验证——collection id `7fbaddfc-…` / dim 2560 / cosine / **6 条向量**全程保持，持久化实测生效<br>**主 agent 独立验收**（不采信子 agent 报告）：重新打包并启动 jar 实测（32.8s 启动、服务级 ERROR 0、调度 4 轮、fallback 日志链完整、HTTP 200/403）；MySQL+Chroma 基线零变化（19 切片 / 11788 行 / 6 向量）；`./mvnw test` 13 模块 BUILD SUCCESS（**54 用例 0 失败**，新增 21 条全绿）<br>**新增测试 21 条**（4 个测试类：路由/切面/Fallback 装配/只读约定守护）<br>**新发现 2 项**：R10（p3 `hikari:` 子块死配置，连接池声明 20 实为 10 —— 待决策归属，见待确认 #13）、R11（`-pl` 必须配 `-am`，本地仓库构件过期陷阱） | 主 agent |
| 2026-10-07 | **批次 03 完成**（问题 41、07、06、08、09、33 断裂 2/3/4、39-B2），并首次启用**独立测试子 agent** 验收流程（用户 2026-10-06 要求，已记入长期记忆）：<br>① **3.1** 唯一 403 处理者 `AbacAccessDeniedHandler`（@Order 最高优先）+ 统一 Result + 审计字段补 `uri`/`method`；过滤器链 403 响应同构<br>② **3.2** `refresh()` 改为 `selectById` 加载完整用户 + status 校验；删除"两分支字面量相同"的错误三元；**硬约束 3 未被违反**<br>③ **3.3** `@PreAuthorize` + 路由级 `hasRole('ADMIN')` 双层；`@JsonIgnore` 阻断口令外泄；补齐 `department`/`title` 筛选（用 LambdaQueryWrapper 等价实现，未扩范围改 Mapper）<br>④ **3.4** `POST /api/auth/revoke` 接线既有但无调用方的方法；前端容错已存在无需改<br>⑤ **3.5** 入口强制 domain（缺域 400/越域 403）+ 检索层 `allowedDomains ∪ department` 独立裁剪（双保险）；**未触碰批次 04 的空候选守卫**<br>⑥ **3.6** 恢复设计原文 admin 直通；**3.6.2 评估结论：不增加防自提权约束**——依据（自编辑敏感字段限制对 admin 无条件生效）经独立验证证实<br>**独立测试子 agent 验证**：A–F 全项通过；修复者关键声明逐条证伪核验通过；全量 104 用例复跑一致；数据基线前后一致、临时数据零残留<br>**新增测试 9 类 / 50 用例**（全量 104）<br>**新发现 4 项**：R12（强制 domain 的前端联动缺口，待决策 #14）、R13（JwtAuthenticationFilter 不校验 tokenType → NPE，既有缺陷）、R14（匿名请求 403 空 body，统一响应未覆盖角落）、R15（admin 可置自己 status=0 自锁） | 主 agent |

---

## 九、最终问题状态表（批次 11 完成后填写）

> 43 个问题的最终结论，用于交付前的完整性检查。

| 编号 | 问题 | 所属批次 | 最终状态 | 说明 |
|---|---|---|---|---|
| 01 | 定时任务全线失活 | 01 | ⬜ | |
| 02 | DLQ 重试链路失效 | 01 | ⬜ | |
| 03 | Dockerfile 模块清单滞后 | 02 | ⬜ | |
| 04 | profile 恒叠加导致连库失效 | 02 | ⬜ | |
| 05 | CQRS 读写分离不生效 | 02 | ⬜ | |
| 06 | 用户列表无鉴权 + 口令外泄 | 03 | ⬜ | |
| 07 | 刷新令牌丢 ABAC | 03 | ⬜ | |
| 08 | 登出接口缺失 | 03 | ⬜ | |
| 09 | Chat 域鉴权未落地 | 03 | ⬜ | |
| 10 | PDF 表格空实现 | 06 | ⬜ | 复盘偏差 1 |
| 11 | PdfParser 流式/内存保护 | 06 | ⬜ | |
| 12 | LLM 结构化过滤不可达 | 04 | ⬜ | 复盘偏差 2 |
| 13 | RAG 聚合查询截断 | 04 | ⬜ | 复盘偏差 3 |
| 14 | Reranker 静默降级 | 10 | ⬜ | |
| 15 | BM25Retriever 未升级 | 07 | ⬜ | |
| 16 | Collection 隔离死代码 | 07 | ⬜ | |
| 17 | Embedding 选型未回填文档且为单点无降级 | 07 | ⬜ | 选型（本地 Ollama + Qwen）属有意决策，非缺陷；处理文档回填 + 失败可见性 |
| 18 | RRF 去重键不一致 | 09 | ⬜ | |
| 19 | 域过滤空集静默跳过 | 04 | ⬜ | |
| 20 | operator 不生效 | 04 | ⬜ | |
| 21 | 大数据源同步性能 | **05 + 07** | ⬜ | 复盘偏差 4；①②③ 在批次 05，④（ChromaDB 批量）在批次 07 的 7.0d |
| 22 | SyncScheduler 死代码 | 05 | ⬜ | |
| 23 | 连接器异常被吞 | 05 | ⬜ | |
| 24 | 增量同步不完整 | 05 | ⬜ | |
| 25 | CharsetDetector 形同虚设 | 09 | ⬜ | |
| 26 | quality_failure 表缺失 | 10 | ⬜ | |
| 27 | 软删切片漏出 | 08 | ⬜ | |
| 28 | 提前置 READY | 07 | ⬜ | **已升级为全链路重构**：双状态机 + 事件驱动（任务 7.0） |
| 29 | 孤儿向量扫描漏检 | 08 | ⬜ | |
| 30 | DeleteTask 失败不落 FAILED | 01 | ⬜ | |
| 31 | 删除假成功 | 08 | ⬜ | |
| 32 | filePath 无消费者 | 08 | ⬜ | |
| 33 | 前后端契约断裂 | 03, 09 | ⬜ | 断裂2/3/4 在 03，断裂1 在 09 |
| 34 | WebSocket 前端有后端无 | 10 | ⬜ | |
| 35 | 质检规则页假数据 | 10 | ⬜ | |
| 36 | 生产 API 路径双前缀 | 09 | ⬜ | |
| 37 | 全仓库零测试 | 01, 11 | ⬜ | 基建在 01，收尾在 11 |
| 38 | 配置项与代码脱节 | 07, 10 | ⬜ | |
| 39 | 设计文档矛盾与脱节 | 11 | ⬜ | |
| 40 | 模块依赖隐式化 | 09 | ⬜ | |
| 41 | 双 Advice 争抢异常 | 03 | ⬜ | |
| 42 | 删除缺少并发保护 | 09 | ⬜ | |
| 43 | ETL 半结构化路径错误 | 09 | ⬜ | |

---

## 十、有意不修项（如有）

> 若某项经决策后确定不修，在此记录原因与后续建议。

| 编号 | 问题 | 不修原因 | 后续建议 |
|---|---|---|---|
| | 待填写 | | |

---

## 更新指引（给主 agent）

每完成一个批次后：

1. 更新「二、批次整体状态」中该批次的状态与完成时间
2. 更新「三、逐步执行清单」中对应任务的 checkbox
3. 更新「四、关键产出物登记」（若该批次有产出）
4. 更新「五、待确认事项」（若有新发现或已确认）
5. 更新「六、风险与回退记录」（若有风险事件）
6. 更新「七、验收证据汇总」
7. 在「八、更新日志」追加一行
8. 批次 11 完成后填写「九、最终问题状态表」与「十、有意不修项」
