# 批次 02 · 部署链路修复

> **涵盖问题**：03（Dockerfile 模块清单滞后）、04（profile 恒叠加导致容器连库失效）、05（CQRS 读写分离不生效）、**R8**（ChromaDB 数据持久化路径不匹配，批次 01 发现的批次外问题）
> **前置依赖**：批次 01 任务 1.0（测试基础设施）
> **批次内顺序**：**严格** 2.1 → 2.2 → 2.3；**2.4 与前三者无顺序依赖**，但**必须在批次 07 之前完成**（理由见任务 2.4）
> **可并行**：与批次 01、06、08、09 无文件交集

---

## 批次目标

让容器化交付链路可用（能构建、能连库、组件真正生效、**数据可持久化**），并让 CQRS 从"空转"变为"可控"。

> ⚠️ **本批次含高危顺序陷阱**：先修 2.3（CQRS 读库路由）而未修 2.1 会导致**所有查询失败**。必须按 2.1 → 2.2 → 2.3 执行。

---

## 已确认的路线（不要再决策）

问题 04 采用 **路线 A：承认三 profile 叠加现状，只修正副作用**。执行时直接按此实施，不要改为"单 profile 切换"。

---

## 涉及文件

| 文件 | 任务 |
|---|---|
| `agent-qr-web/src/main/resources/application.yml` | 2.1 |
| `agent-qr-web/src/main/resources/application-p3.yml` | 2.1 |
| `docker-compose.yml` | 2.1、**2.4** |
| `agent-qr-web/.../config/CqrsDataSourceConfig.java` | 2.1、2.3 |
| `Dockerfile`、`.dockerignore` | 2.2 |
| `agent-qr-common/.../datasource/ReadWriteRoutingDataSource.java` | 2.3（如需） |
| 各查询侧 Service（`DocumentQueryService`、`StatisticsQueryService`、`DataSourceService` 等） | 2.3 |

**不得修改**：本批次之外的任何文件。

---

## 任务 2.1 — profile 副作用修正与 CQRS 开关（问题 04）

> 问题详情：`doc/问题清单/04-profile恒叠加与CQRS恒开启导致容器数据库连接失效.md`

- [ ] **2.1.1** 把 CQRS 的读写库地址改为可外部覆盖
  - `application-p3.yml` 中 `spring.datasource.write.jdbc-url` / `read.jdbc-url` 目前硬编码 `localhost:3308` / `localhost:3309`
  - 改为 `${SPRING_DATASOURCE_WRITE_URL:jdbc:mysql://localhost:3308/...}` 形式（**保留当前值作为默认值**，不改变本地开发行为）

- [ ] **2.1.2** 处理失效的数据源键
  - `application.yml` 中的 `spring.datasource.url` 因 CQRS 常开而不再被绑定
  - 二选一：删除该键，或保留但注释说明"仅在 `agent-qr.cqrs.enabled=false` 时生效"
  - `docker-compose.yml` 中的 `SPRING_DATASOURCE_URL` 需调整为 `SPRING_DATASOURCE_WRITE_URL` / `SPRING_DATASOURCE_READ_URL`（与 2.1.1 的键名对齐）

- [ ] **2.1.3** 让 `CqrsDataSourceConfig.readReplicaFallbackToPrimary` 真正参与决策
  - 当前该字段**只被打印进日志**，不参与任何装配判断
  - 实现为：读库不可用时回退写库（可在装配时做一次连通性探测），或明确移除该字段并删除误导性注释
  - **不要保留"只打日志"的现状**

### 补充测试

- [ ] 用例：配置键在不同 profile 下的解析结果符合预期（可用 `@SpringBootTest` + `Environment` 断言，或纯配置读取测试）
- [ ] 用例：`readReplicaFallbackToPrimary` 为 true 且读库不可用时，路由回退到写库（可 Mock 数据源）

### 验收标准

