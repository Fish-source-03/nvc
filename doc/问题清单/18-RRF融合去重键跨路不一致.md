# 18 · RRF 融合去重键跨路不一致

> **严重程度**：🟠 中
> **所属模块**：agent-qr-rag（HybridRetriever、ChromaRetriever、BM25Retriever）
> **设计依据**：《系统详细设计说明书》§6.2.5 HybridRetriever（双路召回 + RRF 融合）、§17.2
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

`HybridRetriever` 的 RRF 融合需要对两路召回结果做**去重合并**：同一个切片若同时被语义检索和关键词检索召回，应合并计分。设计 §6.2.5 原文使用 `doc.getContent().hashCode()` 作为去重键，可跨路对齐。

实际实现改为使用 `RetrievedDocument.documentId` 作为去重键，但**两路赋予该字段的语义不同**：

- 语义路（`ChromaRetriever`）：`documentId` = ChromaDB 的 `embeddingId`；
- 关键词路（`BM25Retriever`）：`documentId` = chunkId 的字符串形式。

两者命名空间不同，同一切片在两路产生**两个不同的 key**，因此无法合并 RRF 分数。后果是该切片在融合结果中出现两次，各自只带单路的分数（被低估），并挤占 Top-K 名额。

---

## 二、推断依据

### 依据 1：语义路的 documentId 来自 ChromaDB

`agent-qr-rag/src/main/java/org/example/agent_qr/rag/retriever/ChromaRetriever.java:65` 附近，构造 `RetrievedDocument` 时把 ChromaDB 返回的 embedding 记录标识写入 `documentId`。

### 依据 2：关键词路的 documentId 是 chunkId 字符串

`agent-qr-rag/.../retriever/BM25Retriever.java:97` 附近，从 Lucene 文档取出的 `chunkId` 被转为字符串写入 `documentId`。

### 依据 3：融合处按 documentId 去重

`agent-qr-rag/.../retriever/HybridRetriever.java:143,149,157`

```java
// RRF 融合：以 documentId 为 key 累加 1/(k + rank)
```

由于依据 1、2 的命名空间不同，同一个物理切片在两路中的 key 不相等，融合逻辑会把它们当成两个不同文档处理。

### 依据 4：设计原文使用内容哈希

设计 §6.2.5 明确给出 `doc.getContent().hashCode()` 作为去重键。该方案虽然脆弱（内容相同的不同切片会被合并），但能保证跨路对齐。当前实现换成了更"规范"的 ID，却忽略了两个 ID 的语义不同——属于重构引入的回归。

---

## 三、影响范围

1. **融合排序质量下降**：真正的多路命中切片拿不到"双路加分"，而单路命中切片因不与其他条目冲突反而容易上位。RRF 的核心价值（多路一致 → 更高置信度）被削弱。
2. **结果重复**：同一切片可能以两条结果的形式出现在最终上下文里，浪费 Token 预算（叠加文档 13 的 L4 层裁剪，挤掉其他候选）。
3. **评估失真**：文档 13 讨论 Top-K 截断收益时，若融合层本身选错了候选，放宽 K 的收益会被削弱。

---

## 四、修复方向

1. **统一两路的标识语义**（推荐）：让 `ChromaRetriever` 也以 `chunkId` 作为 `documentId`。ChromaDB 的 metadata 中应已存有 chunkId（若不存则需补充写入），这是最干净的方案。
2. **或改用可跨路对齐的复合键**：如 `documentId` 统一为 chunkId，`embeddingId` 另设字段仅用于删除操作。
3. **补测试**：至少一条"同一 chunk 被两路同时召回时应合并为一个结果且分数叠加"的用例。当前 `agent-qr-rag/src/test` 不存在，该类回归无法被拦截。

---

## 五、核查边界

- 静态分析，未运行检索、未观测实际融合结果。
- 未确认 ChromaDB metadata 中是否已写入 chunkId（决定修复方案 1 的可行性）——需在实施时确认。
- 未评估当前线上数据中重复结果的实际比例。
