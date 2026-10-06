# 批次 09 · 独立问题修复

> **涵盖问题**：18（RRF 去重键）、25（CharsetDetector）、33 断裂 1（知识库筛选参数）、36（生产 API 双前缀）、40（模块依赖）、42（删除并发保护）、43（ETL 半结构化）
> **前置依赖**：批次 01（测试基建）
> **批次内顺序**：**无顺序要求**，7 个任务相互独立，可任意顺序执行，也可再拆分给多个子 agent 并行
> **可并行**：与批次 02、03、04、06、08 无文件交集

---

## 批次目标

集中清理一批彼此独立、无交叉影响的问题。

> 本批次是"独立问题"的集合——这些任务之间**没有顺序约束**，也不与其他批次产生干扰。选择它们的标准是：修复不依赖其他批次，也不会破坏其他批次的修复。

---

## 涉及文件（按任务隔离，互不重叠）

| 任务 | 问题 | 涉及文件 |
|---|---|---|
| 9.1 | 18 | `agent-qr-rag/.../retriever/HybridRetriever.java`、`ChromaRetriever.java`、`BM25Retriever.java` |
| 9.2 | 25 | `agent-qr-data-quality/.../util/CharsetDetector.java`、`rule/EncodingRule.java` |
| 9.3 | 33(断裂1) | `agent-qr-knowledge/.../controller/KnowledgeController.java`、`service/DocumentQueryService.java`、`mapper/DocumentMapper.java` |
| 9.4 | 36 | `agent-qr-web-frontend/.env.production`、`src/api/index.ts` |
| 9.5 | 40 | `agent-qr-statistics/pom.xml`、`agent-qr-data-quality/pom.xml`、`agent-qr-compensation/pom.xml` |
| 9.6 | 42 | `agent-qr-knowledge/.../service/DocumentCommandService.java`、`mapper/DocumentMapper.java` |
| 9.7 | 43 | `agent-qr-etl/.../normalizer/DataNormalizer.java`、`converter/StructuredDataConverter.java` |

**不得修改**：本批次之外的任何文件。

> **注意 9.3 与 9.6 都改 `DocumentMapper`**——若并行执行，请合并给同一个 agent，或串行处理。

---

## 任务 9.1 — RRF 融合去重键跨路不一致（问题 18）

> 问题详情：`doc/问题清单/18-RRF融合去重键跨路不一致.md`

- [ ] **9.1.1** 统一两路的标识语义
  - 语义路（`ChromaRetriever`）：`documentId` = ChromaDB 的 `embeddingId`
  - 关键词路（`BM25Retriever`）：`documentId` = chunkId 字符串
  - 两者命名空间不同 → 同一切片在两路产生两个 key → RRF 无法合并分数，导致**结果重复且分数被低估**
  - **推荐方案**：让 `ChromaRetriever` 也以 `chunkId` 作为 `documentId`
  - **前置检查**：确认 ChromaDB metadata 中是否已存有 chunkId；若没有需先补充写入

- [ ] **9.1.2** 若无法统一，改用可跨路对齐的复合键
  - 如 `documentId` 统一为 chunkId，`embeddingId` 另设字段仅用于删除操作

### 补充测试

- [ ] 用例：同一 chunk 被两路同时召回时，融合结果中**只出现一次**且分数叠加（**这条用例能拦住原缺陷**）
- [ ] 用例：仅被一路召回的 chunk 正常保留

### 验收标准

- [ ] 跨路去重键一致
- [ ] 融合结果无重复
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要改回 `content.hashCode()`（设计原文方案，会把内容相同的不同切片错误合并）
- ❌ 不要修改 RRF 的分数计算公式

---

## 任务 9.2 — CharsetDetector 编码检测（问题 25）

> 问题详情：`doc/问题清单/25-CharsetDetector编码检测与转码形同虚设.md`

- [ ] **9.2.1** 把检测移到字节层
  - 当前 `detect(String text)` 接收**已解码的 String**，方法内又用 JVM 默认字符集重新编码为字节再检测
  - 结果：检测的是"默认字符集"而非文件真实编码；在 UTF-8 的 JVM 上**恒返回 UTF-8**
  - 改为：入参改为 `byte[]`（或 `InputStream`），在**读取文件/流的第一时间**检测

