# 12 · LLM 结构化过滤链路不可达（复盘报告偏差 2）

> **严重程度**：🟠 中高
> **所属模块**：agent-qr-rag（HybridRetriever、StructuredFilterService、ChatQueryService）
> **设计依据**：《系统详细设计说明书》§8.11 MySQL 前置结构化过滤、§5.1.4（SRS 结构化条件查询）
> **复盘报告对应**：偏差 2（`doc/项目复盘报告.md:56-66`）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

复盘报告将偏差 2 描述为"`FilterConditionExtractor`（LLM 自动提取过滤条件）未实现，但 SQL 层基础过滤能力已就绪，用户无法直接用自然语言触发"。

核查结论：**实际状态比报告描述的更彻底地不可用**。不只是"缺 LLM 提取组件"，而是：

- `FilterConditionExtractor` 类不存在（符合报告描述）；
- **更关键的是 `FilterCondition` 对象在整个代码库中从未被构造过**——连"人工预先配置字段映射"的入口都不存在；
- 调用方恒传空列表，且没有任何 `enabled` 配置项可用于灰度开关。

即：SQL 层的过滤基础设施确实写好了，但**没有任何代码路径能走到它**，整条链路完全不可达。

---

## 二、推断依据

### 依据 1：`FilterConditionExtractor` 不存在

```bash
grep -rn "FilterConditionExtractor" --include=*.java .
# 零命中（命中的全部位于 doc/ 下的方案文档）
```

### 依据 2：`FilterCondition` 从未被构造（关键证据）

```bash
grep -rn "new FilterCondition\|FilterCondition.builder" --include=*.java . | grep -v target
# 零命中
```

全仓库对 `FilterCondition` 的引用仅有 3 处，且**全部是 `HybridRetriever` 的形参声明**：

| 位置 | 性质 |
|---|---|
| `agent-qr-rag/.../retriever/HybridRetriever.java:89` | 方法形参 |
| `HybridRetriever.java:94` | 传参 |
| `HybridRetriever.java:97` | 传参 |

没有任何构造点，说明这个类型只有"被消费"的接口，没有"被生产"的来源。

### 依据 3：调用方恒传空列表

`agent-qr-rag/.../service/ChatQueryService.java:114` 与 `:198`

```java
hybridSearch(query, queryEmbedding, routing, List.of())
```

两个调用点（同步 `ask` 与流式 `askStream`）均传入 `List.of()` 空列表。因此 `HybridRetriever` 内部的过滤分支虽然存在，运行时永远不会被激活。

### 依据 4：不存在灰度开关

```bash
grep -ri "structured" agent-qr-web/src/main/resources/*.yml
# 零命中
```

复盘报告提到的"默认 `enabled: false`"配置项在代码与配置层均无痕迹——该表述仅存在于方案文档 `doc/未来补充/结构化字段过滤SQL-LLM自动提取启用方案.md` 的设计中。

### 依据 5：SQL 层基础设施确实存在（与报告一致的部分）

- `StructuredFilterService.java:49-102`：`filterChunkIds` 实现完整；
- `ChunkStructuredFilterMapper.java:28-51`：范围查询 SQL 齐备；
- `HybridRetriever.java:97-115`：调用点已接好。

因此报告"基础设施已就绪，LLM 提取组件待开发"的判断在**前半句上是准确的**，但低估了问题的完整性——基础设施虽在，却是"接好线但没通电"。

### 依据 6：无测试

`agent-qr-rag/src/test` 不存在，方案文档 §7.1 规划的 `FilterConditionExtractorTest` 未创建。

---

## 三、影响范围

1. **精确查询走语义检索**：SRS §5.1.4 举例"月薪大于 1 万的研发部员工有哪些"，实际会走语义相似度检索而非 SQL 精确过滤，结果精度与完整性都无法保证。
2. **与偏差 3 叠加**：精确查询往往同时是列举型查询，会再叠加上 Top-K 截断（见文档 13），形成"既不精确也不完整"的双重问题。
3. **数据规模扩大后会更明显**：语义检索在候选量大时的召回质量下降，而结构化过滤恰是为此设计的。

---

## 四、修复方向

方案文档 `doc/未来补充/结构化字段过滤SQL-LLM自动提取启用方案.md` 已给出完整设计（`FilterConditionExtractor` 组件 + 降级策略 + 灰度验证），建议按序实施：

1. **补 `FilterConditionExtractor`**（LLM 从自然语言提取过滤条件），并接入 `ChatQueryService` 的两个调用点，替换当前的 `List.of()`。
2. **补 `enabled: false` 灰度开关**，默认关闭，按方案文档的灰度验证步骤逐步开启。
3. **修 `FilterCondition.operator` 未生效的问题**（见文档 18）——否则提取出"小于 X"的条件也会被错误解析。
4. **前置检查**：方案文档 §3.1 要求先确认 `kb_chunk_structured` 表中有实际数据。本次核查未验证该表的数据覆盖率，建议实施前先确认（该表由 ETL 管道写入，见文档 20）。

---

## 五、核查边界

- 静态分析，未运行服务，未实测"月薪大于 1 万"类查询的实际返回。
- 未连接数据库确认 `kb_chunk_structured` 表的实际数据量与字段覆盖率——这直接影响方案文档"数据依赖未就绪"的判断是否仍然成立。
- 未评估 LLM 提取引入的延迟增量（方案文档 §601 有风险与降级设计）。
