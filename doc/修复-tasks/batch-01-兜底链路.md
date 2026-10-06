# 批次 01 · 兜底链路修复（★ 应最先执行）

> **涵盖问题**：37（测试基建）、30（DeleteTask 失败不落 FAILED）、02（DLQ 重试链路失效）、01（定时任务全线失活）
> **前置依赖**：无
> **批次内顺序**：**严格** 1.0 → 1.1 → 1.2 → 1.3
> **可并行**：本批次与批次 02、06、08、09 无文件交集，但**任务 1.0 是其他所有批次补测试的前提**，建议先行

---

## 批次目标

建立测试基础设施，并让事件驱动链路的"降级兜底"真正生效：DLQ 重试可执行、失败任务状态可见、定时器开启。

> ⚠️ **本批次必须整体完成**。只做任务 1.3 会造成 DLQ 消息被误删，比不修更糟；只做 1.2 则改动无效。

---

## 涉及文件

| 文件 | 任务 |
|---|---|
| 各模块 `pom.xml`（测试依赖）、`agent-qr-web/src/test/` | 1.0 |
| `agent-qr-compensation/.../service/DocumentDeleteServiceV2.java` | 1.1 |
| `agent-qr-compensation/.../mapper/DeleteTaskMapper.java` | 1.1 |
| `agent-qr-common/.../dlq/DlqMessage.java` | 1.2 |
| `agent-qr-web/.../scheduler/DlqRetryScheduler.java` | 1.2 |
| `agent-qr-common/.../dlq/DeadLetterQueue.java` | 1.2（如需） |
| `agent-qr-knowledge/.../listener/DataSyncEtlListener.java` | 1.2（仅改 eventType 字面量） |
| `agent-qr-knowledge/.../listener/ChunkEmbeddingListener.java` | 1.2（仅改 eventType 字面量） |
| `agent-qr-web/.../AgentQrApplication.java` | 1.3 |

**不得修改**：本批次之外的任何文件。

---

## 任务 1.0 — 建立测试基础设施（问题 37 起步）

> 问题详情：`doc/问题清单/37-全仓库零自动化测试.md`

**这是其他所有批次补测试的前提，应最先执行。**

### 步骤

- [ ] **1.0.1** 确认/补齐测试依赖
  - 各模块 `pom.xml` 需有 `spring-boot-starter-test`（scope `test`）
  - 检查根 `pom.xml` 是否已管理该依赖版本；若无则补
  - 当前全部 12 个后端模块**均无 `src/test` 目录**，需按需创建

- [ ] **1.0.2** 确认 `mvn test` 可正常执行
  - 在至少一个模块（建议 `agent-qr-common`）添加一条最简单的冒烟测试（如 `Result.success()` 的字段断言），验证构建链路通畅
  - 执行 `mvn -q test` 确认通过

- [ ] **1.0.3** 明确测试约定并记录到本文件末尾
  - 测试类命名、包路径（与被测类同包）、命名规范
  - 是否需要 Mockito / Testcontainers（说明可用性即可，不必本轮引入）
  - **本任务只搭骨架**，具体用例由各任务自行补充

- [ ] **1.0.4**（可选，若时间允许）为前端补最简测试骨架
  - `agent-qr-web-frontend` 已声明 `vitest`，但 `src/` 下无任何 `*.spec.ts`
  - 补一条最简单的工具函数测试，确认 `npm run test` 可跑
  - 若前端构建环境不可用，跳过并在报告中说明

### 验收标准

- [ ] `mvn -q test` 可在根目录执行并返回成功
- [ ] 至少一条冒烟测试通过
- [ ] 测试约定已记录

### 禁止事项

- ❌ 不要为了跑通测试而修改业务代码
- ❌ 不要引入重量级测试依赖（Testcontainers 等）——本轮只需基础 Mockito + JUnit

---

## 任务 1.1 — DeleteTask 状态流转可用（问题 30）

> 问题详情：`doc/问题清单/30-DeleteTask失败不落FAILED状态且全表只写不读.md`

### 步骤