- [ ] **9.2.2** 重构回退逻辑
  - 当前回退循环用 `new String(text.getBytes(charset), charset)` 与原串比较
  - 列表首项是 UTF-8，对任何合法 String 都能无损往返 → **循环总是返回 UTF-8**，判别力为零
  - 改用可判别的方式：统计非法字节序列数量、检查 BOM、尝试解码后统计替换字符 `�` 的数量

- [ ] **9.2.3** 实现转码（设计 §17.5 步骤 ③）
  - 全仓库 `转码` / `transcode` 零命中——当前只做"标记"，不做转换
  - 按检测结果解码后以 UTF-8 写入下游

- [ ] **9.2.4** 说明置信度分支
  - juniversalchardet 的 `UniversalDetector` 本身不暴露置信度（只返回 charset 名或 null）
  - 设计 §17.5 的"≥ 0.8 才采纳"无法直接对应——**在报告中说明实际采用的处理方式**，不要留一个假的阈值常量

### 补充测试

- [ ] 用例：GBK 编码的字节流被正确识别为 GBK（**这条用例能拦住原缺陷**）
- [ ] 用例：UTF-8 带 BOM 的字节流被正确识别
- [ ] 用例：UTF-8 无 BOM 被正确识别
- [ ] 用例：纯 ASCII 的处理结果符合预期

### 验收标准

- [ ] 检测在字节层进行
- [ ] 回退逻辑有实际判别力
- [ ] 转码链路存在
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要删除 `EncodingRule`（它负责记录质检结果，与本任务互补）
- ❌ 不要保留恒真的回退循环

---

## 任务 9.3 — 知识库列表筛选参数（问题 33 断裂 1）

> 问题详情：`doc/问题清单/33-前后端契约断裂.md`（断裂 1）

- [ ] **9.3.1** 补齐 `domain` / `sensitivityLevel` 查询参数
  - 前端 `src/api/knowledge.ts:17-18` 与 `KnowledgeView.vue:43-45` 已经在传这两个参数
  - 后端 `KnowledgeController.listDocuments` 只声明了 `page` / `size`，被 Spring **静默忽略**
  - 后果：UI 上有"业务域/密级"筛选控件，操作后数据毫无变化，且不报错

- [ ] **9.3.2** 透传到查询条件
  - 需同步修改 `DocumentMapper` 的查询 SQL

- [ ] **9.3.3** 与 ABAC 的关系（**需注意**）
  - 这两个参数是"用户主动缩小范围"，而批次 03 的任务 3.5 处理的是"系统强制限制范围"
  - 本任务只做筛选，**不要**在此处加入权限判断（避免与 3.5 重复或冲突）
  - 但需确认：筛选出的结果仍在用户的 `allowedDomains` 范围内（若 3.5 已在检索侧兜底，此处无需重复）

### 补充测试

- [ ] 用例：传入 `domain` 时结果被正确过滤（**这条用例能拦住原缺陷**）
- [ ] 用例：传入 `sensitivityLevel` 时结果被正确过滤
- [ ] 用例：两个参数都不传时行为与修复前一致（回归）
- [ ] 用例：参数为非法值时返回空或报错（需明确策略）

### 验收标准

- [ ] 两个参数被后端接收并生效
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要修改 `AdminController.listUsers`（那是批次 03 的任务 3.3）
- ❌ 不要在此处加入 ABAC 判定（属批次 03 范围）

---

## 任务 9.4 — 生产环境 API 路径双前缀（问题 36）

> 问题详情：`doc/问题清单/36-生产环境API路径双前缀.md`

- [ ] **9.4.1** 修正 `.env.production` 的 `VITE_API_BASE_URL`
  - 当前为 `/api`，而所有 API 调用自身以 `/api/...` 开头 → 拼接后为 `/api/api/auth/login`
  - **推荐方案**：改为空字符串（让调用路径自带的 `/api` 生效），改动面最小
  - **替代方案**：改为 `'/'` 并去掉所有 `src/api/*.ts` 中的 `/api` 前缀（改动面大）

- [ ] **9.4.2** 统一 SSE / refresh 的拼接方式
  - `src/api/index.ts:78,181` 使用模板字符串 `${VITE_API_BASE_URL}/api/auth/refresh`
  - 该写法在两种环境下都有问题（生产双前缀，开发虽正确但不一致）
  - 改为复用统一的 baseURL 拼接逻辑

