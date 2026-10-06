# 批次 10 · 决策类问题修复（工作量最大）

> **涵盖问题**：14（Reranker 接真实模型）、26（质检失败明细方案 B）、34（WebSocket 后端）、35（质检规则动态化）、38 剩余部分（cache / VITE_* 配置接线）
> **前置依赖**：批次 04（14 的效果评估依赖 13 的检索改造）、批次 05（26 的失败明细量级）、批次 07（38 的 collection-prefix 已接通）、批次 01（测试基建）
> **批次内顺序**：**无强制顺序**，5 个任务相互独立（但 10.4 工作量最大，建议先启动）
> **可并行**：与批次 02、03、04、06、08、09 无文件交集

---

## 已确认的决策（直接执行，不要重新决策）

| 编号 | 决策 |
|---|---|
| 14 | **接入真实交叉编码器**（bge-reranker-v2-m3），让 `agent-qr.reranker.model` 生效 |
| 26 | **保留 JSON 列方案**，回填设计文档 6 处，并修掉去重导致的 recordIndex 丢失 |
| 34 | **补齐后端 STOMP 服务端**；场景为：① 服务端推送文档处理进度 ② 推送运维/告警类通知 |
| 35 | **补齐后端规则 CRUD 与动态加载**（建表 + 端点 + 质检引擎动态规则） |
| 38 | **能接线就接线，接不了就删**；尽可能保留配置能力 |

---

## 涉及文件

| 任务 | 问题 | 涉及文件 |
|---|---|---|
| 10.1 | 35 | `agent-qr-data-quality/` 下新增表/实体/Mapper/Service/Controller；`checker/DataQualityChecker.java`；`agent-qr-web-frontend/src/views/quality/RulesManager.vue` 等 |
| 10.2 | 34 | `agent-qr-web/` 新增 `WebSocketConfig`；`SecurityConfig.java`；`agent-qr-web/pom.xml`；相关 Listener/Publisher |
| 10.3 | 14 | `agent-qr-rag/.../retriever/RerankerService.java`；新增 `RerankerProvider`；`agent-qr-rag/pom.xml`；`application-p2.yml` |
| 10.4 | 26 | `agent-qr-data-quality/.../checker/DataQualityChecker.java`；`entity/QualityFailure.java`；`doc/系统详细设计说明书.md`（回填） |
| 10.5 | 38 | `agent-qr-common/.../config/CaffeineConfig.java`；`agent-qr-web/src/main/resources/application-p2.yml`；`agent-qr-web-frontend/src/api/index.ts` 等 |

**不得修改**：本批次之外的任何文件。

> **注意 10.1 与 10.4 都改 `DataQualityChecker`** —— 请合并给同一个 agent，或串行处理。

---

## 任务 10.1 — 质检规则 CRUD 与动态加载（问题 35，工作量最大）

> 问题详情：`doc/问题清单/35-质检规则页为本地假数据无后端支撑.md`

**当前状态**：前端 `RulesManager.vue` 完全依赖 localStorage（注释自述"后续对接后端 API"），后端无任何规则接口，页面配置的规则**不参与真实质检**。

- [ ] **10.1.1** 建 `quality_rule` 表 + 实体 + Mapper
  - 字段建议：规则类型、目标字段、校验参数（JSON）、启用状态、优先级、创建/更新时间
  - 同步修改 `db/p2-schema.sql`

- [ ] **10.1.2** 在 `DataQualityController` 增加规则 CRUD 端点
  - 参考现有 `/reports` 两个端点的风格（统一 `Result` 封装）
  - 端点需带权限校验（参照同模块或 auth 模块的既有做法）

- [ ] **10.1.3** 改造 `DataQualityChecker` 支持动态规则（**核心改造**）
  - 当前是 `List<QualityRule>` **Bean 注入**（编译期固定四条规则）
  - 改为从数据库动态加载规则定义，并按类型分派到对应的规则实现
  - **设计建议**：保留现有的 `QualityRule` 实现类作为"规则类型"，数据库只存**配置**（目标字段、阈值等），而不是任意脚本 —— 避免引入脚本引擎的复杂度与安全风险
  - **注意**：改造后需保证现有四条规则（`CompletenessRule`、`EncodingRule`、`FormatRule`、`DeduplicationRule`）的行为不变

