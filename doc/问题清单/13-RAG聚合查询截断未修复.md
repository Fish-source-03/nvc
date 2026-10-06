# 13 · RAG 聚合查询截断未修复（复盘报告偏差 3）

> **严重程度**：🔴 高
> **所属模块**：agent-qr-rag（HybridRetriever、ContextTokenManager、ChatQueryService）
> **设计依据**：《系统详细设计说明书》§17.2（混合检索 + RRF 融合 + Rerank）、SRS §5.1.1
> **复盘报告对应**：偏差 3（`doc/项目复盘报告.md:68-78`）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析 + 配置文件核对（未编译、未运行）

---

## 一、问题描述

RAG 检索管道存在多层串行截断，对"列出所有 X""有多少 Y"这类列举/统计型查询会返回不完整结果。复盘报告已将其列为偏差 3，并给出三层递进补救方案。

核查确认：**补救方案的第一步只做了一半，第二、三步完全未启动**。

| 层级 | 位置 | 当前值 | 方案目标 | 状态 |
|---|---|---|---|---|
| L1 结构化过滤 | `ChunkStructuredFilterMapper` 各 SQL | `LIMIT 500` | 500 | ✅ 维持 |
| L2 宽召回 | `application-p2.yml:25` | `wide-top-k: 30` | 30 | ✅ 已调整 |
| L3 精排 | `application-p2.yml:26` | **`final-top-k: 15`** | 15 → **30** | ❌ **未调整** |
| L4 Token 预算 | `application-p2.yml:28` + `ContextTokenManager` | `max-context-tokens: 8000` | — | ✅ 按序累加、超预算即 break |

同时，方案第二层要求的 `QueryIntentClassifier`（查询意图分类）与 `AggregationQueryService`（聚合查询分支）在代码中**均不存在**。

---

## 二、推断依据

### 依据 1：`final-top-k` 仍为 15

`agent-qr-web/src/main/resources/application-p2.yml:25-26`

```yaml
wide-top-k: 30              # 宽召回 TopK（每次从 ChromaDB/BM25 各拉取 30 条候选）
final-top-k: 15             # Reranker 精排后的候选数（由 token 预算层进一步裁剪）
```

复盘报告 §77 明确建议短期配置调优 `finalTopK: 15→30`，当前未执行。

补充发现：代码内的默认值仍是 20（`HybridRetriever.java:72`），而 yml 覆盖为 30——两处不一致，若 yml 失效将静默回落到 20。

### 依据 2：聚合查询分支的两个类均不存在

```bash
grep -rn "QueryIntentClassifier\|AggregationQueryService" --include=*.java .
# 零命中（命名仅出现在 doc/未来补充/RAG聚合查询截断问题解决方案.md）
```

### 依据 3：截断链路结构原样保留

`HybridRetriever.java` 的检索流程（`:92-126`）仍是"双路召回 → RRF 融合 → Rerank → TopK"的单一路径，无分支。`ChatQueryService` 的两个入口（`ask`、`askStream`）均直接走该路径，没有意图判断环节。

### 依据 4：L4 Token 预算是有意设计，属正常行为

`ContextTokenManager.java:105` 按序累加并在超出预算时 `break`，`max-context-tokens: 8000`（`application-p2.yml:28`）、回复预留 2048。这一层是必要的上下文保护，但**它对聚合查询同样生效**——即使前几层放宽，聚合结果仍会被 Token 预算裁剪，这正是方案文档强调"聚合路径需用紧凑格式（如 JSON 数组）提升 Token 密度"的原因。

### 依据 5：无测试与验证

`agent-qr-rag/src/test` 不存在，方案文档 §6.1/§6.2 规划的功能验证与回归验证用例均未创建。复盘报告 §74 举的实例"公司有哪些人已经离职，超过 15 人时只返回 15 人"无法在现有测试体系中复现。

---

## 三、影响范围

1. **用户信任受损**：列举/统计类查询返回不完整结果，且系统**不会提示结果被截断**——用户无法辨别"只有 15 条"和"显示了前 15 条"。
2. **与偏差 2 叠加**：精确查询走语义检索（文档 12），再叠加 Top-K 截断，形成"既不精确也不完整"。
3. **影响面覆盖高频场景**：如"有哪些部门""列出所有数据源""多少份文档待审核"等，都是用户最常用的问答类型。

---

## 四、修复方向

按方案文档 `doc/未来补充/RAG聚合查询截断问题解决方案.md` 的三层递进实施：

1. **第一层（5 分钟，建议立即执行）**：把 `application-p2.yml:26` 的 `final-top-k` 从 15 调至 30，并统一 `HybridRetriever.java:72` 的代码默认值。这是最低成本的改善。
2. **第二层（核心修复）**：
   - 新增 `QueryIntentClassifier`（规则匹配优先，LLM 兜底），在检索入口分流；
   - 新增 `AggregationQueryService`，对聚合类查询走"SQL 过滤 → 全量取回（安全上限 2000）→ 紧凑格式化"；
   - 扩展 `ContextTokenManager` 与 `PromptTemplate` 支持紧凑格式；
   - 集成到 `ChatQueryService`。
   - **前置依赖**：需要先补 `FilterConditionExtractor`（见文档 12），方案文档 §2.1 已说明该依赖关系。
3. **第三层（长期）**：NL-to-SQL 直查。
4. **兜底建议**：在第二层落地前，至少在回答中**显式标注"结果可能不完整"**，给用户一个诚实的信号。

---

## 五、核查边界

- 静态分析，未运行服务，未实测列举型查询的实际返回条数。
- 未评估 `final-top-k` 调至 30 后对响应延迟与 Token 消耗的影响（方案文档 §6.3 有性能验证设计）。
- 未检查 `application-p2.yml` 是否被外部配置覆盖（见文档 04 的 profile 叠加问题，需一并考虑）。