- [ ] **9.4.3** 确认部署侧的反向代理配置
  - 确认 Nginx / 网关对 `/api` 前缀的转发规则
  - 特别是 SSE 端点的缓冲与超时配置

### 补充测试

- [ ] 用例：生产构建后，拼接出的请求路径为 `/api/auth/login`（而非 `/api/api/auth/login`）
  - 可用一个纯函数测试覆盖 URL 拼接逻辑，避免依赖实际部署
- [ ] 用例：开发环境的请求路径仍为 `http://localhost:9090/api/auth/login`

### 验收标准

- [ ] 生产构建的请求路径无双前缀
- [ ] SSE / refresh 的拼接方式与其他 API 一致
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要修改 `vite.config.ts` 的开发代理配置
- ❌ 不要硬编码生产环境的域名

---

## 任务 9.5 — 模块依赖显式化（问题 40）

> 问题详情：`doc/问题清单/40-模块依赖隐式化与边界模糊.md`

- [ ] **9.5.1** `agent-qr-statistics` 显式声明对 rag 的依赖
  - 源码直接 import 了 `org.example.agent_qr.rag.*`（`StatisticsQueryService`、`FeedbackService`），但 pom **未声明** `agent-qr-rag`，靠 `knowledge → rag` 的传递依赖获得
  - **二选一**：
    - 在 pom 中显式声明 `agent-qr-rag`（最小改动）
    - 或改为通过 `knowledge` 暴露的接口访问（更符合分层，但改动大）

- [ ] **9.5.2** 重新审视 `data-quality → knowledge` 的逆向依赖
  - `DeduplicationRule` 直接注入 `ChunkMapper` 查询 `kb_chunk` 表，属跨模块直接访问数据
  - 建议改为通过 `knowledge` 提供的服务接口，或通过事件获取去重所需的哈希集合
  - **若改动成本过高，至少在报告中说明并保留现状**，不要强行重构引入风险

- [ ] **9.5.3** 删除未使用的依赖
  - `agent-qr-compensation/pom.xml` 声明了 `langchain4j-chroma`，但全模块 `ChromaVectorStore` 零引用
  - **注意**：批次 08 的任务 8.3 可能重新需要该依赖 —— **若 8.3 选择"改为从 ChromaDB 侧驱动扫描"，则本任务应保留该依赖并说明**

- [ ] **9.5.4** 补回 `DuplicateCleanupScanner` 的 javadoc 引用
  - `DuplicateCleanupScanner.java:28` 通过 `{@link DeduplicationRule}` 引用了 data-quality 模块的类，但**未 import**，javadoc 链接失效
  - 补 import 或改为纯文本

### 补充测试

- [ ] 用例：`mvn -q dependency:analyze` 无"未声明但已使用"的告警（或列出剩余项并说明）

### 验收标准

- [ ] `statistics` 的 rag 依赖已显式化
- [ ] 未使用依赖已清理或说明保留原因
- [ ] javadoc 引用有效

### 禁止事项

- ❌ 不要修改 `agent-qr-common` 的依赖（它是基础模块，当前约束是正确的）
- ❌ 不要为了"架构美观"做大范围重构（本任务以显式化为目标，不做分层重构）
- ❌ **不要在批次 08 完成前删除 `langchain4j-chroma`**（8.3 可能需要）

---

## 任务 9.6 — 文档删除的并发与重复保护（问题 42）

> 问题详情：`doc/问题清单/42-知识库文档删除缺少并发与重复保护.md`

- [ ] **9.6.1** 补状态校验
  - `requestDeleteDocument` 当前只校验"文档存在"与"ABAC 权限"，**没有校验文档状态**
  - 设计 §5.2.1 要求状态为 `DELETING` 时报错
  - 补充：`DELETING` 与 `DELETED` 状态都应拒绝

- [ ] **9.6.2** 改为条件更新（并发安全）
  - 仅靠"读-判断-写"存在竞态窗口
  - 改为 `UPDATE kb_document SET status = 'DELETING' WHERE id = #{id} AND status <> 'DELETING'`
  - 依据影响行数判断是否由本次请求成功抢占

- [ ] **9.6.3** 下游幂等（可选）
  - `DocumentDeleteServiceV2` 创建任务前可按 `documentId` 查重
  - 需 `DeleteTaskMapper` 的查询方法 —— 已在批次 01 任务 1.1 提供

