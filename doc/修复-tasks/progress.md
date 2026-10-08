# 修复进度记录

> **用途**：主 agent 记录修复进度、验收证据、遗留问题；子 agent 完成后由主 agent 更新
> **创建日期**：2026-10-06
> **最后更新**：2026-10-08（**批次 11 完成 — 11 个批次全部收官**；43 个问题最终状态表已填写）
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
| 04 | 检索过滤 | 20, 19, 12, 13 | 4 | ✅ | 2026-10-07 | 2026-10-07 | 灰度开关默认关闭；N1（重复提取）经**返工 + 复验**修复 |
| 05 | 数据源同步 | 24, 23, 22, 21(①②③) | 3 | ✅ | 2026-10-07 | 2026-10-07 | 已收窄为 MySQL 侧优化；5.3 经**返工 + 复验**（熔断 + 两半自洽 + 恢复通道） |
| 06 | 文档解析 | 10, 11 | 2 | ✅ | 2026-10-07 | 2026-10-07 | tabula 接入 + 流式/内存保护；⚠️ contentType/tableCaption 为"死列"（见 R39） |
| 07 | 向量化链路与索引重构 | 28, 16, 17, 15, 38(部分), 21(④) | 5 | ✅ | 2026-10-07 | 2026-10-07 | 全 5 任务完成（含存量迁移 6→19）；7.5 的 HTTP 不可达见 R27 |
| 08 | 删除链路一致性 | 27, 31, 29, 32, **R24**, **R1** | 5 | ✅ | 2026-10-07 | 2026-10-07 | 含回退风险（已遵守）；**R24 与 R1（批次 01 遗留）均经返工 + 复验闭环** |
| 09 | 独立修复 | 18, 25, 33(断裂1), 36, 40, 42, 43 | 7 | ✅ | 2026-10-07 | 2026-10-07 | 全 7 任务完成；9.1 顺带部分解决 R18；新登记 R34–R38 |
| 10 | 决策类修复 | 14, 26, 34, 35, 38(剩余), **R27** | 5 | ✅ | 2026-10-07 | 2026-10-07 | 全 5 任务完成（+R42）；**Reranker 效果 Hit@1 100% vs 旧 80%** |
| 11 | 文档回填与收尾 | 39, 37(收尾), **R28**, **R30** | 4 | ✅ | 2026-10-08 | 2026-10-08 | 4 任务 + 收尾清单（前端 + 后端杂项 + R48）全部完成 |

**总览统计**

| 指标 | 数值 |
|------|------|
| 批次总数 | 11 |
| 任务总数 | 46（任务级；批次 07 的任务 7.0 内含 18 个子项；批次 02 新增任务 2.4 = R8；批次 03 新增任务 3.6 = 问题 39-B2） |
| 已完成 | **11（全部批次）** |
| 进行中 | 0 |
| 阻塞 | 0 |
| 完成率 | **100%（11/11 批次）** |

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

- [x] 任务 4.1 operator 生效（问题 20，**必须先做**）
- [x] 任务 4.2 域过滤空集守卫（问题 19，**必须先于 4.3**）
- [x] 任务 4.3 LLM 结构化过滤链路（问题 12）
- [x] 任务 4.4 聚合查询不完整（问题 13）
- 批次状态：✅（含一轮返工：N1 重复 LLM 提取）

### 批次 05 · 数据源同步（已收窄）

- [x] 任务 5.1 连接器异常语义 + 增量完整性（问题 24 + 23）
- [x] 任务 5.2 性能改造：**仅 ①JDBC 流式 + ②ETL 批量写入 + ③队列参数化**（问题 21）
- [x] 任务 5.3 定时同步（问题 22，**必须最后做**）
- 批次状态：✅（含一轮返工：熔断 + 两半自洽 + 恢复通道）

### 批次 06 · 文档解析

- [x] 任务 6.1 PDF 表格结构化（问题 10，复盘偏差 1）—— tabula 1.0.5 三层策略；⚠️ 结构化元数据未闭环（R39）
- [x] 任务 6.2 PDF 流式解析与内存保护（问题 11）
- 批次状态：✅（新登记 R39–R41）

### 批次 07 · 向量化链路与索引重构 ★

- [x] **任务 7.0 向量化链路重构（问题 28）—— 最大单项，必须原子完成**
  - [x] 7.0a 双状态机（Chunk + Document + 前端）
  - [x] 7.0b 事件驱动（新事件 + 新 Listener + 旧 Listener 退役）
  - [x] 7.0c 存量数据核对与迁移（id 集合核对）→ **实跑完成：ChromaDB 6 → 19**
  - [x] 7.0d ChromaDB 批量写入与**幂等**
  - [x] 7.0e BM25 增量索引（双保险）
- [x] 任务 7.1 Collection 隔离生效（问题 16）—— 保守策略保护历史数据；E2E 检索命中历史向量
- [x] 任务 7.2 Embedding 选型回填与失败可见（问题 17）—— 含 7.2a 文档回填、整批失败、聚合告警、配置去冗
- [x] 任务 7.3 BM25Retriever v2（问题 15）—— 磁盘索引 + 异步构建 + 分页加载
- [x] 任务 7.4 （已并入 7.0，占位保留）
- [x] 任务 7.5 语义路由开关接线（问题 38 部分）—— ⚠️ 接线完成但 HTTP 入口不可达（见 R27）
- 批次状态：✅（全 5 任务完成并通过独立验证）

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

- [x] 任务 8.1 软删切片不漏出（问题 27）
- [x] 任务 8.2 ChromaRetriever 为空不假成功（问题 31）
- [x] 任务 8.3 孤儿向量扫描（问题 29）—— **不得回退 8.1**（已核验通过）
- [x] 任务 8.4 删除时清理物理文件（问题 32）
- [x] 任务 8.5 DLQ 向量化重试体与新状态机对齐（**R24**，批次 07 独立验证发现，用户确认归入）
- 批次状态：✅（含一轮返工：R1 DELETE 重放环路闭环；PARSE/CHUNK 环路评估后转后续批次，见 R30）

### 批次 09 · 独立修复

- [x] 任务 9.1 RRF 去重键（问题 18）—— 顺带部分解决 R18
- [x] 任务 9.2 CharsetDetector（问题 25）—— ⚠️ 短样本边界实测比声明严重（见 R34）
- [x] 任务 9.3 知识库筛选参数（问题 33 断裂1）
- [x] 任务 9.4 生产 API 路径双前缀（问题 36）—— ⚠️ WS 通道同类残留（见 R36）
- [x] 任务 9.5 模块依赖显式化（问题 40）
- [x] 任务 9.6 删除并发与重复保护（问题 42）
- [x] 任务 9.7 ETL 半结构化路径（问题 43）
- 批次状态：✅（9.6.3 可选未做——范围外；新登记 R34–R38）

### 批次 10 · 决策类修复

- [x] 任务 10.1 质检规则 CRUD 与动态加载（问题 35）—— 实时查库、下次质检即生效
- [x] 任务 10.2 后端 STOMP 服务端（问题 34）—— `/ws/**` 鉴权 + 用户隔离 + 运维频道仅 admin
- [x] 任务 10.3 接入真实交叉编码器（问题 14）—— **效果 Hit@1 20/20（vs 旧启发式 16/20）**
- [x] 任务 10.4 质检失败明细方案 B（问题 26）—— recordIndex 修复 + 文档 6 处回填
- [x] 任务 10.5 剩余死配置接线（问题 38 收尾 + **R27**）—— 含防漂移检查
- 批次状态：✅（全 5 任务 + R42 追加修复；新登记 R46–R47）

### 批次 11 · 文档回填与收尾

- [x] 任务 11.1 设计文档回填（问题 39）—— A/B/C 三类全部处理（+1367/−527 行），抽查脚本 0 缺失
- [x] 任务 11.2 测试体系收尾（问题 37）—— 后端 661 用例 / 前端 73 / **playwright E2E 2**；CI 建立
- [x] 任务 11.3 Collection 解析的向量数防线（**R28**）—— 规则 3 升级为"存在性 + 向量条数"；保守护栏未削弱
- [x] 任务 11.4 PARSE/CHUNK 重放环路闭合（**R30**，顺带 **R31**）—— 同步入口方案；**R30 的最后残余（R1/R24 之后）已闭环**
- 批次状态：✅（**11.1–11.4 + 前端收尾 + 后端杂项收尾（10 项）+ R48 全部完成**；43 个问题最终状态表已填写）

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
| （批次 04 新增） | rag 11 类：`FilterConditionExtractorTest` 23 / `StructuredFilterServiceOperatorTest` 17 / `AggregationQueryServiceTest` 8 / `ChatQueryServiceAggregationTest` 8 / `QueryIntentClassifierTest` 8 / `ChunkStructuredFilterMapperLiveDbTest` 6 / `ContextTokenManagerAggregationTest` 5 / `ChatQueryServiceFilterConditionTest` 5 / `ChatQueryServiceExtractionOnceTest` 5 / `HybridRetrieverDomainFilterTest` 4 / `RetrievalConfigConsistencyTest` 3 | 92 | operator 开闭区间、空集守卫、提取器与灰度降级、意图分流与聚合路径、聚合权限兜底、实库 SQL 冒烟 |
| （批次 05 新增） | datasource 7 类（`SyncSchedulerTest` 19 / `JdbcConnectorTest` 14 / `RestApiConnectorTest` 9 / `JdbcConnectorLiveDbTest` 5 / `DataSourceServiceScheduleRecoveryTest` 5 / `DataSourceServiceSingleFlightTest` 4 / `DataSourceServiceSyncStatusTest` 4）+ knowledge 1 类（`DataSyncEtlListenerBatchWriteTest` 6）+ rag 3 类（`OllamaEmbeddingProviderTest` 6 / `BatchEmbeddingServiceTest` 5 / `ChunkMapperBatchInsertLiveDbTest` 4） | 81 | 失败语义与 URL 脱敏、多表增量、REST 翻页/真游标/maxPages、批量 SQL 形态、流式 fetchSize、队列参数化、调度注册/熔断/单飞锁/恢复通道 |
| （批次 03-补 · 前端） | `auth.spec.ts` 14 / `index.spec.ts` 6 | 20 | 域选择纯函数（含 admin 回退）、axios 403 分支与回归守卫 |
| （批次外 R7） | `ChromaConfigTest` | 3 | ChromaDB 1.0.0 路径与 404 判定 |
| agent-qr-web-frontend | `format.spec.ts` | 4 | vitest 链路冒烟 |

> 全量基线：**277 用例**（后端 13 模块，截至批次 05，经独立测试子 agent 复跑确认：BUILD SUCCESS / 0 失败 / 0 错误 / 0 跳过）；前端 vitest **24 用例**。
>
> ⚠️ **计数更正**：批次 04 的基线记录 **193 实为 196**（漏算 `AgentQrApplicationTest` 3 例；经批次 05 独立验证者核实：批次 03 的 104 + 批次 04 新增 92 = 196，与逐类求和吻合）。本表已以此为准。

**全量基线（截至批次 11.2，2026-10-08，**测试体系收尾的最终清单**）**

> 后端 **661 用例 / 104 测试类 / 12 个模块有测试**（全绿：0 失败 0 错误 0 跳过）；前端 vitest **73**；前端 playwright E2E **2**。

| 模块 | 用例 | 模块 | 用例 |
|---|---|---|---|
| agent-qr-common | 17 | agent-qr-knowledge | 125 |
| agent-qr-user | 5 | agent-qr-statistics | 15 |
| agent-qr-auth | 28 | agent-qr-compensation | 27 |
| agent-qr-datasource | 60 | agent-qr-data-quality | 73 |
| agent-qr-catalog | 11 | agent-qr-web | 96 |
| agent-qr-rag | 193 | agent-qr-etl | 11 |

> **opt-in 实库/性能测试**（默认不执行，需显式开关）：`-Dagent-qr.live.delete`（删除链路 E2E）、`-Dagent-qr.live.migration`（存量核对/迁移，会重跑 19 条向量化）、`-Dagent-qr.live.dlq`（DLQ 重放真库）、`-Dagent-qr.perf`（性能冒烟）。
> **CI**：`.github/workflows/ci.yml`（PR 阻塞：后端测试 + 前端单测 + playwright E2E；**不依赖 DB/Chroma/LLM**，实库用例按约定 skip）+ `perf-smoke.yml`（仅手动/定时，不阻塞 PR）。⚠️ **两个 workflow 尚未在真实 GitHub Runner 上执行过**。

### 4.2 事件契约清单（批次 01 任务 1.2.1/1.2.3b 定稿；**批次 07 任务 7.0b 将扩展批量事件**）

> 常量定义于 `agent-qr-common/.../dlq/entity/DlqMessage.java`，入队方与重试方共用。

| 事件类型 | eventType 常量值 | 入队位置 | 重试体 | payload 契约与备注 |
|---|---|---|---|---|
| 文档解析 | `EVENT_PARSE` = `PARSE` | `DocumentParseListener.handleDocumentUploaded` catch | `DlqRetryScheduler.retryParse` | `{"documentId":N,"filePath":"...","fileType":"..."}`；filePath 为**未转义**原始路径，解析器采用宽松正则（兼容 Windows 反斜杠） |
| 切片 | `EVENT_CHUNK` = `CHUNK` | **（批次 07 起）**`ChunkEmbeddingBatchListener`（切片阶段失败） | `retryChunk` | `{"documentId":N}`；重试体从 `kb_document` 取回 filePath/fileType 重新解析，并先软删残留切片避免重复；**重放经 `DocumentParsedEvent` 由新 Listener 消费（勿断）** |
| 向量化 | `EVENT_EMBED` = `EMBED` | **（批次 07 起）**`ChunkEmbeddingBatchListener`（向量化失败，**每批一条**）、`DlqRetryScheduler`（重试体自入队） | `retryEmbed` | **批量语义**：`{chunkId, documentId|datasourceId+batchId, chunkCount}`，**每批一条而非逐条**；批次标识优先（documentId > datasourceId > chunkId）整批重放 |
| 物理删除 | `EVENT_DELETE` = `DELETE` | `DocumentDeleteListener`、`DocumentDeleteServiceV2` catch | `retryDelete` | 兼容两种 chromaIds 形态：逗号串 `"a,b"` 与 JSON 数组 `["a","b"]`；含 DONE 幂等判断 |
| ETL | `EVENT_ETL` = `ETL` | `DataSyncEtlListener`（2 处） | `retryEtl` | `{"datasourceId":N,"batchId":"...","recordCount":N}`；⚠️ **缺 passedData 无法重放**，重试体显式标记失败并留 TODO（批次 07/10 需要"可重放批次快照"） |
| ChromaDB 写入 | `EVENT_CHROMA_WRITE` = `CHROMA_WRITE` | **（批次 07 起）**`ChunkEmbeddingBatchListener`（Chroma 写入失败，每批一条）、`DlqRetryScheduler`（重试体自入队） | `retryChromaWrite` | **批量语义**（同 EMBED）；⚠️ 重试体仍走单条 `add()`（随机 UUID、无 removeAll）——见风险 R24 |
| 未知类型（兜底） | — | — | `handleUnknownEventType` | **保留记录不删除**，每轮扫描持续 error 告警直至人工处理（原实现为静默删除） |
| **批量向量化事件（批次 07 新增）** | —（**Spring 应用事件，非 DLQ 类型**） | 文档链：`ChunkEmbeddingBatchListener.handleDocumentParsed`（MySQL 批量写入后）；同步链：`DataSyncEtlListener.handleDataQualityPassed` | `ChunkEmbeddingBatchListener.handleChunksBatchCreated` | `ChunksBatchCreatedEvent`：`{documentId}` 或 `{datasourceId, syncBatchId}`，**只带标识**；Listener 从 MySQL **keyset 分页读取（200/批）**；**7.0.10 决定不新增 `EMBED_BATCH` 类型**（理由见风险 R25） |

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