- [ ] **10.1.4** 前端切换到真实 API
  - `RulesManager.vue` 移除 localStorage 逻辑，改调后端接口
  - 保留现有的页面结构与交互（UI 已完成，只需换数据源）

- [ ] **10.1.5** 处理规则生效时机
  - 规则变更后是否需要重启？还是下次质检即生效？
  - **需在报告中明确说明**所采用的策略

### 补充测试

- [ ] 用例：规则 CRUD 端点可正常增删改查
- [ ] 用例：新增一条"字段 X 非空"的规则后，质检时**确实按该规则判定**（**这条用例能拦住原缺陷**）
- [ ] 用例：禁用的规则不参与质检
- [ ] 用例：现有四条规则的行为与改造前一致（**回归，重要**）
- [ ] 用例：非法规则配置被拒绝（参数校验）

### 验收标准

- [ ] `quality_rule` 表与 CRUD 端点存在
- [ ] `DataQualityChecker` 从数据库加载规则
- [ ] 前端不再使用 localStorage
- [ ] 规则变更能影响真实质检结果
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要引入脚本引擎（如 Groovy/JS 引擎）——规则应为配置化，保持可控
- ❌ 不要移除现有四条 `QualityRule` 实现类（它们变成"规则类型"的提供者）
- ❌ 不要改变现有质检结果的语义（改造前后对同一数据应得出相同结论）

---

## 任务 10.2 — 补齐后端 STOMP 服务端（问题 34）

> 问题详情：`doc/问题清单/34-WebSocket前端有后端无.md`

**已确认场景**：① 服务端推送文档处理进度 ② 推送运维/告警类通知

- [ ] **10.2.1** 引入依赖并实现 `WebSocketConfig`
  - `spring-boot-starter-websocket` 依赖（当前 pom 中无）
  - `@EnableWebSocketMessageBroker` + `registerStompEndpoints`
  - 端点路径与前端约定一致（前端当前连 `${VITE_WS_URL}/ws`）

- [ ] **10.2.2** 实现握手阶段的 JWT 鉴权（**安全要求**）
  - 当前 `SecurityConfig.java:51` 是 `/ws/**` → `permitAll()`（悬空放行）
  - 收敛为带鉴权的握手：通过 `ChannelInterceptor` 在 CONNECT 阶段校验 JWT
  - 认证失败应拒绝连接

- [ ] **10.2.3** 实现场景①：文档处理进度推送
  - 复用现有事件链（`DocumentUploadedEvent` → `DocumentParsedEvent` → ... → `EmbeddingCompletedEvent`）
  - 推送到用户专属的目的地（`/user/queue/...`），避免串号
  - **注意**：批次 07 的 7.4 会改变 READY 的触发时机，推送点应以最终实现为准

- [ ] **10.2.4** 实现场景②：运维/告警类通知
  - 数据源同步失败、质检阻断、DLQ 积压等事件推送到运维频道
  - **注意**：批次 01 修复后的 DLQ 能力可供使用（死信入队时推一条通知）
  - 需要有权限控制（仅管理员可订阅）

- [ ] **10.2.5** 前端适配
  - `useWebSocket.ts` 的 `send` / `subscribe` 当前是死代码，需接入实际订阅
  - `ChatView.vue` 的连接与 SSE 降级逻辑保留（降级是必要的容错）

### 补充测试

- [ ] 用例：未携带有效 JWT 的握手被拒绝
- [ ] 用例：已认证用户能订阅到自己的文档进度消息
- [ ] 用例：用户 A 收不到用户 B 的进度消息（**隔离性**）
- [ ] 用例：非管理员无法订阅运维频道
- [ ] 用例：文档处理事件触发后，消息被推送到预期目的地

### 验收标准

- [ ] STOMP 端点可连接（前端不再永远 `disconnected`）
- [ ] 握手有鉴权，`/ws/**` 不再是 `permitAll`
- [ ] 两个场景的消息能实际推送
- [ ] 上述测试通过

### 禁止事项

- ❌ **不要把 `/ws/**` 继续保持为 `permitAll`** —— 这是原缺陷的一部分
- ❌ 不要移除前端的 SSE 降级（它是必要的容错）
- ❌ 不要用广播目的地推送含用户数据的消息（会造成越权泄露）