- [ ] 容器环境下读写库地址可通过环境变量覆盖（不再被硬编码 localhost 锁定）
- [ ] `docker-compose.yml` 注入的环境变量名与实际绑定的配置键一致
- [ ] `readReplicaFallbackToPrimary` 参与实际逻辑，或已被移除
- [ ] 补充的测试通过

### 禁止事项

- ❌ **不要在本任务中给任何 Service 添加 `@Transactional(readOnly=true)`**（那是任务 2.3，必须在 2.1 之后）
- ❌ 不要修改 `agent-qr.cqrs.enabled` 的默认值（保持 p3 中为 true）
- ❌ 不要删除 `application-p1.yml` / `application-p2.yml`（三 profile 叠加是已确认的现状）
- ❌ 不要改为"单 profile 切换"（已确认选择路线 A）

---

## 任务 2.2 — Dockerfile 模块清单（问题 03）

> 问题详情：`doc/问题清单/03-Dockerfile模块清单滞后导致镜像构建失败.md`

- [ ] **2.2.1** 补齐缺失的 5 个模块
  - 当前 `Dockerfile:16-31` 只 COPY 了 7 个模块的 pom 与 src
  - 缺失：`agent-qr-compensation`、`agent-qr-datasource`、`agent-qr-data-quality`、`agent-qr-etl`、`agent-qr-catalog`

- [ ] **2.2.2** 改造为不易随模块增加而遗漏的写法（**推荐**）
  - 方案：`COPY . .` 配合完善的 `.dockerignore`（排除 `target/`、`node_modules/`、`uploads/`、`log/`、`.git/`、`doc/`）
  - 或：保持逐目录 COPY，但在 `Dockerfile` 顶部加注释说明"新增模块时必须同步此处"
  - **理由**：当前写法是"每次加模块都会忘记改"的结构性诱因

- [ ] **2.2.3** 验证构建命令与模块清单一致
  - `Dockerfile:36` 的 `mvn clean package -pl agent-qr-web -am` 会解析根 pom 全部 `<modules>`
  - 确认 `pom.xml:24-37` 的 12 个模块目录在构建上下文中都存在

### 补充测试

- [ ] 若采用"逐目录 COPY"方案：补一条断言，校验根 `pom.xml` 的 `<modules>` 与 Dockerfile 的 COPY 目录集合一致（可为脚本或单元测试）
- [ ] 若采用"COPY . ."方案：本任务难以自动化测试，需在报告中说明，并**实际执行一次 `docker build` 作为验证证据**

### 验收标准

- [ ] `docker build` 可成功完成（至少通过 Maven 反应堆初始化阶段）
- [ ] 构建上下文中包含全部 12 个模块目录
- [ ] `.dockerignore` 已排除构建产物与本地数据目录

### 禁止事项

- ❌ 不要修改根 `pom.xml` 的 `<modules>` 列表（12 个模块是正确的）
- ❌ 不要修改各模块的 `pom.xml` 依赖

---

## 任务 2.3 — CQRS 读写分离真正生效（问题 05）

> 问题详情：`doc/问题清单/05-CQRS读写分离不生效.md`
> **必须在前置任务 2.1 完成后执行。**

- [ ] **2.3.1** 给查询侧 Service 方法添加 `@Transactional(readOnly = true)`
  - 设计 §8.13.3 明确要求此约定，当前全仓库**零处**使用
  - 重点目标（按读取频率排序）：
    - `DocumentQueryService`（当前连 `@Transactional` 都没有）
    - `StatisticsQueryService`
    - `DataSourceService` 的 list / get 方法
    - catalog 相关的查询方法
  - **只给纯查询方法加**；涉及写操作的方法保持 `readOnly = false`