- [ ] **1.1.1** 在 `DocumentDeleteServiceV2.asyncPhysicalDelete` 的 `catch` 分支中补失败状态更新
  - 当前 `catch` 只有 `incrementRetryCount` + `deadLetterQueue.enqueue(...)`
  - 需增加 `deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_FAILED)`
  - `STATUS_FAILED` 常量已定义但全仓无引用，本次激活它

- [ ] **1.1.2** 在 `DeleteTaskMapper` 中新增查询方法（DLQ 重试体需要）
  - `selectByStatus(String status)` — 按状态查询任务
  - `selectByDocumentId(Long documentId)` — 按文档查询任务（幂等判断用）
  - 参考同文件已有 `updateStatus` / `incrementRetryCount` 的 SQL 风格

- [ ] **1.1.3** 修正 `DeleteTask.chromaIds` 字段的注释
  - 当前注释描述为「JSON 数组」，实际以逗号分隔串存储（`String.join(",", ...)`）
  - **建议改注释**（改动面小）

### 补充测试

- [ ] 用例：物理删除抛出异常时，`updateStatus` 被调用且参数为 `STATUS_FAILED`
  - 可用 Mockito 验证 `DeleteTaskMapper` 的调用

### 验收标准

- [ ] 物理删除失败后，`delete_task` 表中该行 `status` 为 `FAILED`（而非停留 `PENDING`）
- [ ] `DeleteTaskMapper` 新增的两个查询方法可被调用并返回预期结果
- [ ] `DeleteTask.STATUS_FAILED` 在代码中至少有一处引用
- [ ] 补充的测试通过

### 禁止事项

- ❌ 不要改动 `chromaRetriever == null` 的分支逻辑（属问题 31，在批次 08 处理）
- ❌ 不要删除 `incrementRetryCount` 与 DLQ 入队逻辑（失败时三者应并存）

---

## 任务 1.2 — DLQ 重试链路可用（问题 02）

> 问题详情：`doc/问题清单/02-DLQ重试链路失效.md`

### 步骤

- [ ] **1.2.1** 引入事件类型枚举，替代散落的字符串字面量
  - 在 `DlqMessage` 中定义合法的事件类型常量，至少包含：
    `PARSE`、`CHUNK`、`EMBED`、`DELETE`、`ETL`、`CHROMA_WRITE`
  - 建议用 `public static final String` 常量或 enum，与现有代码风格一致
  - **关键要求**：必须一次性覆盖当前所有实际入队类型（见 1.2.2），避免修完又漏

- [ ] **1.2.2** 统一入队方的 eventType 字面量，改为引用枚举常量

  | 文件 | 行号（核查时） | 当前值 |
  |---|---|---|
  | `DataSyncEtlListener.java` | :86 | `"ETL"` |
  | `DataSyncEtlListener.java` | :140 | `"CHROMA_WRITE"` |
  | `DataSyncEtlListener.java` | :149 | 向量化失败入队 |
  | `DataSyncEtlListener.java` | :171 | `"ETL"` |
  | `ChunkEmbeddingListener.java` | :113 | `"CHROMA_WRITE"` |

  > 行号来自静态核查，可能有偏移，以实际代码为准。

- [ ] **1.2.3** 补齐 `DlqRetryScheduler` 的重试体
  - `retryParse`：从 payload 解析参数，重新触发文档解析
  - `retryChunk`：重新触发切片
  - `retryEmbed`：重新提交向量化
  - `retryDelete`：调用 `documentDeleteServiceV2` 的实际删除方法
  - 新增 `retryEtl` / `retryChromaWrite` 分支（或归并到合适的现有分支）
  - **若某重试体因缺少上下文无法实现**，必须留下明确的 `TODO` 注释说明缺什么，并**改为标记失败**（而非标记成功）