---

## 任务 10.3 — 接入真实交叉编码器（问题 14）

> 问题详情：`doc/问题清单/14-RerankerService静默降级为非交叉编码器实现.md`

**当前状态**：`RerankerService` 是**字符 n-gram + Jaccard 本地启发式**，零网络调用，与设计的 `bge-reranker-v2-m3` 交叉编码器不符。

- [ ] **10.3.1** 前置：部署本地 reranker 推理服务（**已确认的决策**）
  - **决策：本地部署**（如 Xinference / TEI / text-embeddings-inference 加载 `bge-reranker-v2-m3`），通过 HTTP 调用
  - **理由**：与项目现有架构一致（Ollama、ChromaDB 都跑在本地容器/进程），无外网依赖、无 API 费用、数据不出本机
  - ⚠️ **实测现状（2026-10-06）**：本机**没有任何 reranker 服务**——Ollama 只装了 `qwen3-embedding:4b`，9997/8080 端口无响应，8000 是 ChromaDB
  - ⚠️ 需先评估本机算力（Ollama 已占一部分），并确认模型文件（约 2GB）的获取方式
  - **若部署受阻，停下来上报**，不要实现一个无法验证的 Provider

- [ ] **10.3.2** 实现 Reranker 调用
  - 定义 `RerankerProvider` 接口 + 具体实现（如 `BgeRerankerProvider`）
  - 参考同模块 `DeepSeekLLMProvider` / `OllamaEmbeddingProvider` 的客户端风格

- [ ] **10.3.3** 接通配置
  - `application-p2.yml:32` 已有 `agent-qr.reranker.model: bge-reranker-v2-m3`，但**无任何 Java 读取点**
  - 用 `@Value` 读取并传入模型调用
  - 同时把硬编码的权重常量（`RerankerService.java:27,30`）参数化

- [ ] **10.3.4** 超时与降级
  - 模型调用需设超时，避免拖慢问答响应
  - **保留现有启发式实现作为降级路径**：模型不可用时回退（这本身是有价值的降级设计）
  - 降级时应有 WARN 级日志，**不得静默降级**（原缺陷的教训）

- [ ] **10.3.5** 效果评估（**必须在批次 04 完成后**）
  - 放宽 `final-top-k` 的收益取决于精排质量
  - 建议构造一个小规模评测集（如 20 条 query + 期望命中的 chunk），对比新旧实现的命中率
  - **在报告中给出对比数据**

### 补充测试

- [ ] 用例：模型服务正常时调用真实模型（可 Mock HTTP 验证请求体与解析）
- [ ] 用例：模型服务超时/不可用时降级到启发式，且**有 WARN 日志**
- [ ] 用例：`agent-qr.reranker.model` 配置被读取
- [ ] 用例：权重参数化后可通过配置调整
- [ ] 用例：`rerank` 返回结果数量与 `topK` 一致（回归）

### 验收标准

- [ ] 存在真实的模型调用路径
- [ ] 配置键生效
- [ ] 降级链完整且有日志
- [ ] 效果对比数据已给出
- [ ] 上述测试通过

### 禁止事项

- ❌ **不要删除现有的启发式实现**（它是降级路径）
- ❌ 不要做成"静默降级"——模型不可用时必须留下日志
- ❌ 不要为了跑通而伪造模型返回

---

## 任务 10.4 — 质检失败明细：方案 B 落地（问题 26）

> 问题详情：`doc/问题清单/26-quality_failure表与QualityFailureMapper整体缺失.md`

**已确认方案**：保留 `quality_report.failures` JSON 列方案（代码注释自称"方案 B"），回填设计文档，并修掉去重缺陷。

- [ ] **10.4.1** 回填设计文档（6 处）
  - §8.8.3、§8.8.5、§8.8.6、§8.8.7、§12.0、§12.5
  - 把"`quality_failure` 表 + `QualityFailureMapper`"的描述改为"`quality_report.failures` JSON 列"
  - **注意**：这是设计文档的修改，请与批次 11 的文档回填保持一致的风格