**迁移执行结果（2026-10-07，批次 07 任务 7.0c 实跑完成）**

> 依据本表差集，按"保持 collection 不变、按差集补写"策略执行迁移；**执行前已完成 7.0d 幂等改造**（先决条件）。

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| ChromaDB 向量 | 6 | **19**（与 MySQL 19 条 id 集合**逐条一致**） |
| 仅在 MySQL（待补） | 13 | **0** |
| 仅在 Chroma（孤儿） | 0 | **0** |
| collection id / dimension / space | 7fbaddfc-… / 2560 / cosine | **完全相同（未重建）** |
| 向量 id 规则 | UUID | 19/19 = `UUIDv3("agent-qr-chunk-"+chunkId)`（确定性，Python 复算命中） |
| `kb_chunk.status` | 混合 | 19 条全部 `READY`，`chroma_id` 全为 UUID |

**该结果已由独立测试子 agent 逐条复核**（含真实全量重跑无 `DuplicateIDError`）。

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

### 4.8 本地 Reranker 服务部署事实（2026-10-07，**批次 10 的前置**）

| 项 | 值 |
|---|---|
| 方案 | **TEI（text-embeddings-inference）1.9.4 CPU 版**，Docker 容器 `agent-qr-reranker`（`restart: unless-stopped`） |
| 镜像 | `ghcr.nju.edu.cn/huggingface/text-embeddings-inference:cpu-latest`（ghcr.io 的国内镜像，同一 image ID） |
| 模型 | `BAAI/bge-reranker-v2-m3` —— **ModelScope 预下载**至卷 `agent-qr-reranker-data` 的 `/data/bge-reranker-v2-m3`（2.29GB）；**运行时无网络依赖** |
| 端口 | **8080** → 容器 80（主 agent 已独立复核：`score 0.9989 vs 0.000016`、`/health` 200） |
| **端点** | `POST http://localhost:8080/rerank`，请求体 `{"query":"...","texts":["..."]}` |
| **响应** | `[{"index":0,"score":0.9989},...]` —— **已按 score 降序**，`index` 为输入数组的**原始下标** |
| 关键限制 | ⚠️ `top_n` **被忽略**（始终返回全部，Java 侧自行截断）；单请求最多 **32** 条 texts；单对 2048 token（超长**静默截断**）；请求体上限 2MB |
| 时延实测（CPU fp32） | 单对 0.11–0.19s；5 条 0.41s；**30 条 2.1s**；接近 2048 token 的长文本单条约 11.5s → **建议发送前把文档预截断到 512–1024 字符**；超时建议：连接 5s / 读取 30s |
| 健康检查 | `GET /health`（200 空体）、`GET /info`（模型元数据） |
| 内存占用 | 稳定 3.1–3.2GiB（Docker VM 上限 7.65GiB） |

**部署时踩过的坑（已解决，供维护参考）**：
1. **ghcr.io 拉取挂起**（国内网络，连上但零吞吐）→ 改用南大镜像 `ghcr.nju.edu.cn`（同一 image ID）
2. **TEI 内置模型下载不可用**（huggingface.co 超时；hf-mirror.com 的 307 跳转缺 `Content-Range` 头，TEI 的 Rust hf-hub 不兼容）→ **ModelScope 预下载**后以本地路径 `--model-id /data/bge-reranker-v2-m3` 启动
3. **首次启动 OOM**（fp32 权重 2.27GB + 默认 `max-batch-tokens=16384` 超出 7.65GB 上限）→ 降至 **2048** 后稳定（若需更长输入可试 4096，或调整 `.wslconfig` 内存）
4. Git Bash 下中文 JSON 报 `invalid unicode code point`（shell 以 GBK 发送）→ 用 UTF-8 文件 + `--data-binary @file`；**Java 后端以 UTF-8 发送不受影响**

> `docker-compose.yml` 已同步登记 `reranker` 服务，**卷标 `external: true`**（卷已存在且含预置模型；若不加 external，compose 会新建空卷导致模型缺失）。

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
| 14 | R12：批次 03 强制 domain 后的**前端联动缺口**（域选择器默认「全部域」→ 400 / axios 对 403 显示「网络连接失败」/ SSE 缺域静默） | ✅ **已决策并完成** | 用户选择「立即补前端小修复」（2026-10-07）。已实施并经独立测试子 agent 验证通过、已提交（`2d06721`）。**遗留 3 项**见风险 R19（不阻塞，待后续处理） |

---

## 六、风险与回退记录

> 执行中发现的实际风险、回退操作、以及对其他批次的影响。