- [ ] **2.3.2** 确认路由生效链路
  - `ReadWriteDataSourceAspect` 拦截 `@Transactional` → 读取 `readOnly()` → 设置 ThreadLocal
  - `ReadWriteRoutingDataSource.determineCurrentLookupKey()` → 返回 `"read"` / `"write"`
  - 验证方式：观察日志 `事务只读标志: readOnly=true`

- [ ] **2.3.3** 验证"读库不可用"的降级
  - 若 2.1 已实现 fallback，验证读库断开时确实回退到写库
  - 若 2.1 选择移除 fallback，则需在设计文档中明确"CQRS 要求读库必须可用"

### 补充测试

- [ ] 用例：标注 `readOnly = true` 的方法中，`determineCurrentLookupKey()` 返回 `"read"`
- [ ] 用例：未标注 `readOnly` 的方法返回 `"write"`
- [ ] 用例：切面执行后 ThreadLocal 被清理（`finally` 中的 `clear()`）

### 验收标准

- [ ] 全仓库 `@Transactional(readOnly = true)` 至少出现在 3 个查询侧 Service 中
- [ ] 运行时日志可见 `readOnly=true`
- [ ] 只读方法与写方法各自路由到正确的数据源
- [ ] 读库不可用时行为符合 2.1 中确定的策略
- [ ] 补充的测试通过

### 禁止事项

- ❌ **不要在 2.1 完成前执行本任务**（会导致所有查询路由到不可达的读库）
- ❌ 不要给写方法（`DocumentCommandService.uploadDocument` 等）加 `readOnly = true`
- ❌ 不要修改 `ReadWriteDataSourceAspect` 的 `@Order(-1)`（须在事务拦截器之前）

---

## 任务 2.4 — ChromaDB 数据持久化修正（问题 R8，批次 01 发现）

> **来源**：批次 01 执行中发现的批次外问题，见 `progress.md` 风险记录 **R8**（2026-10-06 实测确认）
> **依赖**：无（可在本批次内任意位置执行）
> **⚠️ 时限**：**必须在批次 07 之前完成** —— 7.0c 的存量迁移依赖当前 ChromaDB 中的 6 条向量
> **性质**：部署配置修正 + 一次数据迁移

### 背景

`docker-compose.yml` 为 ChromaDB 配置了 `PERSIST_DIRECTORY=/chroma/chroma`，并把命名卷 `agent-qr-chroma-data` 挂载到该路径。但实测（ChromaDB **1.0.0**，Rust 版，容器内以 `chroma run /config.yaml` 启动）：

| 项 | 实测值 |
|---|---|
| 容器**实际写入**目录 | **`/data`**（启动日志 `Saving data to: /data`；`/data/chroma.sqlite3` 约 10MB，含全部数据） |
| 挂载卷内容 | `/chroma/chroma` 仅 4KB（几乎为空） |
| `PERSIST_DIRECTORY` 环境变量 | **不生效**（1.0.0 已改由 config.yaml 决定持久化路径） |

**后果**：全部向量数据位于**容器可写层**——容器一旦重建/删除（`docker compose down`、`up --force-recreate`、镜像更新、Docker Desktop 重建容器），数据全部丢失。

**为什么必须在批次 07 之前**：任务 7.0c（存量数据核对与迁移）的输入是 `progress.md` 4.4 的实测结论——「19 条切片中 13 条需补写、6 条已有向量（collection `7fbaddfc-4cd8-4651-b987-827e81e31257`）、孤儿 0 条」。那 6 条向量正存在当前这个**未挂载**的 `/data` 中；若容器在 7.0c 之前被重建，核对结论作废，7.0c 的"按差集补写 13 条"会退化为"全部 19 条重新向量化"。

### 步骤

- [ ] **2.4.1** 修正卷挂载点
  - 将 `docker-compose.yml` 中 chromadb 服务的卷挂载从 `/chroma/chroma` 改为 **`/data`**（ChromaDB 1.0.0 的实际持久化目录）
  - 处理已失效的 `PERSIST_DIRECTORY` 环境变量：移除，或保留并加注释说明"1.0.0 起不生效，实际路径由 config.yaml 决定"

