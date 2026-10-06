# 14 · RerankerService 静默降级为非交叉编码器实现

> **严重程度**：🟠 中高
> **所属模块**：agent-qr-rag（RerankerService）
> **设计依据**：《系统详细设计说明书》§6.2.5.3 RerankerService（bge-reranker-v2-m3 交叉编码器）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

设计 §6.2.5.3 要求 `RerankerService` 调用 `bge-reranker-v2-m3` 交叉编码器对候选文档做精排。实际实现是**纯本地的字符 n-gram + Jaccard 相似度启发式打分**，**没有任何网络调用**。

这意味着 RAG 管道中的"Rerank 精排"层并非设计所述的语义交叉编码器，而是一个词面重叠度计算。类名、方法名、调用链都保持了"Rerank"的外观，因此从架构图或代码调用关系上**看不出降级**——只有读实现才能发现。

复盘报告的 4 项偏差中**未记录此项**。

---

## 二、推断依据

### 依据 1：核心打分逻辑是 n-gram 与 Jaccard

`agent-qr-rag/src/main/java/org/example/agent_qr/rag/retriever/RerankerService.java:43-64`

```java
public List<RetrievedDocument> rerank(String query, List<RetrievedDocument> candidates, int topK) {
    ...
    Set<String> queryNgrams = extractNgrams(query);

    List<RetrievedDocument> reranked = candidates.stream()
            .peek(doc -> {
                double textScore = calculateJaccardSimilarity(queryNgrams, doc.getContent());
                double combinedScore = ORIGINAL_WEIGHT
                        * (doc.getSimilarity() != null ? doc.getSimilarity() : 0.0)
                        + TEXT_RELEVANCE_WEIGHT * textScore;
                doc.setSimilarity(combinedScore);
            })
            .sorted(Comparator.comparing(RetrievedDocument::getSimilarity).reversed())
            .limit(topK)
            .toList();
    ...
}
```

### 依据 2：类注释明确说明是本地启发式

`RerankerService.java:15-16`

```java
* 对粗排候选结果进行精排。使用字符级 n-gram（unigram + bigram）
* 提取中文词项，计算 Jaccard 相似度作为文本相关性分数，
```

### 依据 3：无任何网络调用

```bash
grep -n "WebClient\|RestTemplate\|HttpClient\|chatModel\|ChatLanguageModel" RerankerService.java
# 零命中
```

对比同模块的 `DeepSeekLLMProvider`（使用 WebClient）与 `OllamaEmbeddingProvider`（HTTP 调用），RerankerService 完全没有任何客户端依赖。

### 依据 4：模型配置项是死配置

`agent-qr-web/src/main/resources/application-p2.yml:32` 存在：

```yaml
agent-qr:
  reranker:
    model: bge-reranker-v2-m3
```

但全仓库检索 `agent-qr.reranker` **无任何 Java 读取点**（`RerankerService` 无 `@Value`；权重是硬编码常量，见 `:27`、`:30`）。即配置项声称使用该模型，代码从未使用。

### 依据 5：权重是常量而非可调参数

`RerankerService.java:27,30` 的 `ORIGINAL_WEIGHT` / `TEXT_RELEVANCE_WEIGHT` 为硬编码常量（0.4 / 0.6），无法通过配置调整。

---

## 三、影响范围

1. **检索质量与设计预期不符**：交叉编码器能捕捉 query 与 doc 的语义交互，而字符 n-gram + Jaccard 只能衡量**字面重叠**。对"同义不同词"的查询（如问"如何离职"而文档写"解除劳动合同流程"）精排效果显著更弱。
2. **与偏差 3 叠加评估失真**：偏差 3 的讨论焦点是 Top-K 截断，但若"精排"层本身选不出正确的那 K 条，放宽 K 的收益会被削弱。**修偏差 3 时应同时评估本项**。
3. **能力被高估**：文档、汇报材料、代码注释都呈现为"已实现 Rerank"，实际未达设计目标。
4. **运维无效**：修改 `agent-qr.reranker.model` 不会有任何效果。

---

## 四、修复方向

1. **二选一，但必须选一个**：
   - **方案 A（对齐设计）**：接入真实的 `bge-reranker-v2-m3`（本地部署或 HTTP 服务），使 `agent-qr.reranker.model` 配置项真正生效；
   - **方案 B（对齐实现）**：若短期内不打算接模型，应把类名/文档/配置统一改为"文本相关性启发式重排"，避免能力被误认为已达到设计目标。
2. **无论选哪个，都建议保留可配置的降级链**：当模型服务不可用时回退到当前启发式实现（这本身是有价值的降级设计，只是不应作为唯一实现）。
3. **补充效果评测**：Rerank 是效果类组件，无法通过"编译通过"验证，需要建立小规模评测集（当前无任何测试，见文档 33）。

---

## 五、核查边界

- 静态分析，未运行服务、未实测排序质量。
- 未评估当前启发式实现在真实中文语料下的效果（可能对某些场景够用，但无法通过静态阅读判断）。
- 未确认项目是否在其他模块（如 `agent-qr-statistics`）另有真正的 Rerank 实现——全仓检索 `rerank` 仅命中本类。