- [ ] **1.2.3b** ⚠️ 为"整批"语义预留 payload 结构（**跨批次契约**）
  - 批次 07 已确认：**Embedding 整批失败时整批入一次 DLQ**（见 `progress.md` 待确认事项 #2c）
  - 因此 `EMBED` 重试体需能**重放整批**，而非只处理单条
  - **本任务只需**：把 payload 设计为可承载**批次标识**（而非单条 chunk 标识），并让重试体据此重新触发整批
  - **不要在批次 01 自行定义批量契约的细节**——具体契约由批次 07 任务 7.0b 定义；本任务保证"接口能容纳"即可
  - 完成后在 `progress.md 4.2 事件契约清单` 登记

- [ ] **1.2.4** 修改 `default` 分支的语义（**本批次最关键的一处**）
  - 当前：未知类型 → `log.warn` → `updateRetryResult(id, true)` → **记录被物理删除**
  - 改为：未知类型 → `log.error` 告警 → **保留记录不删除**
  - 理由：未知类型属"契约不匹配"，静默删除等于丢数据

### 补充测试

- [ ] 用例：未知 eventType 的消息经重试调度后**仍存在于** `dlq_message` 表（不被删除）
- [ ] 用例：已知类型的重试体执行了实际业务动作（可 Mock 依赖验证调用）
- [ ] 用例：重试体抛异常时标记为失败（而非成功）

### 验收标准

- [ ] 入队方全部使用枚举常量，无裸字符串字面量
- [ ] `DlqRetryScheduler` 的每个 `retryXxx` 都执行实际业务动作，或显式标记失败并附 `TODO`
- [ ] 未知 eventType 不再导致记录被删除
- [ ] `DlqRetryScheduler` 的 switch 覆盖所有已定义的事件类型
- [ ] 补充的测试通过

### 禁止事项

- ❌ **不要在 1.2 完成前执行 1.3**
- ❌ 不要修改 `DeadLetterQueue` 的退避算法（3^n、max-retries 4 与设计一致，是正确的）
- ❌ 不要把 `updateRetryResult(id, true)` 作为"无法处理"的兜底——这正是原缺陷

---

## 任务 1.3 — 开启定时任务（问题 01）

> 问题详情：`doc/问题清单/01-定时任务全线失活.md`

### 步骤

- [ ] **1.3.1** 在 `AgentQrApplication` 上添加 `@EnableScheduling`
  - 当前仅有 `@SpringBootApplication` + `@ComponentScan("org.example.agent_qr")`

- [ ] **1.3.2** 启动后确认 5 处 `@Scheduled` 已被注册执行

  | 任务 | 位置 |
  |---|---|
  | DLQ 重试（30s） | `agent-qr-web/.../DlqRetryScheduler.java` |
  | 孤儿向量扫描（5min） | `agent-qr-compensation/.../OrphanVectorScanner.java` |
  | 重复数据清理（每日 3 点） | `agent-qr-compensation/.../DuplicateCleanupScanner.java` |
  | 域描述刷新（5min） | `agent-qr-catalog/.../router/DomainRouterV2.java` |
  | 域向量刷新（5min） | `agent-qr-rag/.../router/DomainRouterV2.java` |

- [ ] **1.3.3** 观察启动日志，确认定时任务注册无异常

### 补充测试

- [ ] 用例：`AgentQrApplication` 的注解中包含 `@EnableScheduling`（可用反射断言，防止后续被误删）

### 验收标准

- [ ] 应用可正常启动（`@EnableScheduling` 未引入循环依赖或 Bean 冲突）
- [ ] 启动后 30 秒内可见 DLQ 重试的日志输出
- [ ] 无 `@Scheduled` 相关的启动异常
- [ ] 补充的测试通过

### 禁止事项

- ❌ 不要修改 `OrphanVectorScanner` 的调度周期（属问题 29，在批次 08 处理）
- ❌ 不要调整其余 4 处 `@Scheduled` 的参数

---

## 批次验收

- [ ] 任务 1.0、1.1、1.2、1.3 全部完成
- [ ] 项目可编译，`mvn test` 通过
- [ ] 三条硬约束（见 `README.md` 第四节）无一被违反
- [ ] 已更新 `progress.md`

## 回归验证建议