- [ ] **10.4.2** 正式化 `QualityFailure` 为 DTO
  - 当前它无主键、无 `reportId`、无 `@TableName`，处于"实体不像实体、DTO 不像 DTO"的中间态
  - 明确为 DTO，补必要的注解与字段说明

- [ ] **10.4.3** 修掉去重导致的 recordIndex 丢失（**核心缺陷**）
  - 当前 `DataQualityChecker.java:101,134-144` 按 `MD5(ruleName + "|" + reason)` 跨记录去重
  - 后果：1 万条"内容为空"只留 1 条，其 `recordIndex` 仅代表首次出现的位置 → **报告无法回答"具体哪几条失败了"**
  - 改为：**按规则聚合，但保留 recordIndex 列表**（设长度上限，如最多 100 个 + 总数）
  - **注意**：过滤环节用的是另一份未去重的 `failedIndices`（`DataQualityService.java:166-177`），**阻断行为是正确的**，不要改动它

- [ ] **10.4.4** 评估 `failedIndices` 的暴露
  - `QualityReport.java:71-72` 的 `failedIndices` 未加 `@JsonIgnore`，会随详情接口暴露内部索引集合
  - 评估是否需要暴露（若前端不用，建议加 `@JsonIgnore` 或改为不返回）

- [ ] **10.4.5** 评估 `failures` JSON 的体积风险
  - `FormatRule.java:57-58` 会把具体值拼进 reason，高基数字段下明细行数可能接近记录数
  - 评估是否可能触达 MySQL `max_allowed_packet`，必要时加条数上限

### 补充测试

- [ ] 用例：多条记录因同一规则失败时，报告能定位到**具体记录**（**这条用例能拦住原缺陷**）
- [ ] 用例：失败明细的条数不超过设定的上限
- [ ] 用例：阻断行为不受影响（超阈值仍正确阻断）—— **回归，重要**
- [ ] 用例：`QualityFailure` 的序列化/反序列化往返正确

### 验收标准

- [ ] 设计文档 6 处已回填
- [ ] `QualityFailure` 定位为 DTO
- [ ] 失败明细可定位到具体记录索引
- [ ] 阻断行为未受影响
- [ ] 上述测试通过

### 禁止事项

- ❌ **不要建 `quality_failure` 表**（已确认采用 JSON 列方案）
- ❌ 不要修改阻断判断逻辑（它是正确的）
- ❌ 不要移除 `failedIndices`（过滤环节依赖它）

---

## 任务 10.5 — 剩余死配置接线（问题 38 收尾）

> 问题详情：`doc/问题清单/38-配置项与代码实现脱节.md`
> **已完成的部分**：`reranker.model`（10.3）、`collection-prefix` / `auto-dimension-check`（批次 07 的 7.1）、`routing.mode`（批次 07 的 7.5）、`read-replica-fallback-to-primary`（批次 02 的 2.1.3）

> **注意区分两类配置**（详见问题 38）：
> - **A 类（误导性）**：注释声称生效、实际无读取点 → **必须处理**（接通或删除并修正注释）
> - **B 类（诚实占位符）**：注释已标注"暂未使用" → 属预留配置，**接通即可，不必按缺陷对待**

**已确认策略**：能接线就接线，接不了就删；尽可能保留配置能力。

- [ ] **10.5.1** 接线后端缓存配置
  - `application-p2.yml:61-63` 的 `agent-qr.cache.max-size` / `ttl-hours` 当前**无读取点**
  - `CaffeineConfig.java:33-34` 硬编码 10000 / 1h
  - 改为 `@Value` 读取（成本极低）

- [ ] **10.5.2** 接线前端配置
  - `.env.development` 中三个键无任何引用：
    - `VITE_SSE_TIMEOUT`（SSE 无超时配置）
    - `VITE_TOKEN_REFRESH_AHEAD`（提前刷新时间硬编码为 `60_000`，见 `api/index.ts:155`）
    - `VITE_SSE_MAX_RECONNECT`（SSE 无重连配置）
  - 三处都改为读取环境变量