| # | 日期 | 批次 | 事件 | 影响 | 处理 |
|---|---|---|---|---|---|
| R1 | 2026-10-06 | 01 | DELETE 重试经 `DocumentDeleteServiceV2.asyncPhysicalDelete`（`@Async` fire-and-forget）提交，提交即标记 DLQ 成功 | 若异步删除再次失败，`asyncPhysicalDelete` 会创建新的 DeleteTask 并**重新入队新消息**（退避计数从 0 重置）；持续失败时将无限重试、`delete_task` 表持续增长，而非 4 次后进 DEAD | 按 batch-01 指定实现（"调用 documentDeleteServiceV2 的实际删除方法"）。**建议批次 08 评估改为同步语义**（`ChromaRetriever.deleteByIds` 本身失败会抛异常，可直接驱动 DLQ 退避） → ✅ **已于批次 08 闭环**（2026-10-07）：独立验证复现该环路且指出 8.2 会放大它 → 打回返工：新增 **`retryPhysicalDelete`**（同步、失败上抛、不自行入队）供重放专用，`retryDelete` 改用它；主链路 `asyncPhysicalDelete` 语义不变。复验（真实 MySQL 落库）：**死信行数恒为 1**、退避 9/27/81s → DEAD |
| R2 | 2026-10-06 | 01 | EMBED/CHUNK 重放按批次标识整批执行，可能重复写入切片/向量 | `ChromaEmbeddingStore` 无 upsert，重复写入会产生新的 UUID 记录（检索出现重复） | 幂等由批次 07 任务 7.0d（写入前 `removeAll`）解决；`retryChunk` 已先 `softDeleteByDocumentId` 降低切片重复 |
| R3 | 2026-10-06 | 01 | 入队 payload 由 `String.format` 拼接，**Windows 路径反斜杠未转义**，payload 非法 JSON | 严格 JSON 解析器会失败 | 重试体采用**宽松正则解析**规避（已验证可解析 `C:\uploads\a.pdf`）；建议批次 07 统一改用 Jackson 序列化 |
| R4 | 2026-10-06 | 01 | ETL 事件 payload 缺 `passedData`，无法重放 | `retryEtl` 只能显式标记失败，4 次退避后进 DEAD（不再静默丢失，但无法自愈） | 已留明确 TODO；批次 07/10 需引入"可重放的批次快照" |
| R5 | 2026-10-06 | 01 | `Result.success(String)` 与 `success(T data)` 重载歧义（T=String 时命中 message 重载，data 为 null） | 期望携带 String 数据的调用方会静默拿到 null | 非批次 01 范围（既有 API 缺陷）；已在 `ResultTest` 锁定行为防回归，**建议后续单独修复**（如需可加 `successData(T)` 或调整重载） |
| R6 | 2026-10-06 | 01 | 前端 jsdom 测试环境因依赖不兼容不可用（`html-encoding-sniffer` → `@exodus/bytes` 触发 `ERR_REQUIRE_ESM`） | 需要 DOM 的组件测试无法运行 | 纯函数测试用 `// @vitest-environment node` 规避；**归批次 11 任务 11.2 收尾** |
| R7 | 2026-10-06 | 01（**批次外，已修复**） | **`ChromaConfig` 的 collection 检查/创建 REST 路径缺少 tenant/database 段**，在 ChromaDB 1.0.0 上返回 404/400；且 `.onStatus(is4xxClientError)` 把 400 一并误判为"collection 不存在" | ① 每次启动打印"不存在 + 创建失败"两条告警；② **"确保 cosine" 的防护静默失效**——若 collection 被重建，将由 langchain4j 以默认 **L2** 创建（实测 ChromaDB 1.0.0 默认 `space=l2`），检索效果下降且无任何告警 | **已修复**（2026-10-06 经确认，批次外）：① 路径补全为 `/api/v2/tenants/{tenant}/databases/{database}/collections`；② 命名空间固定 `default`/`default`（与 langchain4j `ChromaClientV2` 字节码默认值一致，避免与读写命名空间分裂）；③ 404 判定收窄为仅 404，其余 4xx 向上抛出告警。新增 `ChromaConfigTest`（3 用例）；启动验证：由"不存在 + 创建失败"变为"**已存在 (id=7fbaddfc-4cd8-4651-b987-827e81e31257)，跳过创建**" |
| R8 | 2026-10-06 | 01（**批次外**）→ **已转入批次 02 任务 2.4** | **ChromaDB 数据目录与挂载卷不匹配**：docker-compose.yml 设 `PERSIST_DIRECTORY=/chroma/chroma` 并把卷挂载于该路径，但 ChromaDB 1.0.0（Rust 版）实际写入 **`/data`**（47MB 数据在此，`/chroma/chroma` 仅 4KB） | **容器重建/删除即丢失全部向量数据**（当前 6 条历史向量 + collection 配置） | **未修复，已确认归入批次 02（部署链路）**为任务 2.4（2026-10-06 决策）。归入理由：① 与任务 2.1 共用 `docker-compose.yml`（避免两次改动同一文件）；② 同属"容器化交付链路可靠"主题；③ **须在批次 07 之前完成**——7.0c 存量迁移依赖当前 6 条向量，容器重建会使 4.4 的核对基线作废 |
| R9 | 2026-10-06 | 01（观察，属已知问题 16） | 启动日志显示 `EmbeddingDimensionManager` 计算出的 collection 名为 `kb_ollama_ollama`，而实际读写使用 `enterprise_knowledge`——两者不一致 | 印证问题 16（Collection 隔离为死代码）：维度管理器计算的名字未被任何读写路径采用 | 属**批次 07 任务 7.1（Collection 隔离生效）**范围，本次不改 |
| R10 | 2026-10-06 | 02（**新发现，未修**） | **`application-p3.yml` 的 `hikari:` 子块是死配置**（实测确认）：`HikariDataSource` 无嵌套 `hikari` 属性，`spring.datasource.write.hikari.maximum-pool-size: 20` 等键被**静默忽略**，实测绑定为 Hikari 默认值（maxPoolSize=10 / minIdle=-1）。声明的连接池参数从未生效 | 连接池容量不符预期（声明 20 实为 10）；属批次 02 主题（CQRS 配置副作用）但**不在 batch-02 任务清单内**，按 README 第八节第 4 条**上报不自行决策** | ⚠️ **主 agent 复核发现遗漏**：批次 10 的 10.5 清单**未包含本项**，且 10.5.5 的防漂移检查（扫"有无读取点"）**检测不到本项**（本项是"键被绑定但 Hikari 不认识嵌套层"，非"无读取点"）→ **已补登记至批次 11 收尾清单**（2026-10-07）。正确写法：`spring.datasource.write.maximum-pool-size`（去掉嵌套 `hikari` 层级）。不修不影响任何批次前置 |
| R11 | 2026-10-06 | 02（操作陷阱） | 本地 `~/.m2` 中已安装的 `org.example:agent-qr-*` 构件**早于批次 01**，因此 `./mvnw -pl <module> test` 会因找不到 `DlqMessage.EVENT_*` 等新常量而编译失败（实测） | 单模块测试命令会误报失败，浪费排查时间 | **规避**：一律用 `./mvnw -pl <module> -am test`（-am 让 reactor 以源码构建依赖模块），或先 `./mvnw install -DskipTests` 刷新本地仓库。已登记供各批次参考 |
| R12 | 2026-10-07 | 03（**独立验证发现，待决策**） | **强制 domain 校验的前端联动缺口**：① `ChatInput.vue` 域选择器默认「全部域」→ 不传 domain → 后端 400；② `api/index.ts` axios 拦截器对 HTTP 403 只提示「网络连接失败」；③ 缺域 400 走 HTTP 200+body code=400，而前端 SSE（fetchEventSource）只看 HTTP 状态 → **静默无响应** | 前端用户视角：问答入口不可用/无反馈。后端策略本身系按 batch-03 建议执行（强制 domain），副作用在前端侧 | **待用户决策**（见「待确认事项 #14」）：补前端小修复 / 归入后续批次 |
| R13 | 2026-10-07 | 03（**既有缺陷**，独立验证确认） | `JwtAuthenticationFilter` 不校验 `tokenType`：用 Refresh Token 充当 Bearer 时 `principal.getRole()` 为 null → `getRole().toUpperCase()` 抛 NPE → 客户端收到 403 空 body（应为 401） | 误用凭证时服务端 ERROR 日志 + 语义错误的 403；**非批次 03 引入** | 建议单独立项：按 `tokenType` 显式拒绝 Refresh Token。**11.2 再次证实并补充**：全仓库**无任何地方消费 `tokenType` claim**（只写不读）；已由 `JwtUtilTest.refreshToken_shouldCarryIdentityOnly` 固化为可执行证据 |
| R14 | 2026-10-07 | 03（独立验证发现） | **统一 403 响应的未覆盖角落**：匿名请求走 Spring 默认 `Http403ForbiddenEntryPoint`（SecurityConfig 只配了 accessDeniedHandler，未配 authenticationEntryPoint）→ HTTP 403 + **0 字节空 body**，无统一 Result | 未认证请求的响应结构与 3.1「统一 Result」目标不一致（实测：匿名 `GET /api/admin/users`、无 Token `POST /api/auth/revoke`） | 建议后续补 `authenticationEntryPoint`（401+Result）；不阻塞批次 04 |
| R15 | 2026-10-07 | 03（独立验证发现） | `PUT /api/admin/users/{id}/status` **无自保护**：admin 可把自己 `status` 置 0（实测 HTTP 200 且落库）→ 自锁 | 3.6.2「防自提权评估」未覆盖的同类面（自锁而非提权） | 建议随 R13/R14 一并评估处理 |
| R16 | 2026-10-07 | 03-补（**并发操作事故苗头，已核实无损失**） | 前端修复子 agent 为取类型检查基线，在**另一子 agent（批次 04）正在并发写入工作区**时执行了 `git stash push -u` / `git stash pop`（2 次） | 理论上可将并发方的未提交改动卷入 stash 或造成覆盖/冲突 | 事后核查：**stash 列表为空、无冲突标记、批次 04 全部改动文件完整**，未造成损失。**教训：并行子 agent 期间，任何 agent 不得执行 `git stash` / `checkout` / `reset` 等影响全局工作区的写操作**——后续派发指令均已加入该禁令；需要"改动前"基线时改用只读的 `git show HEAD:<file>` |
| R17 | 2026-10-07 | 04（**独立验证发现 → 返工 → 复验通过**） | 修复者声称"同一问题 LLM 提取 2 次已修复为 1 次"，但独立验证用应用日志证明**仅聚合命中时成立**：降级路径（聚合不可用 → 语义路径）仍提取 2 次；其单测因 mock 掉 `AggregationQueryService` 而覆盖不到 | 每次"列举类但无结构化条件"的提问多付 1 次 LLM 调用（0.4–0.9s，远程 DeepSeek）；功能正确性不受影响 | **已返工修复**（改为"调用方提取一次、向下传递"）+ **复验通过**：两态（开关开/关）× 两链路（同步/流式）× 两分支（聚合命中/降级）提取次数均为 1；新增 `ChatQueryServiceExtractionOnceTest` 5 条（spy 真实 extractor，覆盖降级路径）；rag 99 → 103 |
| R18 | 2026-10-07 | 04（新发现，**未处理**） | **聚合路径 `sources` 契约与语义路径口径不一致**：`documentId` = chunkId 字符串（如 `"7387"`）而非 ChromaDB UUID；`documentTitle` 回退为 `"chunk-7387"`；`similarity` 恒 1.0 | 前端若用 `documentId` 做引用/跳转会出现两种口径 | 建议后续统一或显式区分（归批次 11 或单独立项）；**不阻塞批次 05** |
| R19 | 2026-10-07 | 03-补（独立验证发现，**未处理**） | 前端修复后的 3 项遗留：① `chatApi.ask()` 是**无 domain 的死代码路径**（全仓无调用点，启用必失败）② **SSE 403 提示缺口**（`fetchEventSource` 不经 axios 拦截器，越域显示"连接异常"而非"权限不足"）③ `fetchUserInfo()` 无调用点（刷新后未授权域提示可能误导，fail-safe 不发空域请求） | 均为体验/健壮性问题，不影响当前功能 | 建议归批次 11 收尾统一处理 |
| R20 | 2026-10-07 | 05（**独立验证发现，未处理**） | **REST 重放最后一页 × 去重阻断阈值的交互**：`DataQualityService:93-98` 中 `blocked` 时 `passCount=0` 且不发布 `DataQualityPassedEvent` —— 重放页会拉低 passRate，**可能把同一批中真正的新数据一并判为 blocked**。实测：100 重复 + 10 新增 → rate=0.09 → blocked=true | 新数据**延迟一个周期**入库（非永久丢失：下轮从同一游标重取时不再重复）；**非返工引入**（修复前 REST 走"永远全量"，重放比例更高） | 建议登记至批次 07/11：评估"重放页是否应从 passRate 分母剔除"；**批次 07 重构 ETL 时不要移除 `record_hash` 去重**（它是这条链路唯一的重复护栏） |
| R21 | 2026-10-07 | 05（低，设计取舍已接受） | 熔断只计**连续**失败（成功 1 次即清零）→ "失败 4 次 / 成功 1 次"的抖动源永不熔断，仍持续产生 FAILED 记录（约 80% 的跳） | 与"防重试风暴"目标存在残留差距，但有界 | 可接受（`max-consecutive-failures` 已参数化）；复验者建议的"连续 N 次 **或** 距上次失败超 T"双阈值可作后续优化项 |
| R22 | 2026-10-07 | 05（**范围外改动登记，已接受**） | 两处范围外改动（batch-05 正文要求 vs 文件清单不一致）：① `S3Connector.java` **+4/−0**（两个 catch 补 `SyncResult.failure`；5.1.1 正文要求"三个连接器"）② `ChunkStructuredMapper.java` **+21/−0**（新增 `insertBatch`；5.2.2 正文点名该处逐条插入，实测占 MySQL 写入 **83%**） | 独立验证者逐行核实：**两者均为达标所必需且最小化**（纯新增，对既有调用方零影响）；`S3Connector` 的 `lastModified` 增量逻辑一字未改 | **已接受**（同批次 01 先例）。批次 05 文件的「涉及文件」清单存在遗漏，后续复核以此条为准 |
| R23 | 2026-10-07 | 05（遗留，**未处理**） | 前端 `cronExpression` **回显断裂**：写入已用 `@JsonAlias` 打通，但响应输出 `syncCron`、不含 `cronExpression`，`DataSourceFormDialog.vue` 的 `form.cronExpression = props.editData.cronExpression \|\| ''` 得到空串；`types/index.ts:185` 的类型声明与实际不符 | 编辑数据源时调度 Cron 输入框回显为空 | 需前端 1 行改动（改读 `syncCron`）+ 类型更新；建议归批次 11 或前端小任务 |
| R24 | 2026-10-07 | 07-7.0（**独立验证发现，跨批次 · 高，未处理**） | **`DlqRetryScheduler` 的向量化重试体与新状态机/幂等方案三处不一致**：① 重放只写 `chroma_id`、**从不回写 chunk.status** → 走 DLQ 恢复的切片**永久停在 `INDEXED`**（改造前因默认值 READY 掩盖了此问题），文档聚合恒为"部分就绪" ② 重放走单条 `chromaEmbeddingStore.add()`（**随机 UUID、无 `removeAll`**）→ 不报错但**静默产生重复/孤儿向量**并覆盖 `chroma_id`（破坏 UUIDv3 确定性 id 方案）③ `resolveChunks` 优先按 documentId 取 `selectByDocumentId`（**不过滤 status，含已 READY 切片**）→ 一次重放会把整文档切片重灌一遍 | 三者叠加：真实 DLQ 重放时同时造成"**静默孤儿向量**"（破坏批次 08 依赖的 id 集合一致性）与"**文档永久停在部分就绪**" | ✅ **已决策并登记**（2026-10-07，用户选择"归入批次 08"）：已正式登记为 **batch-08 新增任务 8.5**——batch-08 文件已同步更新（涵盖问题、涉及文件表加 `DlqRetryScheduler.java`、批次内顺序改为 8.1→8.5、补充测试/验收/回归建议/子 agent 指令） |
| R25 | 2026-10-07 | 07-7.0（低，措辞/判断，未处理） | 三处需修正的表述：① `DocumentQueryService.deriveStatus` 的"否则前端会停止轮询"理由**与实际前端代码矛盾**（`PROCESSING_STATUSES` 已含 INDEXED，轮询不会停）——行为可辩护，理由应改为"在途优先" ② 7.0.10 的"批量语义已具备"只对一半（写入侧仍是逐条 `add`，见 R24②） ③ 修复者把 DLQ 重放失败模式误述为"撞 `DuplicateIDError`"（实为静默重复） | 文档/注释瑕疵，不影响功能 | 建议随 R24 一并修正 |
| R26 | 2026-10-07 | 07-7.0（低，已由主 agent 处置） | ① 实库缺 `idx_status`（schema 文件已声明但未重放，`status <> 'READY'` 高频查询无索引支撑）② `/api/knowledge/documents/{id}/status` 返回 `data:null`（既有 `Result.success(String)` 重载问题，即 R5） | ① 性能项 ② 既有缺陷，对 `INDEXED` 状态可观测性有影响 | ① **已由主 agent 手工补建实库索引**（schema 文件保持幂等）② 转批次 11 随 R5 一并处理 |
| R27 | 2026-10-07 | 07-7.5（**独立验证发现，中，未处理**） | **`agent-qr.routing.mode` 已接线但生产路径不可达**：`ChatController.requireDomain()` 强制非空（批次 03 的越权防护决策）→ `resolveRouting()` 在 domain 非空时先 `return pinned` → **模式 switch 永不执行**（实测不带 domain 请求返回 400） | 该键"有日志、有单测，但对用户可见行为仍是死的"——与问题 38 要消除的"误导性配置"只是换了形式 | ✅ **已决策并登记**（2026-10-07，用户选择"归批次 10.5"）：已登记为 **batch-10 任务 10.5.6**（含现状、推荐做法"保留接线 + 明确标注"、**禁止放宽 domain 强制**的告诫、以及 `similarity-threshold`/`top-k` 的顺带核查）——batch-10 文件已同步更新 |
| R28 | 2026-10-07 | 07-7.1（**独立验证发现，中，未处理**） | **"存在性判定不含向量数"的反向风险**：隔离名与既有名**同时存在**时一律优先隔离名（规则 3），不看向量数。若隔离 Collection 被外部/历史操作创建为空，启动后会**静默切到隔离名 → 历史向量立即不可检索**——恰是该类 javadoc 声明"绝不允许"的场景 | 当前环境**不会触发**（隔离名 `kb_ollama_qwen3-embedding-4b` 尚不存在）；属"保守方向"的潜在缺陷 | ✅ **已决策并登记**（2026-10-07，用户选择"归批次 11"）：已登记为 **batch-11 任务 11.3「Collection 解析的向量数防线」**（含 3 条测试与"不得削弱保守护栏"的禁止事项）——batch-11 文件已同步更新 |
| R29 | 2026-10-07 | 07（低，登记） | ① BM25 分页每页重复查询活跃数据源（约 12 次额外小表查询；构建期活跃集合变化会让过滤口径在页间漂移）② 设计文档 §6.0 / §6.2.5.2 仍写"Lucene 内存索引 / @PostConstruct"（与 7.3 实现不符，属已知延后项）③ 顺带观察：`agent-qr.routing.similarity-threshold` / `top-k` 疑似仍无读取点（未在本批任务范围） | 性能/文档一致性，无功能缺陷 | ②③ 建议在**批次 11 清单**显式登记；① 可作后续优化项 |
| R30 | 2026-10-07 | 08（**独立验证发现，待归属**） | **PARSE/CHUNK 重放存在同类无界环路**（R1/R24 的最后残余）：`retryParse`/`retryChunk` → `publishEvent(DocumentParsedEvent)` → `@Async` 的 `ChunkEmbeddingBatchListener.handleDocumentParsed` 失败时 `enqueue(EVENT_CHUNK, …, retryCount=0)` → 链式重放永不终止。**评估结论**：在"不引入范围外改动或逻辑重复"的前提下无法在 `DlqRetryScheduler` 侧闭合（闸门/删后继消息是半成品，反把问题藏起来）；可行方案需把 `ChunkEmbeddingBatchListener` 纳入清单（加同步入口 / 事件携带"DLQ 重放"标记） | 确定性切片失败（解析正常、切片/入库异常）时死信表无界增长 | ✅ **已决策并登记**（2026-10-07，用户选择"归批次 11 任务 11.4"）：batch-11 已新增任务 11.4（**同步入口方案** + 2 条测试 + 11.4.3 顺带修正 R31 的退避 off-by-one）；`ChunkEmbeddingBatchListener` / `DlqRetryScheduler` / `DeadLetterQueue` 已纳入涉及文件 |
| R31 | 2026-10-07 | 08（次要，登记） | `DeadLetterQueue` 退避 **off-by-one**（独立实测确认）：`updateRetryResult` 传入 1 基 `newRetryCount`，首次重试实际等 **9s** 而非类注释的 3s；`calcBackoffSeconds(0)=3s` 在重试路径是死值（3s 只出现在 `enqueue`） | 实际退避序列 9→27→81→DEAD（少一跳）；可观测性/文档一致性 | 建议随 R30 一并修正（`DeadLetterQueue` 非批次 08 文件）；`DlqDeleteReplayBackoffTest` 的类注释也需同步改为 9/27/81 |
| R32 | 2026-10-07 | 08（**范围外发现，待归属**） | `DocumentMapper.selectTypeDistribution` **同类软删缺陷**：`SELECT file_type, COUNT(*) FROM kb_document GROUP BY file_type` 未排除软删文档，由 `StatisticsQueryService:79` 消费 → **统计面板「文档类型分布」把已删文档一起计数**（实测：5 篇中 4 篇已删） | 对外可见的统计失真 | 已由 `DOCUMENT_MAPPER_KNOWN_GAPS` 测试护栏登记（新增未过滤查询会红灯）；**待用户决策归属**（建议批次 11） |
| R33 | 2026-10-07 | 08（次要点，登记） | ① DEAD 时日志 `retryCount=4` 与库内 `retry_count=3` 不一致（DEAD 走 `updateStatus` 不写 retry_count）② 同步重放把向量删除搬到调度线程（Chroma 挂起至超时 30s 时会拖后同轮其它死信，有界）③ `DuplicateCleanupScanner` 仍按"请求条数"计 `chromaCleaned`（未用新返回值）④ `DocumentDeleteListener` 文件死信 payload 手工拼 JSON（路径含 `"` 会截断，概率极低） | 均为可观测性/一致性次要项 | 可随 R30/R31 一并处理或归批次 11 |
| R34 | 2026-10-07 | 09-9.2（**独立验证发现，高，未处理**） | **CharsetDetector 短样本误判 + 静默乱码风险**：独立验证用 14 个真实业务短字段（GBK）抽样，**8 个误判**（"张三"→KOI8-R、"研发部"→WINDOWS-1252、"这是一个测试"（6 汉字）→KOI8-R……），误判后 `transcodeToUtf8` 输出乱码且**无 WARN、无 U+FFFD 痕迹**。修复者声明的"≥6 汉字才稳定"**不成立**——边界是"短值普遍不可靠"（juniversalchardet 统计特性） | **当前安全**（唯一调用点是 `EncodingRule` 的 byte[] 分支，误判 → 判失败，安全方向）；**但 `transcodeToUtf8` 一旦接入读入链路（设计 §17.5 既定方向）会静默写坏数据**——这正是问题 25"步骤 ③ 未串起来"的前置风险 | **建议加护栏**（短样本优先 GBK / 加可观测信号）；✅ **已归批次 11 收尾清单**（2026-10-07 用户确认） |
| R35 | 2026-10-07 | 09（**独立验证发现，中，未处理**） | **`MethodArgumentTypeMismatchException` 无处理器**：`?sensitivityLevel=abc` / `?page=abc` → 落入 `GlobalExceptionHandler` 兜底 `Exception` 分支 → **HTTP 200 + body code 500**（"服务器内部错误"）——客户端参数错误被当作服务器故障 | 错误码语义不准，影响前端提示与排障；既有缺陷（`page/size` 早已受影响），批次 09 新增 `sensitivityLevel` 扩大了暴露面 | 建议补处理器返回 400（**小修复**）；✅ **已归批次 11 收尾清单**（2026-10-07） |
| R36 | 2026-10-07 | 09-9.4（**独立验证发现，中，未处理**） | **问题 36 的 WS 通道同类残留**：`useWebSocket.ts:23` 默认 `http://localhost:9090` 且 `.env.production` 未定义 `VITE_WS_URL` → **生产包内含 `http://localhost:9090/ws`**（已从 dist 产物确认）；`.env.development` 的 `VITE_WS_URL=.../ws` 与代码再拼 `/ws` 叠加成 **`/ws/ws`**。9.4 只覆盖了 `/api` 前缀 | 生产环境 WebSocket 永远连不上；开发环境路径错误 | 建议归批次 11（前端 1–2 行 + env 键）；✅ **已归批次 11 收尾清单**（2026-10-07） |
| R37 | 2026-10-07 | 09-9.1（低，登记） | **R18 的残留部分**（9.1 已解决 documentId 主症状）：① `documentTitle` 口径不一（BM25 用 `"chunk-" + chunkIndex` 占位；Chroma 用 metadata `document_title`）② `similarity` 口径不同（Lucene BM25 无界分 vs cosine 0~1，融合后被 RRF 分覆盖）③ 语义路在 metadata 缺 `chunk_id` 时回退 embeddingId（会重新劈开 key 空间；当前 19/19 都有 chunk_id，属潜在风险） | 前端引用/展示可能不一致 | 建议随批次 11 一并处理（含 `RetrievedDocument.documentId` 过时注释）；✅ **已归批次 11 收尾清单**（2026-10-07） |
| R38 | 2026-10-07 | 09（低，登记） | ① **9.7 的非结构化字段默认顺序变化**：由 `_content→content→text` 变为 `content,text,_content`（两者同时存在时取 `content`）——需确认是否符合预期 ② **9.5.3 的保留理由需更正**：独立验证证明 `langchain4j-chroma` 经 rag **可传递获得**（删显式声明不会破坏 LiveTest 编译），保留本身可辩护（显式优于隐式）但理由表述应更正 ③ 前端 `keyword` 参数未实现（类型有、视图未传）；`RetrievedDocument.documentId` 字段注释过时 | 文档/一致性细项 | 可随批次 11 一并处理；✅ **已归批次 11 收尾清单**（2026-10-07） |
| R39 | 2026-10-07 | 06（**独立验证发现，重要，待归属**） | **`Chunk.contentType` / `tableCaption` 是"死列"——既无写入方、也无读取方**：全仓 grep 仅出现在实体声明 / schema / 测试；两处 `new Chunk()` 落库点（`ChunkEmbeddingBatchListener:136`、`DataSyncEtlListener:158`）均未 set 这两字段。运行库实测：11788 行 `content_type` 全为 `'TEXT'`（DB 默认值兜底）、`table_caption` 全 NULL；**新上传文档同样如此**——`TABLE`/`TABLE_FRAGMENT`/`MIXED` **永远不会被产生**，表格切片在 DB 层与普通文本无法区分 | **问题 10 的"结构化元数据"交付物实际未闭环**（属"半成品接线"）；当前表格上下文还原只靠正文里的 `[TBL]` 标记 + Markdown 表头（TextSplitter 保证不截断），**检索链路本身未受影响** | 建议归后续批次（接线 Listener + 切分时判定表格段落 + 决定检索侧是否消费），或**明确标注为"预留给后续批次"**；✅ **已归批次 11 收尾清单**（2026-10-07 用户确认） |
| R40 | 2026-10-07 | 06（低，登记） | ① **运行库新增列的中文注释是双重编码乱码**（DDL 执行时连接字符集非 utf8mb4）——**仅注释受影响**，列名/类型/默认值全部正确；`p2-schema.sql` 本身无问题（用 `--default-character-set=utf8mb4` 重跑即正确）② **`p2-schema.sql` 整份文件非完全幂等**：文末是裸 `INSERT INTO sys_user ...`（无 `IGNORE`/`ON DUPLICATE KEY`），整文件重放会对运行库报重复键或产生重复用户（批次 06 新增的两行 CALL 本身幂等，已实测两遍） | 注释乱码影响可读性；schema 文件重放有隐患 | 建议随批次 11 处理（重跑注释 + 为 INSERT 加幂等） |
| R41 | 2026-10-07 | 06（提示，登记） | ① **PDFBox 2.x/3.x 混装**：tabula 传递带来 `pdfbox-tools 2.0.31` / `xmpbox 2.0.31` / `jempbox 1.8.17`（与 pdfbox 3.0.3 并存）；已核查 tabula 引用的 22 个 pdfbox 类全部可解析、当前无影响，但属隐性风险 ② **真实 PDF 布局鲁棒性未覆盖**：样本均为规则边框表格/规则文本；合并单元格、无边框靠空格对齐、跨页表、扫描件的行为未验证 ③ 「slf4j-simple 是 tabula CLI 用的」表述不准确（实为 `ObjectExtractorStreamEngine` 引用；CLI 不引用）——结论不受影响 | 隐性风险 / 覆盖盲区 | ② 如需可另立评测任务；①③ 登记备查 |
| R42 | 2026-10-07 | 10-10.4（**独立验证证实，中，未处理→随第二批修**） | **质检报告"详情"接口 `failures` 恒为空**（**既有缺陷**，非本批引入；`QualityReportMapper.java` 与 HEAD 逐字节相同）：`GET /api/dataquality/reports/{batchId}` 走自定义 `@Select`，MyBatis-Plus 3.5.5 **不给自定义 `@Select` 套 `autoResultMap`** → JSON 列未被 `JacksonTypeHandler` 处理。独立验证（真库 + 真 REST 双证据）：3 个含明细的 batch 详情**全部返回 `[]`**，而**列表接口正常**（22 条中 14 条非空） | REST 契约损坏；**当前不影响页面**（前端 `dataqualityApi.getReport()` 无调用方，`QualityReportView.vue` 只用列表）；但 batch-10 的"回归建议 4"（查看详情定位失败记录）会失败 | **最小修复 1 行**（`@ResultMap("mybatis-plus_QualityReport")` 或改走 BaseMapper wrapper）；**主 agent 决定：随批次 10 第二批一并修复** |
| R43 | 2026-10-07 | 10-10.4（低，登记） | ① **聚合键含"值相关内容"导致聚合失效**：reason 拼了具体数值/字段名（如 `字段 'username' 的长度 7 小于…`）→ 每种取值各成一条明细；300 条不同长度即触发 200 条上限被截断 ② **截断汇总条的 `recordCount` 语义重载**（正常条目 = 失败记录数；截断条 = 被丢弃的失败**种类数**） | 大数据量下明细可读性打折；字段语义不一致 | 建议 reason 模板化 + 区分字段（可归批次 11） |
| R44 | 2026-10-07 | 10-10.1（低，登记） | `QualityRuleService.updateRule` 是**整实体覆盖**：请求体缺 `enabled`/`priority` 时会被静默重置为 `true`/`100`（**可能把停用规则意外启用**；当前前端总发全量字段，属潜在风险） | 误启用规则的潜在风险 | 建议改增量更新或 null=保持原值（可归批次 11） |
| R45 | 2026-10-07 | 10（低，登记） | ① 前端未消费 10.4 新字段（`types/index.ts` 的 `QualityFailure` 仍只有 3 字段；`QualityReportView.vue` 未展示 `recordCount/recordIndices`）② `agent-qr-data-quality/target` 有陈旧 surefire 报告（已删除的 `ReadPathDiagTest`，建议 `mvn clean`） | 数据已暴露但 UI 未用；构建残留无害 | 建议随批次 11 处理 |
| R46 | 2026-10-07 | 10-10.2（低，体验缺口，未处理） | **前端订阅了但没人消费**：`ChatView.vue` 调用 `useWebSocket()` 时未传 `onDocumentProgress`/`onOpsAlert`，全前端无任何 `onDocumentProgress` 引用 → 进度/告警消息**能到浏览器但被丢弃**，界面只有连接状态点。"订阅接入（非死代码）"成立，但"**消息可用**"未闭环 | 10.2 的后端能力前端未真正用起来 | ✅ **已归批次 11 收尾清单**（2026-10-07） |
| R47 | 2026-10-07 | 10（提示，登记） | ① **验证工具链提示**：Git Bash 会把 `/topic/ops.alerts` 这类参数做 MSYS 路径转换 → STOMP 真机验证**必须设 `MSYS_NO_PATHCONV=1`**（否则会得出错误的"隔离/授权失效"结论——验证者实际踩过）② **精排能力当前潜伏**：19 条语料 < `final-top-k=30` → 默认链路不触发交叉编码器（要做生产级收益评估需调小 final-top-k 或扩大语料）③ **硬删切片不自动清理 BM25 磁盘索引**（需删目录重启重建）④ 效果数据口径：20 条自拟评测、候选 top-8、含粗排分混入——数字真实但**不宜外推** | 工具链/能力状态提示 | ①②③ 登记备查；④ 已在结论中标注适用范围 |
| R48 | 2026-10-08 | 11-11.2（**新发现，中，待决策**） | **`FeedbackService.submitFeedback` 无所有权校验**：`userId` 仅用于日志，**任何已登录用户可对任意 `messageId` 提交点赞/点踩**并影响全局满意率指标 | 满意度统计可被任意用户污染 | ✅ **已决策：需补校验**（2026-10-08 用户确认）→ 已追加给批次 11 收尾 agent 执行（补消息归属校验 + 测试） |
| R49 | 2026-10-08 | 11-11.1（**实现侧发现，低-中，未处理**） | **`AsyncConfigV2 implements AsyncConfigurer` 但未覆写 `getAsyncExecutor()`** → 未显式指定池名的 `@Async` **会落到 Spring 默认执行器**，不享受"六池隔离"与 MDC 传递——隔离设计存在一个洞（已在设计文档 §7.4 记录） | 部分异步任务的线程池与 MDC 传递不如预期 | 建议随批次 11 收尾清单一并评估（补 `getAsyncExecutor()` 覆写或逐个指定池名） |
| R50 | 2026-10-08 | 11-前端收尾（**新发现，低，未处理**） | ① `src/utils/sse.ts#createSSERequest` 是**死代码**（仅 `shouldRetrySse`/`SSE_RETRY_DELAY_MS` 被引用），且存在与 R19② **完全相同的 403 缺口**（`throw new Error('SSE 连接失败: HTTP ' + status)`）② E2E 在 dev 模式必失败：`page.route('**/api/**')` 通配会命中 Vite dev server 的模块请求（`/src/api/index.ts` 等）→ 必须 `CI=1` 走生产包 ③ i18n `zh-CN.ts`/`en-US.ts:178` 的 `user` 块**重复键 `title`**（既有问题） | 死代码/工具链/既有瑕疵 | ① 建议接线或删除（另立小项）；② 已记录用法；③ 可作独立小修 |
| R51 | 2026-10-08 | 11-收尾（**新发现，低-中，未处理**） | **`GlobalExceptionHandler` 的兜底 `Exception` 处理器会吞掉其他 Spring MVC 异常**：`HttpRequestMethodNotSupportedException` → **500 而非 405**、`NoResourceFoundException` → **500 而非 404**（与 R35 同源但非"绑定类异常"，本次未扩大改动） | 错误码语义不准（与 R35 同类） | 建议按 R35 同一模式补充处理器（405/404）；可归后续小修 |
| R52 | 2026-10-08 | 收官验证（**发现并已修复，高**） | **N1：`JwtUtilTest#validateToken_shouldReject_whenSignatureTampered` 是 flaky 测试**（实测 **2/36 ≈ 5.6%** 随机失败）：原实现"替换签名末字符"，而 base64url **末字符仅 4 个有效位、低 2 位是填充位**——`'A'→'B'` 可能解出**完全相同的字节** → 令牌仍合法 → 断言随机失败。**生产 JWT 校验逻辑无缺陷**（payload 篡改/换密钥/过期均正确拒绝，独立 HMAC 计算一致） | "707 用例全绿"不能稳定复现；**`ci.yml` 的 backend-tests 是 PR 阻塞项 → 会随机卡 PR** | ✅ **已修复**（2026-10-08）：改为"解码签名 → 翻转首字节最低位 → 重新 base64url 编码"（字节必然改变，断言确定化）；**单类复跑 15 次全过** + 全量 BUILD SUCCESS。同批登记 N2（e2e 注释悬空引用）/N5（CI 未在真实 Runner 跑过） |
| R53 | 2026-10-08 | 收官验证（**新发现，低，既有缺陷，未处理**） | **N4：`FeedbackService` 的满意率计数静默丢失**——累加走 `UPDATE stat_daily … WHERE stat_date=?`（**无 upsert**），**当天尚无任何问答**时该表无行 → 反馈计数静默丢失（实测：owner 提交返回 200，但 `stat_daily` 无今日行、无变化）；对照组：问答路径 `StatisticsUpdateListener` 会"不存在则建行"，两条路径未对齐 | 当天首次问答前的反馈不计入满意率（指标失真） | 建议对齐问答路径的"不存在则建行"（与 R48 同一指标域，可一并处理）；**待用户决策归属** |
| R54 | 2026-10-08 | 收官后（**用户报告的紧急回归，已修复并端到端确认**） | **`/api/auth/info` 返回残缺用户 → 上传对话框"数据域"选择器为空 → 无法上传文件**：`AuthServiceImpl.getCurrentUser()` 的 `UserPrincipal` 分支**只回填 id/username/role**（`allowedDomains`/department/clearanceLevel/title 全为 null）；批次 11 的 **R19③** 接线 `fetchUserInfo()` 后，前端 `parseAllowedDomains(u.allowedDomains \|\| '')` 解析成**空数组并覆盖**登录时正确的域列表（**既有后端缺陷，被接线暴露**） | **用户实际功能受损**（知识库无法上传） | ✅ **已修复（双端）**：后端 `getCurrentUser()` 改为 `sysUserMapper.selectById(...)` 加载完整用户（查不到回退最小对象）；前端对**字段缺失**保留本地已有域列表。**端到端确认**（同一账号，唯一变量 = 应用 jar）：`allowedDomains` **`null` → `"HR,FINANCE"`**，department/clearanceLevel/title 同步恢复；登录路径无回归；临时数据全清、基线全复 |
| R39 | 2026-10-08 | 11（**已处置，方案 B**） | `Chunk.contentType`/`tableCaption` 死列 → **选择"明确标注为预留给后续批次"**（任务认可的二选一）。理由：方案 A 的闭环需改范围外文件且**只接一半更糟**——`ChunkMapper#insertBatch` 的手写列清单**不含这两列**，在 `DataSyncEtlListener` set 字段会**静默不落库**（正是 R39 要消灭的半成品）；仅接上传链会造成"上传有值/同步恒 TEXT"的不一致 | 已四处标注（实体 javadoc + schema 注释与 DDL COMMENT + 设计文档 §8.15.2 + 两处落库点"为何刻意不填"）+ 两条锁定测试（标注齐全 + "当前确实无写入方"反向护栏） | ✅ **已闭环（方案 B）**。**如需改走方案 A**：需额外授权修改 `ChunkMapper.java`（`insertBatch` 补两列）与 `TextSplitter.java`（表格段权威判定） |

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
| 03-补 | 前端联动修复 | 独立子 agent 验证 | 纯函数 14 例 + **自定义渲染器真实挂载组件驱动 6 例** + 真实 axios 链 14 例全绿；vitest 24 用例；vue-tsc 无新增错误；遗留 3 项见 R19 | ✅ |
| 04 | 4.1 operator 语义 | 独立验证（与手写 JDBC oracle 逐条比对） | GT/GTE/LT/LTE 真下推 `>`/`>=`/`<`/`<=`；GT∪LTE=全量、开闭互斥；无 operator 保持区间语义；非法 operator WARN 不静默 | ✅ |
| 04 | 4.2 空集守卫 | 独立验证（真实 E2E） | FINANCE 域（有权但无数据）→ 返回空、**不跨域**，WARN 日志实测；**批次 03 权限过滤零改动**（5/5 通过） | ✅ |
| 04 | 4.3 灰度与降级 | 独立验证（真实 E2E + harness） | 开关双处默认 false；关闭态**零 LLM、零字段查询**（MyBatis 日志实证）；四种降级独立复现；HR 域 E2E 提取 9 条与 oracle 逐条一致 | ✅ |
| 04 | 4.4 聚合路径 | 独立验证（真实 SecurityContext + 真实 Mapper） | 越域/无域 fail-closed、admin 直通；空集语义与 4.2 一致；2000 上限生效；final-top-k 30/30 一致；流式链路 SSE 正常 | ✅ |
| 04 | N1 重复提取（返工项） | **独立复验**（与发现问题相同的场景） | 两态 × 两链路 × 两分支提取次数**均为 1**（原始日志）；rag 103 用例全绿；无与声明不一致之处 | ✅ |
| 04 | 批次级：全量测试 | 独立子 agent 复跑 | 13 模块 BUILD SUCCESS，**193 用例 0 失败**（同时证明 stash 事故未造成内容损坏） | ✅ |
| 04 | 批次级：范围与基线 | 独立子 agent 复查 + 主 agent 补清 | 无越界文件；全部基线复原（ChromaDB 6 / kb_chunk 19 / sys_user 12 / kb_chunk_structured 135 / dlq_message 0）；**主 agent 补清返工方遗留的 2 条 `token_refresh`（53 条，无孤儿）** | ✅ |
| 05 | 5.1 连接器失败语义 | 独立验证（自建 harness，真实 MySQL + 桩 HTTP） | 坏连接/缺表名/缺 cursorField → `success=false` + 明确 errorMessage；调用方写 `FAILED` 且不发事件；**URL 脱敏实测**（userinfo 与查询参数被剥离，非标准形态 fail-closed） | ✅ |
| 05 | 5.1 增量完整性 | 独立验证（多表实测） | 两表各插一行 → 增量两表都读到、游标各自推进（JSON 映射）；历史标量游标兼容；REST 3 页翻页 + **真实游标（非 null）**；maxPages 截断有 WARN + truncated | ✅ |
| 05 | 5.2 性能改造 | 独立验证（**驱动级证据**） | 1000 行批量 = **1 条 SQL（1000 组值 + 主键全回填）**；流式 `fetchSize` 真实生效（缓冲 124ms → 流式 1ms）；队列容量 `-D` 真机端到端生效；**性能数量级独立复现 157–186 倍** | ✅ |
| 05 | 5.2.5 零收益结论复核 | 独立复现（16 线程 × 128 条） | 旧 25739ms vs 批量 25687ms = **1.00×**（两轮一致）；批量端点 128/128 无数量不一致（对批次 07 的 size 分支有参考值） | ✅ |
| 05 | 5.3 调度 + 单飞锁 | 独立验证（真机 E2E） | 1 秒 cron → `sync_record` 真实新增；并发被拒；**异常路径 finally 释放锁**；INACTIVE/`syncEnabled=false` 不注册；原 90% 重复实现已删除 | ✅ |
| 05 | 5.3 返工项（熔断 + 两半自洽） | **独立复验**（真机 + 进程内） | 真机 **恰好 5 条 FAILED 后熔断**（返工前 59 条/59s），熔断后**无真实连接尝试**；单飞拒绝 6 连击计数恒 0；**ERROR 重启后被注册**（上次被证伪点已修复）；三条恢复通道验证且**恢复后任务真的继续跑** | ✅ |
| 05 | 范围外改动必要性 | 独立逐行核实 | `S3Connector` +4/−0、`ChunkStructuredMapper` +21/−0（纯新增）；确为达标所必需（见 R22） | ✅ |
| 05 | 批次级：全量测试 | 独立子 agent 复跑 | 13 模块 BUILD SUCCESS，**277 用例 0 失败**（datasource 60）；实库测试真实执行（skipped=0） | ✅ |
| 05 | 批次级：基线 + 清理 | 独立子 agent 复查 | 7 项基线全复原；**ChromaDB 仍 6 条**、无 scratch collection；258 条临时 sync_record + 4 个临时数据源已删；应用停止 | ✅ |
| 07-7.0 | 双状态机（问题 28 根因） | 独立验证（全仓写入点审计 + 实库） | 8 状态、`INDEXED` 插位正确；**`READY` 唯一写入点在向量写入成功之后**，全仓无第二处提前置 READY；实库观测 `PARSING→INDEXED→EMBEDDING→READY`；失败路径逐条回退 `INDEXED` 且有 `never()` READY 断言 | ✅ |
| 07-7.0 | 幂等（7.0d 核心） | 独立验证（**真库全量重跑**） | 19 条真实切片全部重跑向量化 → **无 `DuplicateIDError`**、向量 id 集合逐条不变；`removeAll` 入参经代码+单测+实库三重确认是 **UUID**；字节码确认 `ChromaEmbeddingStore` 无 upsert | ✅ |
| 07-7.0 | 存量迁移（7.0c） | 独立验证（逐条比对） | MySQL 19 ↔ Chroma 19 **id 集合完全一致**；19/19 向量 id = `UUIDv3("agent-qr-chunk-"+chunkId)`（Python 复算逐条命中）；collection id/dim/space 未变、无 scratch、无重复 id | ✅ |
| 07-7.0 | 事件驱动两链路 | 独立验证（含关键回归点） | 两条链路均发布 `ChunksBatchCreatedEvent`；旧 Listener 已删且 main 代码**零残留引用**；**CHUNK 重放链路未断**（新 Listener 同时消费 `DocumentParsedEvent`，有 Spring 容器级派发测试） | ✅ |
| 07-7.0 | 端到端状态流转 | 独立验证（**真实上传**） | `PARSING→INDEXED→EMBEDDING→READY`；**`INDEXED` 窗口内 BM25 命中新切片**（该切片向量尚未写入 ChromaDB，铁证）；生产数据反证 `getTitle()` 元数据修正已生效 | ✅ |
| 07-7.0 | BM25 双保险 | 独立验证 | 发布方失败仍置 `INDEXED` 并继续；只补缺失不重写；重复写不产生重复索引；实库 `BM25 校验: 缺失 0 条` | ✅ |
| 07-7.0 | 批次级：全量测试 | 独立子 agent 复跑 | 后端 **324 用例 0 失败**（+47 逐条核对吻合）；前端 **28**；迁移实库测试默认跳过（判定合理）但被独立显式跑通 **6/6** | ✅ |
| 07-7.0 | 批次级：基线 + 清理 | 独立子 agent 复查 + 主 agent 补建索引 | 全部基线逐条复原（**ChromaDB 19**、id 集合与基线相同）；E2E 的 62 切片/62 向量经真实删除 API 清理；**主 agent 补建实库缺失的 `idx_status` 索引** | ✅ |
| 07-7.1 | Collection 隔离（数据安全） | 独立验证（**E2E + 自建探针 17/17**） | **生效名仍为 `enterprise_knowledge`**（历史向量完好）；**E2E 检索命中历史向量**（semantic=19 全量命中、答案正确）；`ensureCollection` 三态+缓存语义探针通过；两配置键真机生效；**无"写 A 读 B"分裂** | ✅ |
| 07-7.2 | Embedding 失败语义与文档回填 | 独立验证 | 整批失败（`verify(never()).embed`）、`retrySingle` 零残留；聚合告警阈值/恢复日志；`preferred-embedding` 零残留；设计文档 8 处回填全部落在 Embedding 相关章节、未越界 | ✅ |
| 07-7.3 | BM25 v2 | 独立验证（**铁证三连**） | FSDirectory 真实落盘；**二次启动"已加载（未重建）"**（keyset SQL 2 次→0 次、segments mtime 不变）；异步（Started 之后才构建）；分页 keyset SQL 实测；增量回归通过；降级标志可见 | ✅ |
| 07-7.5 | 路由开关接线 | 独立验证（⚠️ 见 R27） | 三模式语义与启动日志均实现并有用例；批次 03"指定域优先"未破坏；**但 HTTP 入口强制 domain → 模式分支不可达** | ⚠️ |
| 07 | 批次级：全量测试（**补实库**） | 独立子 agent 复跑（容器已恢复） | **369 用例 0 失败**（354 + 15 实库补跑）；迁移类按显式开关单独执行 **6/6 通过**（幂等空操作、id 集合收敛） | ✅ |
| 07 | 批次级：基线 + 清理 | 独立子 agent 复查 | ChromaDB 19（**id 集合逐项 diff=∅**）；MySQL 六项基线一致；测试残留全清（含会话/消息/令牌/stat_daily 回退）；应用已停止 | ✅ |
| 07 | 批次级：范围外改动最小性 | 独立逐行核实 | `IndexableTextProvider` 只增一个 `default` 方法（+27/−0，未改抽象方法）；`ChunkIndexableTextProvider` 语义等价；`/data/` gitignore 实际生效 | ✅ |
| 08 | 8.1 软删切片 | 独立验证（**REST 真链路**） | `DELETE /documents/{id}` 后 `GET .../chunks` 返回空（真库 + 真 Service 链）；两个 Mapper 全部手写 `@Select` 独立审计；`selectTypeDistribution` 缺口属实（见 R32） | ✅ |
| 08 | 8.2 假成功修复 | 独立验证（21/21） | null 依赖 → FAILED + DLQ + 计数（**不是 DONE**）；空向量 → DONE（正确语义）；日志无"跳过…完成"；`required=false` 保留 + 启动校验实测 | ✅ |
| 08 | 8.3 孤儿扫描 | 独立验证（真库真 Chroma **23/23**） | 软删+残留能发现并清理、无残留不误删、失败不计 cleaned、三种归属 + 无元数据保留、周期 1800000；**`selectByDocumentId` 未被回退**（diff 确认） | ✅ |
| 08 | 8.4 文件清理 | 独立验证（真实文件系统） | 文件真删、幂等、失败入 DLQ **且 `retryDelete` 确实重放文件删除**、`filePath` 为空不调用 | ✅ |
| 08 | 8.5（R24）向量化重放 | 独立验证（真库真 Chroma + **线上实测**） | 三处对齐全过；注入必失败死信 → 走完 1→2→3→DEAD，**死信行数恒为 1**（对比修复前"每轮新增"） | ✅ |
| 08 | 8.5 追加：DELETE 环路（**R1**） | **返工 + 独立复验**（真实 MySQL 落库） | 失败落在当前消息（**不产生新死信**）、退避 9/27/81s、第 4 轮 DEAD；主链路 `asyncPhysicalDelete` 语义未变；结构性护栏（重放永不调 `asyncPhysicalDelete`）；REST 端到端 20/20 | ✅ |
| 08 | 批次级：全量测试 | 独立子 agent 复跑 | **422 用例 0 失败**（369 → +53）；opt-in 实库测试（delete 1/1、dlq 3/3、migration 6/6）全过，各自断言 ChromaDB 回 19 | ✅ |
| 08 | 批次级：基线 + 清理 | 独立子 agent 复查 | ChromaDB 19 / kb_chunk 19/11788 / structured 135 / document 5 / sys_user 12 / dlq 0 / delete_task 5 全一致；临时数据零残留；应用已停止 | ✅ |
| 09 | 9.1 RRF 去重键 | 独立验证（**真实端到端**） | 同一 chunk 两路召回 → 融合**只出现 1 次**且分数叠加（`0.0625 = 0.55/16 + 0.45/16`）；真实问答链路 `semantic=19, keyword=19, fused=19`（38→19）；ChromaDB 19/19 metadata 含 chunk_id；**R18 部分解决成立**（documentId 三处统一） | ✅ |
| 09 | 9.2 CharsetDetector | 独立验证（24 项过 23） | 字节层检测 + BOM + 转码还原（BOM 剥离）+ 旧 `detect(String)` 确已删除 + 回退判别力（严格解码）；⚠️ **短样本误判比声明严重**（见 R34） | ⚠️ |
| 09 | 9.3 筛选参数 | 独立验证（真库 + REST） | domain/密级/组合/分页全部生效；软删排除；空串等同不传；⚠️ 非法值 → 200/code 500（见 R35） | ✅ |
| 09 | 9.4 生产构建 | 独立验证（真实 `vite build`） | 产物中 `/api/api` = **0 次**；`/api/auth/refresh` 3 次；开发环境未动；⚠️ WS 同类残留（见 R36） | ✅ |
| 09 | 9.5 依赖显式化 | 独立验证（`dependency:tree -Dverbose`） | statistics 已显式声明 rag/auth；范围外改动仅 1 行 javadoc、无行为变化；⚠️ 保留理由需更正（R38②） | ✅ |
| 09 | 9.6 并发保护 | 独立验证（**真库 + REST**） | 并发两次删除**恰好一次成功、一次 409**（body code），`delete_task` 只建 1 行；DELETING/软删均拒；**ABAC 仍在**（403） | ✅ |
| 09 | 9.7 ETL | 独立验证（22/22） | 半结构化 = 合法 JSON + 映射后字段 + 嵌套保留；`content_fields` 对齐（非空）；`_table_comment` 标题；结构化回归 | ✅ |
| 09 | 批次级：全量测试 | 独立子 agent 复跑 | 后端 **497 用例 0 失败**（+75，逐类自洽）；前端 **37**（+9） | ✅ |
| 09 | 批次级：基线 + 清理 | 独立子 agent 复查 | 7 项基线全一致（另核 delete_task 5）；临时文档/用户/令牌/会话全清；应用已停止 | ✅ |
| 06 | 6.1 表格结构化 | 独立验证（**自建 reportlab 样本 + 真实编译产物**） | 多页 PDF（4 页，表格页+正文页）**全页正文与表格都完整**（独立证伪"第 2 页起静默空白"陷阱）；**`ObjectExtractor.close()` 陷阱本体经 javap + 反证实验证实**（close 后 page2 chars 93→2）；无表格 PDF **逐字符回归一致**（3626=3626）；"≥2 行且 ≥2 列"守卫**承重验证**（伪表格 28r×1c 被挡、真表格未误杀）；TextSplitter 超长表 40/40 行不丢不重且每片带表头；死循环修复未回退 | ✅ |
| 06 | 6.2 流式与内存 | 独立验证 | 2MB/64 页 PDF：两路径**结果完全一致**（1,788,780 字符）；`MemoryUsageSetting` 真实落盘（ScratchFileBuffer）；**反射证实 PDFBox 3.0.3 无 `loadPDF(File, MemoryUsageSetting)` 重载**（现写法为正解）；配置键 `parser.pdf.max-memory-mb` 生效（旧键已删） | ✅ |
| 06 | 6.1.4 DDL 与实体 | 独立验证（scratch 库幂等 + 运行库只读） | 运行库两列存在且类型/默认值正确、`Chunk.status` 未回退；**幂等在 scratch 库实测两遍**；⚠️ 注释乱码 + schema 非完全幂等（R40）；⚠️ **两列"无写入方也无读取方"**（R39） | ✅ |
| 06 | 禁止事项 | 独立核查 | OCR 未实现（反射 `performOcr` 返回 null、默认 false）；chunkSize/overlap 仍 500/50；PDFBox 普通文本提取保留 | ✅ |
| 06 | slf4j 冲突排除 | 独立验证（**机制级复现**） | 全量 `./mvnw test` **517 用例 0 失败**，`DocumentDeleteServiceV2Test` 11/11 不再报错；`dependency:tree` 确认 slf4j-simple 已全网不在；**仓外拼接 classpath 复现冲突机制**（simple 在前 → provider=SimpleLoggerFactory → cast=false）；反编译确认 tabula 仅 1 个 class 引用 slf4j、CLI 不引用 | ✅ |
| 06 | 批次级：基线 + 清理 | 独立子 agent 复查 | kb_chunk 11788/19、ChromaDB **19**（另用 sqlite 只读副本复核 embeddings=19）；临时库/临时文件全清；未启动过应用；工作区零改动 | ✅ |
| 10-10.1 | 动态规则（核心） | 独立验证（**真实 REST + 真库**） | 新增规则**不重启即生效**（真实同步链路：新增→pass 0/13、改→原因文本变、停用→明细消失、删除→404）；**★种子链 vs 内置兜底链逐条完全相等**（结果/原因/索引/阻断全同）；四边界符合声明；8 类非法配置全被拒；非 admin 403 | ✅ |
| 10-10.4 | recordIndex 修复 | 独立验证（真实类 + 真库） | 10000 条同因失败 → **1 条聚合明细 + `recordCount=10000` + `recordIndices` 恰为 100**（上限生效）；旧格式 JSON 仍可读 | ✅ |
| 10-10.4 | **详情接口缺陷复核** | **独立证实**（真库 + 真 REST） | 3 个含明细 batch 的详情接口**全部返回 `[]`**，列表接口正常（14/22 非空）；`QualityReportMapper.java` 与 HEAD 逐字节相同 → **既有缺陷**；`failedIndices` 在两种响应中均不出现 | ⚠️（见 R42） |
| 10-10.4 | 阻断行为与体积 | 独立验证 | 阈值边界（=0.50 不阻断、<0.50 阻断）与 HEAD 逐字一致；300 种原因 → 200 条 + 1 条截断汇总（无静默丢失）；真实链路 12/13 通过并触发 ETL（`failedIndices` 过滤仍正确） | ✅ |
| 10 | 批次级：全量测试 | 独立子 agent 复跑 | 后端 **570 用例 0 失败**（+53）；前端 **52**（+15） | ✅ |
| 10 | 批次级：DDL 与基线 | 独立子 agent 复查 | `quality_rule` DDL **scratch 库重放 3 遍幂等**、运行库 4 条种子完好；基线全部复原（**含 Chroma 19→31→19、Lucene 19→31→19 的精确回滚**）；范围外改动逐一核查**均只增不改语义**；`DataQualityService`/`QualityReportMapper` 零改动 | ✅ |
| 10-10.2 | STOMP 安全与隔离 | 独立验证（**真机 + 自建 STOMP 探针**） | `/ws/info` 无/无效令牌 **403**、有效 **200**；WebSocket 升级无令牌 **403** / 有令牌 **101**；握手有效但 CONNECT 缺 Authorization → **ERROR + CLOSED 1002**；**用户隔离**（A/B 双账号互收不到；订阅他人目的地被拒）；运维频道非 admin 被拒 / admin 放行；**场景②真机 E2E**（DLQ 积压 → 实时收到告警）；**场景①由验证者补做**（PARSING→CHUNKING→INDEXED 推送链，事后完整清理） | ✅ |
| 10-10.3 | Reranker 真实接入 | 独立验证（直连 + echo 服务 + 真机） | TEI 直连正常；Java 侧请求体含 `model`、预截断生效、**模型分真被采用**（echo 操控后 top-1 随之变）、**权重参数化可验证**；**效果数据独立复跑完全一致**（85%/80%/**100%**）；三种降级全部 **WARN 不静默**；"精排默认不触发"独立核实 | ✅ |
| 10-10.5 | 死配置接线 | 独立验证 | cache 策略随配置变（探针）；三个 `VITE_*` **出现在构建产物中**；**`write-to-chromadb` 关闭 → 停 INDEXED 且 Chroma 保持 19**、打开 → READY + 19→20（随后经真实删除链回 19）；**防漂移检查有效性实证**（注入假死键恰好 1 个失败、还原后 3/3）；**R27 标注到位且 domain 强制未被放宽**（`ChatController` git 零改动） | ✅ |
| 10-R42 | 详情接口修复 | 独立验证（真 REST） | `failures` 能读回且**与列表接口一致**、`failedIndices` 仍不暴露；修复点 `@ResultMap` 在位 | ✅ |
| 10 | 批次级：全量测试（第二批） | 独立子 agent 复跑 | 后端 **622 用例 0 失败**（570 → +52）；前端 **73**（52 → +21） | ✅ |
| 11-11.1 | A/B/C 三类回填 | 主 agent 核验 + **抽查脚本** | A 类 15 / B 类 10 / **C 类 13 项对照表（需求零删除）** 全部落地；抽查脚本（198 行）**先抓出 3 处真实缺陷并修正后归零**；11.1.6 架构变更完整回填（8 状态机 / 事件驱动 / §15.1 重画 / 幂等约束）；顺带修 §12 标题缺失与**全文档围栏不平衡**、RRF 参数过时（`k=60`→`rrf-k=15`、`hashCode`→chunkId）、分词器描述错误等 9 项 | ✅ |
| 11-11.2 | 测试体系收尾 | 主 agent 核验 + 实跑结果 | **13 模块盘点**（原 **2 个零测试模块** user/catalog 已补）；新增 8 类 / 36 用例 + 修 1 条**环境隐式依赖**；**E2E：HTTP 打桩 + 真实浏览器 + 真实构建产物**（2 用例实跑通过；真实 LLM 链路不可行已说明）；CI 两文件（阻塞/不阻塞分离、**不依赖 DB/LLM**）；性能冒烟（JDBC 10000 行 **129ms**、Embedding 16 条 5.8s、Chroma 检索 **P95 69ms**、并发检索 **P95 82ms**；RAG 端到端 P95 因无 chat LLM 未测） | ✅ |
| 11-11.3 | R28 向量数防线 | 主 agent 核验（3 场景 + 保守护栏回归） | 规则 3 → "存在性 + **向量条数**"；**隔离名为空 → 仍用既有**（拦原风险）；两条都有 → ERROR + 迁移提示；既有为空+隔离有名 → 用隔离名；**保守护栏未削弱**（count 查询失败 → 保守用既有）；实库只读测试 3 例（真 ChromaDB count 可读）；`enterprise_knowledge` 未动（19 条） | ✅ |
| 11-11.4 | R30 环路闭合（+R31） | 主 agent 核验（**真 `DeadLetterQueue` + 结构护栏**） | 监听器加**同步入口 `processDocumentParsed`**（失败上抛、不入队），`retryParse/retryChunk` 改调它；**4 轮重放无新死信 + 退避 9/27/81s + DEAD**；主链路 `@Async` 语义不变（结构护栏）；**未触碰批次 08 已闭环的 EMBED/DELETE**；R31 取"统一公式与注释"（行为不变） | ✅ |
| 11-前端收尾 | R19①②③ / R23 / R36 / R38③ / R45 / R46 | 主 agent 核验（**前端测试 73 → 129**） | 全部完成；两处二选一理由充分（R19① 补 domain 因**端点仍在用**；R38③ 移除 keyword 因**后端无该能力**）；生产包中 `localhost:9090` 已消除；E2E 2 条通过 | ✅ |

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
| 2026-10-07 | **批次 03 补充：前端联动修复完成并提交**（问题源于 R12 / 待确认 #14，用户决策"立即修复"）：<br>① `stores/auth.ts` 新增 `normalizeDomains` / `resolveAvailableDomains` / `pickDefaultDomain` 纯函数<br>② `ChatInput.vue` 默认选中首个可用域、删除「全部域」选项；无可用域时禁用发送 + 明示提示（fail-safe）<br>③ `ChatView.vue` 可用域解析（admin 空列表回退全量域，与后端 admin 直通一致）+ 入口非空兜底<br>④ `api/index.ts` 新增 HTTP 403 →「权限不足」分支（其他状态码行为不变）<br>**独立测试子 agent 验证**：除自身 20 条测试外，验证者独立复现纯函数 14 例、用自定义渲染器真实挂载组件驱动 6 例（空域禁用/强行触发不发送/时序/切换账号）、真实 axios 链 14 例；vue-tsc 无新增类型错误<br>**遗留 3 项**见 R19 | 主 agent |
| 2026-10-07 | **批次 04 完成**（问题 20、19、12、13），含一轮**独立验证发现 → 返工 → 复验**闭环：<br>① **4.1** operator 真正生效：新增 4 个 Mapper 方法（`>`/`>=`/`<`/`<=`），非法 operator WARN 不静默<br>② **4.2** 空候选集**返回空**（Step 0.5 + WARN），不再退化为全库检索；权限过滤（批次 03）零改动<br>③ **4.3** `FilterConditionExtractor` 接入两个调用点；灰度开关**默认 false**（关闭态零 LLM / 零字段查询）；超时/异常/畸形响应全降级<br>④ **4.4** `QueryIntentClassifier` + `AggregationQueryService` 接入；final-top-k 统一 30；聚合空集语义与 4.2 一致；安全上限 2000；**聚合路径自补 `isDomainPermitted` fail-closed 守卫**（防绕过批次 03 权限兜底）<br>**独立测试子 agent**：A–G 七组全过（含与手写 JDBC oracle 逐条比对、真实 SecurityContext 越域拦截）；**发现 N1**——"重复提取已修复"在降级路径不成立（实测 2 次），**打回返工**后**复验通过**（两态 × 两链路 × 两分支均为 1 次）<br>**测试**：rag 99 → **103**（批次 04 累计新增 92 条 / 11 类）；全量 **193 用例 0 失败**<br>**新登记**：R17（N1 全过程）、R18（聚合 sources 契约口径不一致）、主 agent 补清返工遗留 2 条 token | 主 agent |
| 2026-10-07 | **批次 05 完成**（问题 24、23、22、21①②③），含一轮**独立验证发现 → 返工 → 复验**闭环：<br>① **5.1** 连接器失败不再被吞（`SyncResult` 增 `success`/`errorMessage`/`truncated`）；JDBC **多表增量**（每表独立游标）；REST 增量翻页 + 全量返回真游标 + maxPages 可配/告警；JDBC URL 脱敏<br>② **5.2** ①JDBC 流式 ②ETL 批量 INSERT（1000/批）③队列容量参数化（硬编码 2000 → 可配 10000）⑤Ollama 批量端点（实测占比 **90.6% > 40%** 判据成立 → 改造，但**实测零收益**，如实记录）<br>③ **5.3** TaskScheduler 动态注册 + 单飞锁 + 消除 90% 重复实现<br>**性能实测**：MySQL 写入段 154s → 1.83s（**84 倍**，独立复现 157–186 倍）；但端到端仅省约 5%（向量化占 90%+）——方案文档的"6–8 分钟"须待批次 07 兑现<br>**独立测试子 agent**：核心全部独立复现（含驱动级 SQL 形态证据）；**发现 5.3「ERROR 放行」两半不自洽**（59 条/59s 无退避 + 重启后永不注册）→ 主 agent 撤回"接受"判断、**打回返工** → 复验通过（熔断恰好 5 条、ERROR 重启可注册、三条恢复通道有效）<br>**测试**：新增 81 条 / 11 类（全量 196 → **277**）；**并行踩坑**：`/api/embed` 撞 WebClient 256KB 缓冲（对批次 07 直接适用）<br>**新登记**：R20（重放页 × 阻断阈值交互，建议批次 07 保留 record_hash 去重）、R21（熔断只计连续失败）、R22（两处范围外改动，已接受）、R23（前端 cron 回显断裂） | 主 agent |
| 2026-10-07 | **批次 07 任务 7.0 完成**（问题 28，最大单项，按 **7.0a → 7.0b → 7.0d → 7.0c → 7.0e** 分段推进、每段自检）：<br>① **双状态机**：`DocumentStatus` 8 态（`INDEXED` 插于 CHUNKING 与 EMBEDDING 之间）；`Chunk.status` 补映射；**`READY` 移到向量写入成功之后**（原缺陷根因修复）；Document 状态**查询时实时聚合推导**（与决策 #11 同口径）；前端双状态展示（"部分就绪"可区分）<br>② **事件驱动**：新增 `ChunksBatchCreatedEvent`（只带标识，两条链路共用）+ `ChunkEmbeddingBatchListener`（**keyset 分页**读取，200/批）；文档链与同步链均改为发事件；**`ChunkEmbeddingListener` 已删除**（5 项职责逐条迁移，含 DLQ 入队与 `getFileName`→`getTitle`）；CHUNK 重放链路经 `DocumentParsedEvent` 保持贯通<br>③ **幂等**：写入前 `removeAll`（**UUID** 入参）+ `addAll`；真库 19 条全量重跑**无 `DuplicateIDError`**<br>④ **存量迁移实跑**：ChromaDB **6 → 19**，与 MySQL id 集合逐条一致；collection **未重建/未变**<br>⑤ **BM25 双保险**：发布方尽力 + Listener 校验补写（均幂等）<br>**独立测试子 agent**：核心全部真库复现（含端到端 `INDEXED` 窗口 BM25 命中的铁证）；发现 R24（**DLQ 重试体三处不一致，跨批次·高，待用户决策**）与 R25/R26<br>**测试**：新增 47 条（全量 277 → **324**）；前端 24 → 28<br>**主 agent 收尾**：更新 4.2 事件契约表（修正已删除 Listener 的过期描述）与 4.4 迁移结果、补建实库 `idx_status` 索引<br>**⏸️ 按用户指示，7.0 完成后暂停**，7.1–7.5 待续 | 主 agent |
| 2026-10-07 | **批次 07 完成**（问题 28、16、17、15、38 部分、21④；共 5 任务，7.0 见上一行）：<br>⑥ **7.1 Collection 隔离生效**：`getEffectiveCollectionName()` 接通（**写/读/枚举/删统一口径**）；`ensureCollection()` 真实查询 ChromaDB（三态 + 缓存语义）；`collection-prefix` / `auto-dimension-check` 生效；**保守解析策略保护历史数据**（6 条规则，当前环境恒解析为 `enterprise_knowledge`，隔离名待迁移后启用）；顺带修正 **R9**（`getEmbeddingModelName()` 现返回真实模型名 → 隔离名 `kb_ollama_qwen3-embedding-4b`）<br>⑦ **7.2 选型回填与失败可见**：设计文档 **8 处回填**（§6.0/§6.2.2/§6.2.4/映射表/§17.8 d 步/§17.9 等，限 Embedding 相关章节）；**整批失败**（`retrySingle` 彻底删除；`size` 安全阀**保留**——依据批次 05 真批量端点结论）；**聚合告警**（阈值 3 + 恢复日志）；删除 `preferred-embedding` 死配置<br>⑧ **7.3 BM25 v2**：`FSDirectory` 磁盘索引（二次启动不重建——keyset SQL 2→0 次、segments mtime 不变）；`@Async("indexBuilderExecutor")` 异步构建（不再阻塞启动）；keyset 分页加载；构建失败可见（`BuildState`）<br>⑨ **7.5 路由开关接线**：`routing.mode` 三模式实现 + 启动日志；⚠️ **但 HTTP 入口强制 domain → 模式分支不可达**（R27，待决策）<br>**独立测试子 agent**（容器恢复后补全实库验证）：A–G 全项通过；**E2E 铁证**——检索命中历史向量（semantic=19 全量）、磁盘索引"不重建"三连；发现 R27/R28/R29<br>**测试**：全量 **369 用例 0 失败**（354 + 15 实库补跑；迁移类显式开关 6/6）<br>**环境实战**：容器停运 10 小时后重启，**ChromaDB 19 条向量完好**（批次 02 持久化修复的实战验证）<br>**主 agent 收尾**：补 `.gitignore` 的 `/data/`（BM25 索引运行时产物）<br>**⏸️ 按用户指示，验证结果出来后暂停** | 主 agent |
| 2026-10-07 | **R27 定案并落入批次 10**（用户确认"归批次 10.5"）：<br>① `batch-10-决策类修复.md` 新增**子项 10.5.6「处理 routing.mode 的接线但生产路径不可达」**——含现状（HTTP 入口强制 domain → 模式 switch 永不执行）、推荐做法（**保留接线 + 明确标注为内部/预留能力**）、**禁止放宽 domain 强制**的告诫（会破坏批次 03 越权防护决策）、以及 `similarity-threshold`/`top-k` 的顺带核查；同步更新 10.5 的"已完成的部分"表述、批次验收与子 agent 指令<br>② `progress.md` 批次表（涵盖问题加 R27）、R27 处置列、更新日志同步；`README.md` 批次总览表同步 | 主 agent |
| 2026-10-07 | **R28 定案并落入批次 11**（用户确认"归批次 11"）：<br>① `batch-11-文档回填与收尾.md` 新增**任务 11.3「Collection 解析的向量数防线」**——含问题描述（隔离名与既有名同时存在时优先隔离名、不看向量数 → 隔离名为空时会静默切走历史向量）、3 条测试、**"不得削弱保守护栏"**的禁止事项；同步更新涵盖问题（加 R28）、涉及文件表（加 `EmbeddingDimensionManager.java`）、批次内顺序、批次验收、全局收尾检查与子 agent 指令<br>② **全局收尾检查新增一条**：把标记"建议归批次 11"的遗留项一并列明（R19 前端 3 项 / R23 前端 cron 回显 / R29② 设计文档滞后）<br>③ `progress.md` 批次表（涵盖问题加 R28、任务数 2→3）、执行清单、R28 处置列、更新日志同步；`README.md` 批次总览表同步 | 主 agent |
| 2026-10-07 | **批次 08 完成**（问题 27、31、29、32 + R24 + R1），含一轮**独立验证发现 → 返工 → 复验**闭环：<br>① **8.1** 软删切片不漏出：`selectByDocumentId` 加 `deleted=0` + 同类 SQL 审计（反射护栏）；发现 `DocumentMapper.selectTypeDistribution` 同类缺口（范围外，见 R32）<br>② **8.2** 假成功修复：null 依赖 → FAILED+DLQ+计数，与"无向量 ID→DONE"严格区分；日志不再自相矛盾；保留 `required=false` + 启动校验<br>③ **8.3** 孤儿扫描：方向反转（ChromaDB→MySQL，复用 7.0.11 枚举）；**新增专用方法、未回退 8.1**；删除方法返回实际条数（计数据实）；周期对齐 30 分钟<br>④ **8.4** 物理文件清理：`filePath` 接上真实清理（幂等）；失败入 DLQ 且 `retryDelete` **可重放文件删除**（否则该死信会被"只重试向量"的旧重试体当成功丢弃，文件永久残留）<br>⑤ **8.5（R24）** 向量化重放：**同步重放路径**（论证：事件路径会无界环路）+ 幂等写入 + `READY` 回写 + 只取未就绪切片<br>⑥ **返工（R1，批次 01 遗留）**：独立验证复现 DELETE 重放无界环路（且被 8.2 放大）→ 新增 `retryPhysicalDelete` 同步入口（失败上抛、不自行入队），`retryDelete` 改用它；主链路语义不变<br>**独立测试子 agent**：A–H 全过（含 REST 真链路 20/20、真库真 Chroma 23/23、线上退避实测）；返工后复验通过（真实 MySQL 落库：死信行数恒为 1）<br>**测试**：全量 **422 用例 0 失败**（369 → +53）<br>**新登记**：R30（**PARSE/CHUNK 同类环路，待归属**）、R31（退避 off-by-one）、R32（selectTypeDistribution 缺口，待归属）、R33（次要点） | 主 agent |
| 2026-10-07 | **R30 定案并落入批次 11**（用户确认"归批次 11 任务 11.4"）：<br>① `batch-11-文档回填与收尾.md` 新增**任务 11.4「PARSE/CHUNK 重放环路闭合」**——含问题描述、**同步入口方案**（首选，照搬批次 08 的 EMBED 做法）、2 条测试（含"主链路语义不变"回归）、"**不得用闸门式半成品**"的禁止事项；顺带 **11.4.3（R31）** 修正 `DeadLetterQueue` 退避 off-by-one 与测试类注释矛盾；涉及文件表加 `ChunkEmbeddingBatchListener` / `DlqRetryScheduler` / `DeadLetterQueue`；同步更新涵盖问题、批次内顺序、批次验收与子 agent 指令<br>② `progress.md` 批次表（涵盖问题加 R30、任务数 3→4）、执行清单、R30 处置列同步；`README.md` 批次总览表同步 | 主 agent |
| 2026-10-07 | **批次 09 完成**（问题 18、25、33断裂1、36、40、42、43，7 个独立任务）：<br>① **9.1** RRF 去重键统一为 chunkId（语义/聚合/关键词三处口径一致）——**顺带部分解决 R18**（主症状消除，残留见 R37）<br>② **9.2** CharsetDetector 改字节层（旧 `detect(String)` 删除）+ 严格解码回退 + 转码链路；⚠️ **短样本误判边界实测比声明严重**（R34，高）<br>③ **9.3** 知识库筛选参数补齐（`domain`/`sensitivityLevel` + `selectPageByFilter`）<br>④ **9.4** 生产 API 双前缀修复（`.env.production` 置空 + `buildApiUrl()` 统一）；⚠️ WS 同类残留（R36）<br>⑤ **9.5** 模块依赖显式化（statistics 声明 rag/auth）；`langchain4j-chroma` 保留（理由需更正，R38②）<br>⑥ **9.6** 删除并发保护（状态校验 + 条件更新抢占）——真库验证"并发两次恰好一次成功"<br>⑦ **9.7** ETL 半结构化改 `mappedRecord` + Jackson JSON；`content_fields` 与质检侧对齐；`_table_comment` 标题<br>**独立测试子 agent**：A–J 全项（含**真实端到端 fused 38→19**、真库并发 REST、`vite build` 产物核验）；发现 R34（短样本误判，**高**）、R35（非法参数→500）、R36（WS 双前缀）、R37/R38<br>**测试**：后端 **497 用例 0 失败**（422 → +75）；前端 **37**（28 → +9） | 主 agent |
| 2026-10-07 | **R34–R38 定案并全部归入批次 11 收尾清单**（用户确认"全部归批次 11 收尾清单"）：batch-11 的「全局收尾检查」遗留项条目已扩展为**完整清单**（R19、R23、R29②、**R34（高，需护栏设计决策）**、R35、R36、R37、R38 逐条列明处置要点）；`progress.md` 对应处置列同步更新 | 主 agent |
| 2026-10-07 | **批次 06 完成**（问题 10、11；恢复此前被叫停的批次）：<br>① **6.1** PDF 表格结构化：tabula 1.0.5 三层策略（提取 → `[TBL]` 标记 + `Chunk.contentType/tableCaption` 字段与表结构 → TextSplitter 表格感知 / 超长表保留表头）；移除 Tika 死依赖；**新增"≥2 行且 ≥2 列"守卫**（挡 `BasicExtractionAlgorithm` 对纯文本页的伪表格）<br>② **6.2** 流式解析：`MemoryUsageSetting`（**PDFBox 3.0.3 无 `loadPDF(File, MemoryUsageSetting)` 重载，经 `streamCache` 实现**——设计 §8.15.2 写法已过时）；两条路径统一逐页；配置键对齐 `parser.pdf.max-memory-mb`<br>**两个被挖出的深坑**：① **`ObjectExtractor.close()` 会关闭传入的 `PDDocument`** → try-with-resources 会让**第 2 页起正文静默变空白**（独立验证已用 javap + 反证实验证实）② **tabula 传递引入 `slf4j-simple`** 与 logback 冲突 → compensation 11 用例 `ClassCastException`（**修正了主 agent 此前"无需排除传递依赖"的结论**；已仅排除该项）<br>**独立测试子 agent**：A–F 全过（含独立证伪多页陷阱、守卫承重验证、逐字符回归、slf4j 机制级复现）<br>**测试**：全量 **517 用例 0 失败**（497 → +20）<br>**新登记**：R39（**contentType/tableCaption 死列——问题 10 结构化元数据未闭环，待用户决策归属**）、R40（注释乱码 + schema 非完全幂等）、R41（PDFBox 混装 / 布局覆盖盲区） | 主 agent |
| 2026-10-07 | **批次 10 的 10.1 + 10.4 完成**（工作量的主体），经独立验证：<br>① **10.1** 质检规则 CRUD 与动态加载：`quality_rule` 表（4 条种子，DDL 幂等）+ 6 个 CRUD 端点（admin 权限）+ `DataQualityChecker` **实时查库**动态加载（表空→内置兜底 / 全停用→空链 / 读表异常→WARN 兜底）；**生效策略：下次质检即生效**（无缓存、无需重启）；前端脱离 localStorage；补齐 `LengthRule`（原型有该类型而后端无实现）<br>② **10.4** 质检失败明细方案 B：设计文档 6 处回填（`quality_failure` 表 → `failures` JSON 列）；`QualityFailure` 正式化 DTO；**修 recordIndex 丢失**（10000 条同因失败 → 1 条聚合 + `recordIndices` 上限 100 + `recordCount`）；`failedIndices` 加 `@JsonIgnore`；体积上限 200 条<br>**独立测试子 agent**：A–E 全过；**★最强回归证据**——表内种子规则链与内置兜底链**逐条完全相等**；**独立证实**一处既有缺陷（R42）<br>**测试**：后端 **570 用例 0 失败**（517 → +53）；前端 **52**（37 → +15）<br>**新登记**：R42（**详情接口 failures 恒空——既有缺陷，1 行修复，主 agent 决定随第二批一并修**）、R43（聚合键含值导致聚合失效）、R44（updateRule 整实体覆盖）、R45（前端未消费新字段） | 主 agent |
| 2026-10-07 | **批次 10 完成**（问题 14、26、34、35、38 剩余 + R27 + R42），分两批派发与验证。<br>**第二批（10.2/10.3/10.5/R42）**：<br>① **10.2** STOMP：`/ws/**` 从 `permitAll` → `authenticated` + **CONNECT 阶段 JWT 校验**；场景①文档进度推用户专属目的地、**场景②运维告警仅 admin**（真机 E2E：DLQ 积压实时推送）；前端接入订阅（非死代码）+ 修 dev 的 `/ws/ws` 叠加<br>② **10.3** 真实交叉编码器：`RerankerProvider` + `BgeRerankerProvider`（TEI HTTP）；配置全接通；**降级链保留启发式 + 每次 WARN**；**效果对比（20 条评测）：粗排 85% / 旧启发式 80% / 新交叉编码器 100%**（旧"精排"实际拖累排序）<br>③ **10.5** 死配置收尾：`cache.*` + 3 个 `VITE_*` + `write-to-chromadb`（关闭 = 跳向量化、停 `INDEXED`、**不做自动补做**，恢复路径=DLQ 重放/重新同步）+ **防漂移检查**（有效性实证）+ **R27 按"保留接线 + 明确标注"处置**<br>④ **R42**（追加）：详情接口 1 行修复 + 真 REST 验证（failures 从恒 `[]` → 读到 7 条）<br>**独立测试子 agent**：A–F 全过（含**用户隔离**真机复现、**场景①补做**、效果数据独立复跑一致）；发现 R46（前端订阅未消费）/R47（工具链提示等）<br>**测试**：后端 **622 用例 0 失败**（570 → +52）；前端 **73**（52 → +21）<br>**主 agent 复核发现一处遗漏**：**R10**（hikari 子块死配置）未在 10.5 清单内、且防漂移检查**检测不到**（非"无读取点"类）→ **已补登记批次 11 收尾清单** | 主 agent |
| 2026-10-08 | **批次 11 的 11.1 + 11.2 完成**（并行派发）：<br>① **11.1 设计文档回填**（`doc/系统详细设计说明书.md` **+1367/−527**）：A 类 15 条（`GlobalExceptionHandler`/`DlqRetryScheduler` 归属、六池隔离、`CaffeineConfig`/`AsyncConfigP1` 类名、"`agent-qr.*` 键前缀"等）+ B 类 10 条（`AbacEvaluator` 签名与新增方法、`AsyncConfigP1` 空壳标注、模块依赖清单、`AbstractRoutingDataSource` 用法等）+ **C 类 13 项"设计 vs 实现"对照表（需求描述零删除）**；**11.1.6 向量化架构变更**（8 状态机 + `ChunksBatchCreatedEvent` + 重写 §7.2.2 + **§15.1 时序图重画** + 幂等约束 + 5 处"已退役"标注）；11.1.5 `classify` 偏差；11.1.7 §17.8/§6.2.4 矛盾消除；**抽查脚本 0 缺失**（先抓出 3 处真实缺陷）；顺带修 9 项（§12 标题缺失、**全文档围栏不平衡**、RRF 参数过时、分词器描述错误等）<br>② **11.2 测试体系收尾**：13 模块盘点（**原 2 个零测试模块** user/catalog 已补）；新增 8 类 / 36 用例（优先安全类 `JwtUtilTest`/`SysUserSerializationTest`）；修 1 条**环境隐式依赖**（`QualityRuleServiceTest` 单类执行必失败）；**playwright E2E**（HTTP 打桩 + 真实浏览器 + 真实构建产物，2 用例实跑通过）；**CI 建立**（阻塞/不阻塞分离、不依赖 DB/LLM、实库用例按约定 skip）；性能冒烟（JDBC 10000 行 `129ms`、Chroma 检索 `P95 69ms` 等）<br>**测试**：后端 **661 用例**（622 → +39）；前端 **73**；E2E **2**；基线全复（ChromaDB 19）<br>**新登记**：R48（`FeedbackService` 无所有权校验，**待决策**）、R49（`AsyncConfigV2` 未覆写 `getAsyncExecutor()`——**六池隔离的洞**）；R13 被 11.2 再次证实并补充（**全仓无 `tokenType` 消费**） | 主 agent |
| 2026-10-08 | **批次 11 的 11.3 + 11.4 + 前端收尾完成**（并行派发）：<br>① **11.3（R28）**：`resolveEffectiveCollectionName` 规则 3 升级为"存在性 + **向量条数**"——隔离名为空→仍用既有、两条都有→**ERROR + 迁移提示**、既有为空→用隔离名；**保守护栏未削弱**（count 查询失败→保守用既有）；顺带踩坑修正：ChromaDB count 端点**只接受 id**、**Java 三元拆箱陷阱**（`? count() : 0L` 会 NPE 使护栏失效）<br>② **11.4（R30）**：`ChunkEmbeddingBatchListener` 加**同步入口**（失败上抛、不入队），`DlqRetryScheduler` 改调它——**R1/R24 之后的最后两条环路闭环**；顺带 **R31**（退避公式与注释统一，行为不变）；⚠️ 已知副作用：重放时不再发布 `DocumentParsedEvent` → CHUNKING 推送不再发出（已登记）<br>③ **前端收尾**（R19①②③/R23/R36/R38③/R45/R46）：SSE 403 精准文案、`fetchUserInfo` 回源接线、**WS 消息消费**（ElMessage/ElNotification）、cron 回显、类型更新；**前端测试 73 → 129**（+56）；生产包中 `localhost:9090` 已消除<br>④ **R39 取方案 B**（明确标注为预留）——理由：方案 A 需改范围外文件且"只接一半更糟"（`ChunkMapper#insertBatch` 列清单**不含两列**，set 会**静默不落库**）<br>**测试**：后端 **681 用例**（661 → +20）；前端 **129**；基线全复（ChromaDB 19）<br>**新登记**：R50（`createSSERequest` 死代码 + 403 缺口）；R39 → 已闭环（方案 B） | 主 agent |
| 2026-10-08 | **批次 11 收尾全部完成**（10 项后端杂项 + R48 追加）—— **修复计划 43 个问题全部有明确结论**：<br>① **R10** hikari 死配置：去掉嵌套层（绑定生效测试断言 `getMaximumPoolSize()==20`）② **R34** 短样本护栏：优先 GBK + WARN + 前置条件文档 ③ **R35** 参数绑定异常 → 业务码 **400**（含同族缺参异常）④ **R37** 检索口径：占位标题三路径统一 + `vector:` 命名空间 + 注释重写 ⑤ **R38①** 确认为有意变更（补注释）⑥ **R40** 注释乱码重跑 + schema `INSERT IGNORE`（**scratch 库整份重放两遍通过**）⑦ **R43** reason 模板化 + 截断条语义区分（`omittedKindCount`）⑧ **R44** `updateRule` 增量（null=保持原值）⑨ **R49** 3 处裸 `@Async` 显式池名 + **源码扫描防回归** ⑩ **R50①** 删除死代码 `createSSERequest` ⑪ **R48**（追加）：`FeedbackService` 补**消息归属校验**（403，fail-closed，**admin 不例外**）<br>**测试**：后端 **707 用例**（681 → +26）；前端 **131**；基线全复（ChromaDB 19）<br>**九、最终问题状态表**：**43 个问题全部填写**（**42 ✅ + 1 ⚠️ 部分修复**——问题 21：四项改造全部落地、MySQL 段 84 倍，但端到端目标受向量化占比 95% 限制未兑现，已如实说明）<br>**新登记**：R51（兜底 `Exception` 处理器吞 405/404 → 报 500，建议后续按 R35 模式补） | 主 agent |
| 2026-10-08 | **收官独立验证完成 + flaky 测试已修复**：<br>**验证结论**：6 项高风险修复（R28/R30/R48/R10/R40/R35）**逐项独立复现通过**（R48/R35/R10 真机端到端、R40 scratch 库双重重放）；文档抽查（类名 75 命中、配置键 22/23、C 类 13 项对照表齐全、11.1.6 架构变更齐备）通过；43 问题状态表 **43 行齐全**且抽查与代码一致；基线 100% 复原、git 干净<br>**发现并修复 N1（高）**：11.2 新增的 `JwtUtilTest` 篡改用例是 **flaky 测试（实测 2/36 ≈ 5.6%）**——base64url **末字符仅 4 位有效、低 2 位是填充位**，"替换末字符"可能不改变字节；**已改为"解码后翻转首字节"（断言确定化）**，单类复跑 **15/15**、全量 BUILD SUCCESS。**生产 JWT 校验逻辑经独立验证无误**<br>**其他登记**：N2（e2e 注释悬空）、N4（**满意率计数静默丢失**，R53，**待决策**）、N5（CI 未在真实 Runner 跑过）<br>**「十、有意不修项」已填写**：R27（保留接线 + 标注）/ R39（方案 B 预留）/ C10（转码前置条件）/ 问题 21（技术现实限制） | 主 agent |
| 2026-10-07 | **R39（+R40）定案并归入批次 11 收尾清单**（用户确认"归批次 11 收尾清单"）：batch-11 收尾清单新增 **R39**（`contentType`/`tableCaption` 死列——要求**二选一**：接线落库点 + 切分判定 + 检索侧消费，或**明确标注为"预留给后续批次"**，不留含糊状态）与 **R40**（注释乱码重跑 + schema 文末 INSERT 加幂等）；`progress.md` R39 处置列同步 | 主 agent |
| 2026-10-07 | **批次 10 的推进方式已确认**（用户选择"您先部署再整体派发"）：**等待用户部署本地 reranker 推理服务**（Xinference / TEI 加载 `bge-reranker-v2-m3`，本机当前无此服务）；部署就绪后整体派发批次 10（10.1 质检规则 CRUD / 10.2 STOMP / 10.3 真实交叉编码器 / 10.4 质检明细 / 10.5 死配置收尾 + R27） | 主 agent |
| 2026-10-07 | **R24 定案并落入批次 08**（用户确认"归入批次 08"）：<br>① `batch-08-删除链路一致性.md` 新增**任务 8.5「DLQ 向量化重试体与新状态机对齐」**——含三处问题的实测描述（①状态断链 ②非幂等写入 ③重灌整文档）、首选方案（重试体复用 7.0 批处理路径 + **防 DLQ↔Listener 事件环路**告诫）、3 条针对性测试、验收标准与禁止事项；同步更新该批次的涵盖问题（加 R24）、涉及文件表（加 `DlqRetryScheduler.java`）、批次内顺序（8.1→8.5）、批次验收、回归验证建议与子 agent 指令<br>② `progress.md` 批次状态表（批次 08 任务数 4→5）、执行清单、R24 处置列同步更新；`README.md` 批次总览表同步<br>**归入依据**：与 8.3（孤儿向量扫描）同属"向量 id 集合一致性"领域，且 8.3 的扫描正确性依赖本项修复 | 主 agent |

---

## 九、最终问题状态表（批次 11 完成后填写）

> 43 个问题的最终结论，用于交付前的完整性检查。

| 编号 | 问题 | 所属批次 | 最终状态 | 说明 |
|---|---|---|---|---|
| 01 | 定时任务全线失活 | 01 | ✅ 已修复 | `@EnableScheduling` + 5 处调度点全部注册（真实启动实测） |
| 02 | DLQ 重试链路失效 | 01 | ✅ 已修复 | 6 类重试体全部执行实际业务动作；未知类型保留不删 |
| 03 | Dockerfile 模块清单滞后 | 02 | ✅ 已修复 | `COPY . .` + `.dockerignore`（上下文 615MB→4.36MB）；`docker build` 实测通过 |
| 04 | profile 恒叠加导致连库失效 | 02 | ✅ 已修复（路线 A） | 读写地址外部化 + `readReplicaFallbackToPrimary` 真正参与决策 |
| 05 | CQRS 读写分离不生效 | 02 | ✅ 已修复 | 13 个查询方法补 `@Transactional(readOnly=true)`；路由/降级实测 |
| 06 | 用户列表无鉴权 + 口令外泄 | 03 | ✅ 已修复 | `@PreAuthorize` + hasRole 双层；`@JsonIgnore`；部门/职级筛选补齐 |
| 07 | 刷新令牌丢 ABAC | 03 | ✅ 已修复 | 从 DB 加载完整用户 + 状态校验；admin 刷新后 role 保持 |
| 08 | 登出接口缺失 | 03 | ✅ 已修复 | `POST /api/auth/revoke` 接线既有无调用方方法 |
| 09 | Chat 域鉴权未落地 | 03 | ✅ 已修复 | 入口强制 domain + 检索层 `allowedDomains` 独立裁剪（双保险） |
| 10 | PDF 表格空实现 | 06 | ✅ 已修复 | tabula 1.0.5 三层策略 + `[TBL]` 标记 + 表格感知切片；⚠️ **结构化元数据两列按方案 B 标注为预留**（R39） |
| 11 | PdfParser 流式/内存保护 | 06 | ✅ 已修复 | `MemoryUsageSetting`（经 `streamCache`）+ 两条路径统一逐页 |
| 12 | LLM 结构化过滤不可达 | 04 | ✅ 已修复 | `FilterConditionExtractor` 接入；**灰度开关默认关闭**；全路径降级 |
| 13 | RAG 聚合查询截断 | 04 | ✅ 已修复 | `QueryIntentClassifier` + `AggregationQueryService`；final-top-k 统一 30；空集语义一致 |
| 14 | Reranker 静默降级 | 10 | ✅ 已修复 | 接入真实交叉编码器（TEI + bge-reranker-v2-m3）；**Hit@1 20/20（旧启发式 16/20）**；降级必 WARN |
| 15 | BM25Retriever 未升级 | 07 | ✅ 已修复 | 磁盘索引（FSDirectory）+ 异步构建 + keyset 分页；二次启动不重建 |
| 16 | Collection 隔离死代码 | 07 | ✅ 已修复 | 隔离链接通 + `ensureCollection` 真实查询 + 两键生效；**R28 向量数防线已补**（批次 11.3） |
| 17 | Embedding 选型未回填文档且为单点无降级 | 07 | ✅ 已修复 | 设计文档 8 处回填（本地 Ollama + qwen3-embedding:4b）；**整批失败** + 聚合告警；配置去冗 |
| 18 | RRF 去重键不一致 | 09 | ✅ 已修复 | 三条路径统一为 chunkId；真实端到端 fused 38→19 |
| 19 | 域过滤空集静默跳过 | 04 | ✅ 已修复 | 空候选集返回空（不再跨域）+ WARN |
| 20 | operator 不生效 | 04 | ✅ 已修复 | GT/GTE/LT/LTE 真下推（与手写 JDBC oracle 逐条一致） |
| 21 | 大数据源同步性能 | **05 + 07** | ⚠️ **部分修复（可交付）** | 四项改造**全部落地**（①JDBC 流式 ②ETL 批量 ③队列参数化 ④Chroma 批量+幂等）；**MySQL 段实测 84 倍**；但**端到端"6–8 分钟"未兑现**——向量化+Chroma 写入占 95%，且 5.2.5 实测批量端点零收益（1.0×）。详见 R20/R47② |
| 22 | SyncScheduler 死代码 | 05 | ✅ 已修复 | TaskScheduler 动态注册 + 单飞锁 + **连续失败熔断** + 三条恢复通道 |
| 23 | 连接器异常被吞 | 05 | ✅ 已修复 | `SyncResult.success/errorMessage`；失败写 FAILED 不再记成功；URL 脱敏 |
| 24 | 增量同步不完整 | 05 | ✅ 已修复 | JDBC 多表增量（每表独立游标）+ REST 翻页 + 全量真游标 |
| 25 | CharsetDetector 形同虚设 | 09 | ✅ 已修复 | 字节层检测 + 严格解码回退 + 转码链路；**R34 短样本护栏已补**（批次 11） |
| 26 | quality_failure 表缺失 | 10 | ✅ 已修复 | 保留 JSON 列方案 + 文档 6 处回填；**recordIndex 修复**；详情接口读回失败已修（R42） |
| 27 | 软删切片漏出 | 08 | ✅ 已修复 | `selectByDocumentId` + `deleted=0`（REST 真链路复现）；同类 SQL 审计 + 反射护栏 |
| 28 | 提前置 READY | 07 | ✅ 已修复 | **全链路重构**：8 状态双状态机 + `READY` 移至向量写入成功后（独立验证：唯一写入点） |
| 29 | 孤儿向量扫描漏检 | 08 | ✅ 已修复 | 方向反转为 ChromaDB→MySQL；专用查询；计数据实；周期 30 分钟 |
| 30 | DeleteTask 失败不落 FAILED | 01 | ✅ 已修复 | catch 补 `updateStatus(FAILED)` + Mapper 查询方法 |
| 31 | 删除假成功 | 08 | ✅ 已修复 | 依赖缺失 → FAILED + DLQ + 计数（与"无向量 ID→DONE"严格区分） |
| 32 | filePath 无消费者 | 08 | ✅ 已修复 | 删除时清理物理文件（幂等）+ `retryDelete` 可重放文件删除 |
| 33 | 前后端契约断裂 | 03, 09 | ✅ 已修复 | 断裂 1（筛选参数）在 09；断裂 2/3/4 在 03；后端 STOMP 补齐（10.2）后 WS 契约亦闭环 |
| 34 | WebSocket 前端有后端无 | 10 | ✅ 已修复 | STOMP 服务端 + CONNECT JWT 鉴权 + 用户隔离 + 运维频道仅 admin（真机 E2E） |
| 35 | 质检规则页假数据 | 10 | ✅ 已修复 | `quality_rule` 表 + CRUD + 动态加载（实时查库）；前端脱离 localStorage |
| 36 | 生产 API 路径双前缀 | 09 | ✅ 已修复 | `.env.production` 置空 + `buildApiUrl` 统一；**WS 通道亦修**（R36，生产包内 `localhost:9090` 已消除） |
| 37 | 全仓库零测试 | 01, 11 | ✅ 已修复 | **后端 707 用例 / 前端 131 / E2E 2**；CI 两文件（阻塞/不阻塞分离） |
| 38 | 配置项与代码脱节 | 07, 10 | ✅ 已修复 | 07 接通 collection-prefix / auto-dimension-check / routing.mode；10.5 接通 cache / VITE_* / write-to-chromadb；**R10（hikari 嵌套）与 R27（routing.mode 标注）亦闭环**；防漂移检查已建立 |
| 39 | 设计文档矛盾与脱节 | 11 | ✅ 已修复 | A 类 15 + B 类 10 + **C 类 13 项对照表（需求零删除）** + 11.1.5–11.1.7；抽查脚本 0 缺失 |
| 40 | 模块依赖隐式化 | 09 | ✅ 已修复 | statistics 显式声明 rag/auth；`langchain4j-chroma` 保留并说明 |
| 41 | 双 Advice 争抢异常 | 03 | ✅ 已修复 | 唯一 `@ExceptionHandler` + `@Order`；两条 403 路径响应同构 |
| 42 | 删除缺少并发保护 | 09 | ✅ 已修复 | 状态校验 + **条件更新抢占**（真库：并发两次恰好一次成功） |
| 43 | ETL 半结构化路径错误 | 09 | ✅ 已修复 | `mappedRecord` + Jackson 标准 JSON；`content_fields` 与质检侧对齐；`_table_comment` |

---

## 十、有意不修项（如有）

> 若某项经决策后确定不修，在此记录原因与后续建议。

| 编号 | 问题 | 不修原因 | 后续建议 |
|---|---|---|---|
| **R27** | `agent-qr.routing.mode` 在 HTTP 路径不可达 | **有意保留接线 + 明确标注**（用户决策）：该开关面向内部调用/预留能力；HTTP 入口因强制 domain（**批次 03 的越权防护决策**）恒按指定域检索 | 已在 yml 注释与设计文档 §8.15.6 标注；**不得为让其生效而放宽 domain 强制** |
| **R39** | `Chunk.contentType` / `tableCaption` 两列 | **有意标注为"预留字段"（方案 B）**：完整闭环需改 `ChunkMapper#insertBatch`（手写列清单不含两列，set 会静默不落库）与 `TextSplitter`（表格段权威判定）；**只接一半更糟**（上传有值/同步恒 TEXT 的不一致） | 四处已标注"无写入方/读取方"；如需闭环，按 R39 记录的 4 处清单实施 |
| **C10** | 字符集**转码**未接入读入链路 | 检测已到字节层 + 已提供 `transcodeToUtf8`，但**接入前须先满足 R34 的前置条件**（短样本判定不可靠，落库前后必须有 U+FFFD 校验、误判必须可见） | 前置条件已写入 `CharsetDetector` 类注释与被唯一调用点引用；接入时按此执行 |
| **问题 21** | 端到端"6–8 分钟"目标未兑现 | **技术现实限制**：向量化 + Chroma 写入占总耗时 95%；5.2.5 实测批量端点零收益（16 线程 1.0×）——单靠代码改造无法达标 | 四项改造已全部落地（MySQL 段实测 **84 倍**）；如需进一步，须提升本地推理算力或改远程 Embedding 部署（见 R47②） |

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