1. 构造一条失败任务入 DLQ，确认重试后能被真正处理（而非被删除）
2. 构造一条未知 eventType 的消息，确认它**保留**在 `dlq_message` 表中
3. 确认 `delete_task` 表中失败任务的 `status` 为 `FAILED`

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-01-兜底链路.md 的全部任务。

严格按 1.0 → 1.1 → 1.2 → 1.3 顺序，不得调整。
本批次是"必须整体完成"的批次：只做 1.3 会导致 DLQ 消息被误删，只做 1.2 则改动无效果。
任务 1.0（测试基础设施）是其他所有批次补测试的前提，请优先确保它完成。

只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
完成后逐条报告验收标准的验证方式与结果。
不输出任何 API Key、Token、密码原文。
```

---

## 测试约定（任务 1.0.3 产出，2026-10-06 建立）

> 本约定为全仓库测试基线，后续所有批次补测试时遵循。

### 依赖与运行

| 项 | 内容 |
|---|---|
| 测试框架 | JUnit 5 + Mockito + AssertJ（由根 `pom.xml` 的 `spring-boot-starter-test` 提供，12 个后端模块自动继承，**无需逐模块声明**） |
| 运行命令 | 全量：`./mvnw test`；单模块：`./mvnw -pl <module> test` |
| 前端命令 | `cd agent-qr-web-frontend && npx vitest run`（等价 `npm run test:unit`） |
| 未引入 | Testcontainers / 嵌入式数据库（本轮不引入）；集成测试需要外部服务时应 Mock |
| 构建环境实测 | Java 21.0.11 + Maven Wrapper 3.9.16，`mvn compile` / `mvn test` 均通过 |

### 命名与位置约定

| 项 | 约定 |
|---|---|
| Java 测试类命名 | `<被测类名>Test.java` |
| Java 包路径 | 与被测类**同包**，位于 `<module>/src/test/java/...`（`src/test` 目录按需新建） |
| 用例命名 | `方法名_should预期行为_when条件`，语义直接对应缺陷，如 `unknownEventType_shouldNotBeDeletedFromDlq` |
| 前端测试命名 | `<模块名>.spec.ts`，与被测文件同目录 |
| 前端环境 | 纯函数测试在文件首行加 `// @vitest-environment node`（当前 jsdom 环境因依赖不兼容不可用，见下） |

### 已知环境限制（后续批次注意）

1. **前端 jsdom 环境不可用**：`vitest` 默认 jsdom 环境启动 forks worker 时抛
   `ERR_REQUIRE_ESM`（`html-encoding-sniffer` → `@exodus/bytes` 的 ESM 加载失败）。
   纯函数测试可用 `// @vitest-environment node` 规避；**需要 DOM 的组件测试暂无法运行**，
   属测试收尾（批次 11 任务 11.2）待修复项。
2. **测试须避免依赖 Spring 上下文**：除启动类注解断言外，一律用纯单元测试 + Mockito，
   不启动 `@SpringBootTest`（避免依赖 MySQL / ChromaDB / Ollama 等外部服务）。

### 冒烟测试（基建验证用例）

| 模块 | 测试类 | 覆盖 |
|---|---|---|
| agent-qr-common | `org.example.agent_qr.common.ResultTest` | `Result.success()` / `success(data)` / `error()` 字段断言；顺带锁定 `success(String)` 重载歧义行为 |
| agent-qr-web-frontend | `src/utils/format.spec.ts` | 4 条工具函数断言，验证 vitest 链路通畅 |

### 任务 1.0 执行记录

- **1.0.1** 根 `pom.xml:52-62` 已存在 `spring-boot-starter-test` + `spring-modulith-starter-test`（`<dependencies>` 全局声明），**无需补齐**。
- **1.0.2** 冒烟测试已添加并运行通过：`./mvnw -pl agent-qr-common test` → `Tests run: 4, Failures: 0, Errors: 0`。
- **1.0.3** 本节即产出。
- **1.0.4** 前端冒烟测试已添加并运行通过：`npx vitest run` → `Test Files 1 passed, Tests 4 passed`（node 环境）。