- [ ] **10.5.3** `rag.embedding.write-to-chromadb` 接线为「跳过向量化」开关（**已确认的决策**）
  - `application-p1.yml:29` 该键当前**无读取点**，注释写"P1: 关闭 ChromaDB 写入"而值为 `true`（自相矛盾）
  - **决策：接线**——语义为"是否执行向量化"：关闭时 chunk **只到 `INDEXED` 状态**，不写 ChromaDB
  - **理由**：
    - 该键**在历史上确实起过作用**——实测发现较老的切片（id `7362`、`7386-7397`）**没有向量**，较新的（`11771+`）有，与注释说的"P1 关闭写入"吻合
    - 语义与新的双状态机**自洽**：关闭 = 只到 INDEXED
    - 保留运维应急能力（Ollama/ChromaDB 挂掉或想省算力时可一键停）
  - **需实现**：
    - 在 `ChunkEmbeddingBatchListener` 中加分支判断该开关
    - ⚠️ **需定义**：关闭期间产生的数据，在开关重新打开后**是否补做向量化**——请评估后决定并在报告中说明
  - **同时修正注释**使其与取值一致

- [ ] **10.5.4** 反向补齐硬编码值
  - 以下值应参数化（部分已在其他批次处理，此处只做核对）：
    - `BatchEmbeddingService` 的队列容量 → 批次 05 已处理
    - `RestApiConnector` 的 `maxPages` → 批次 05 已处理
    - `RerankerService` 的权重常量 → 本批次 10.3 已处理
    - `CaffeineConfig` 的 maxSize/TTL → 本任务 10.5.1 处理
  - 核对是否还有遗漏的硬编码可配置值

- [ ] **10.5.5** 建立防漂移检查（**建议**）
  - 在 CI 或构建脚本中增加一个检查：扫描 yml 中的所有键，确认每个键在全仓库存在读取点（可容忍白名单）
  - 目的：防止再次积累死配置

### 补充测试

- [ ] 用例：修改 `agent-qr.cache.max-size` 后，Caffeine 缓存的 maximumSize 随之变化
- [ ] 用例：修改 `VITE_TOKEN_REFRESH_AHEAD` 后，提前刷新时间随之变化
- [ ] 用例：（若 10.5.5 实现）检查脚本能发现人为构造的死配置

### 验收标准

- [ ] `cache.*` 配置生效
- [ ] 三个 `VITE_*` 配置生效
- [ ] `write-to-chromadb` 已接线或删除（注释与取值一致）
- [ ] 无残留的死配置（`collection-prefix`、`reranker.model`、`routing.mode` 已在其他批次接通）
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要删除 `agent-qr.reranker.*` 的配置键（10.3 已接线）
- ❌ 不要为了"消灭死配置"而删除仍在使用的键 —— 先 grep 确认无读取点再动手

---

## 批次验收

- [ ] 任务 10.1 - 10.5 全部完成
- [ ] 全仓库无残留的无读取方配置键（可用 10.5.5 的检查脚本验证）
- [ ] 项目可编译，`mvn test` 通过
- [ ] 已更新 `progress.md`

## 回归验证建议

1. 在质检规则页新增一条规则，触发一次质检，确认新规则生效
2. 前端连接 WebSocket，上传一个文档，观察是否收到进度推送
3. 提一个需要精排的问题，对比接入真实 Reranker 前后的结果质量
4. 查看质检报告详情，确认能定位到具体失败记录
5. 修改 `cache.max-size` 与 `VITE_TOKEN_REFRESH_AHEAD`，确认行为变化

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-10-决策类修复.md 的全部任务。

5 个任务相互独立，无强制顺序。建议先启动 10.1（工作量最大）。
注意：10.1 与 10.4 都修改 DataQualityChecker，请合并给同一 agent 或串行处理。

所有决策已确认（见文件顶部的决策表），请直接执行，不要重新决策：
  14 → 接入真实交叉编码器
  26 → 保留 JSON 列方案，回填文档 + 修去重
  34 → 补齐后端 STOMP（场景：文档处理进度 + 运维告警通知）
  35 → 补齐后端规则 CRUD 与动态加载
  38 → 能接线就接线，接不了就删

两处需先确认再实施：
  10.3 需确认 bge-reranker-v2-m3 的部署形态；若无法实际部署验证，请上报。
  10.5.3 需确认 write-to-chromadb 的语义。

只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
不输出任何 API Key、Token、密码、连接串原文。
```