- [ ] **2.4.2** 迁移现有数据 —— **必须先备份再重建，顺序不可颠倒**
  1. 备份：`docker cp agent-qr-chromadb:/data/. <备份目录>`（当前数据只在容器可写层）
  2. 重建：`docker compose up -d --force-recreate chromadb`，使新挂载点生效
  3. 回填：把备份数据复制回新容器的 `/data`，再重启容器
  - ⚠️ **先重建后备份 = 数据已丢失且不可恢复**

- [ ] **2.4.3** 验证持久化生效
  - `GET /api/v2/tenants/default/databases/default/collections` 应返回 `enterprise_knowledge`（id `7fbaddfc-4cd8-4651-b987-827e81e31257`）
  - **再次重建容器**并复查，证明数据确实落在卷上（而非又一次留在可写层）

### 补充测试

- [ ] 本任务以部署验证为主，纯自动化困难。可行方案：
  - 补一条运维断言脚本：`docker compose down && docker compose up -d` 后查询 collection 列表，断言 `enterprise_knowledge` 存在
  - 若环境不便自动化，需在报告中说明理由，并以 2.4.3 的**实际重建验证输出**作为验收证据

### 验收标准

- [ ] 卷挂载点与 ChromaDB 1.0.0 实际数据目录一致（`/data`）
- [ ] 容器重建后，`enterprise_knowledge` collection 与 6 条向量仍在（迁移前已备份）
- [ ] 存量核对基线（`progress.md` 4.4）未被破坏
- [ ] 补充验证通过，或按"补充测试"说明理由

### 禁止事项

- ❌ **不得在未备份数据的情况下重建/删除容器**（当前数据在可写层，重建即不可恢复）
- ❌ 不要改动 collection 名 / 维度 / 距离度量（`enterprise_knowledge` / 2560 / cosine 均为已核对基线，见 `progress.md` 4.4）
- ❌ 不要修改应用侧 `langchain4j.chroma.*` 配置（应用侧 REST 路径已由批次外修复 R7 对齐，勿回退）

---

## 批次验收

- [ ] 任务 2.1、2.2、2.3、**2.4** 全部完成
- [ ] 功能对等验证：批次完成后所有查询功能正常（未因路由改动而失败）
- [ ] 项目可编译，`mvn test` 通过，容器镜像可构建
- [ ] 硬约束 2（见 `README.md` 第四节）未被违反
- [ ] 已更新 `progress.md`

## 回归验证建议

1. 启动应用，执行一次文档列表查询 + 一次文档上传，确认分别走读库与写库
2. 断开读库（或改错读库地址），确认降级行为符合预期
3. 完整执行一次 `docker build` + `docker compose up`，确认容器内可连库
4. **执行 `docker compose down` 后重新 `up`，确认 ChromaDB 中 `enterprise_knowledge`（id `7fbaddfc-…`）与 6 条向量仍在**（任务 2.4 的持久化验证，同时保护批次 07 的迁移基线）

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-02-部署链路.md 的全部任务。

严格按 2.1 → 2.2 → 2.3 顺序，不得调整。
2.1 是本批次的前置：未完成 2.1 就执行 2.3，会导致所有查询路由到不可达的读库而全部失败。

任务 2.4（ChromaDB 持久化）与前三者无顺序依赖，但必须在批次 07 之前完成。
其数据处理务必遵守「先备份、再重建」的告诫：当前数据在容器可写层，
先重建后备份 = 数据不可恢复，且会使批次 07 的存量迁移基线（progress.md 4.4）作废。

问题 04 的路线已确认为「路线 A：承认三 profile 叠加，修正副作用」，
请直接按路线 A 实施，不要改为单 profile 切换，也不要重新决策。

只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
不输出任何 API Key、Token、密码、连接串原文。
```