### 补充测试

- [ ] 用例：对 `DELETING` 状态的文档再次发起删除，返回错误（**这条用例能拦住原缺陷**）
- [ ] 用例：并发发起两次删除，只有一次成功创建任务（可用线程模拟）
- [ ] 用例：对 `DELETED` 状态的文档发起删除，返回错误
- [ ] 用例：正常删除流程不受影响（回归）

### 验收标准

- [ ] 重复删除被拒绝
- [ ] 并发场景下只有一次生效
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要移除 ABAC 检查（它与状态检查并存）
- ❌ 不要修改 `DocumentMapper.updateStatus` 的签名（其他调用方依赖）

---

## 任务 9.7 — ETL 半结构化路径（问题 43）

> 问题详情：`doc/问题清单/43-ETL半结构化路径canonicalText生成错误.md`

- [ ] **9.7.1** 半结构化路径改用 `mappedRecord` 并实现 `extractSemiStructured`
  - 当前 `DataNormalizer.java:84` 用 `rawRecord.toString()`：
    - 使用的是**未映射**的原始记录（跳过了字段映射环节）
    - `Map.toString()` 产出 `{name=张三, age=25}`，**不是合法 JSON**
  - 设计 §8.9.1 要求 `extractSemiStructured(mapped)`（该方法**全仓不存在**）
  - 改为用 Jackson 序列化为标准 JSON，保留嵌套结构

- [ ] **9.7.2** 对齐非结构化路径的字段来源
  - `DataNormalizer.java:133-142` 的非结构化提取**硬编码** `_content` / `content` / `text` 回退
  - 而质检侧 `CompletenessRule` 的必填字段可由数据源的 `content_fields` 配置覆盖
  - **风险**：若某数据源配置 `content_fields=title,desc`，质检判"通过"的记录在 ETL 侧会产出**空 `canonicalText`**，进而写入空内容切片
  - 两处需对齐

- [ ] **9.7.3** 实现 `_table_comment`（设计 §17.6）
  - 当前段落标题固定为 `【sourceName】`，`_table_comment` 全仓无实现
  - 按其作为段落标题，无该字段时回退到当前行为

- [ ] **9.7.4** 记录 `classify` 的偏差
  - 当前分类依据是"是否含 `_file_type`/`_file_key`"与"是否含嵌套结构"，**不看 `config.sourceType`**
  - 该改动本身合理（基于数据形态更稳健），但属未回填文档的偏差 → 记录到 `progress.md`，由批次 11 统一回填

### 补充测试

- [ ] 用例：半结构化记录产出的 `canonicalText` 是**合法 JSON** 且字段已映射（**这条用例能拦住原缺陷**）
- [ ] 用例：质检通过的非结构化记录不产出空 `canonicalText`
- [ ] 用例：嵌套 Map / List 的结构被保留
- [ ] 用例：结构化记录的处理结果与修复前一致（回归）

### 验收标准

- [ ] 半结构化路径使用映射后的记录与标准 JSON 序列化
- [ ] 非结构化路径与质检侧的字段配置对齐
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要修改 `CanonicalRecord` 的 7 个字段（生产者/消费者/设计文档三方一致，是正确的）
- ❌ 不要改变 `classify` 的分类逻辑（当前实现合理，只需记录偏差）

---

## 批次验收

- [ ] 任务 9.1 - 9.7 全部完成
- [ ] 项目可编译，`mvn test` 通过
- [ ] 已更新 `progress.md`

## 回归验证建议

1. 提一个会同时命中语义与关键词两路的问题，检查返回结果无重复条目
2. 用 GBK 编码的样本数据走一遍质检，确认编码被正确识别
3. 在知识库页面使用"业务域/密级"筛选，确认结果随之变化
4. 删除一个文档两次，确认第二次被拒绝

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-09-独立修复.md 的全部任务。

本批次的 7 个任务相互独立，无顺序要求，可任意顺序执行。
若拆分为多个子 agent 并行，请注意：任务 9.3 与 9.6 都修改 DocumentMapper，
请合并给同一 agent 或串行处理。

注意任务 9.5 与批次 08 的耦合：删除 langchain4j-chroma 依赖前，
请先确认批次 08 的任务 8.3 是否选择了"从 ChromaDB 侧驱动扫描"的方案。

只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
不输出任何 API Key、Token、密码、连接串原文。
```
