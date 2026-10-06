# RAG 聚合查询截断问题 — 完整解决方案

> 状态：待实施 | 日期：2026-06-30 | 依赖：结构化字段过滤SQL-LLM自动提取启用方案.md（FilterConditionExtractor）
> 问题：用户问"公司有哪些人已经离职"，离职人数超过15人时系统只返回15人

---

## 一、问题诊断

### 1.1 症状

列举/统计类自然语言查询（如"列出所有X"、"有多少Y"）返回结果不完整，被截断到约15条。

### 1.2 根因

RAG 检索管道存在 4 层串行截断，语义检索的"找最相关"逻辑与列举类查询的"列出所有"需求存在根本矛盾。

```
ChromaDB/BM25 宽召回 (wideTopK=30，application-p2.yml L25)
  → RRF 融合去重 (HybridRetriever.java L118)
  → Rerank 精排截断 (finalTopK=15，application-p2.yml L26) ★ 主瓶颈
  → Token 预算裁剪 (maxContextTokens=8000，ContextTokenManager.java L83)
```

各截断点详情：

| # | 位置 | 机制 | 当前限制 | 文件:行号 |
|---|------|------|----------|-----------|
| 1 | `ChunkStructuredFilterMapper` | SQL `LIMIT 500` | 500条 | `ChunkStructuredFilterMapper.java:30,39,49,56,64` |
| 2 | `HybridRetriever` — `wideTopK` | ChromaDB/BM25 各拉取 N | 30条 | `HybridRetriever.java:100-101` |
| 3 | `HybridRetriever` / `RerankerService` | Rerank + `.limit(topK)` | **15条** | `RerankerService.java:48,64` |
| 4 | `ContextTokenManager` | 按 token 预算逐个累加文档 | ~5-8篇 | `ContextTokenManager.java:95-110` |

### 1.3 核心矛盾

语义检索（ChromaDB + BM25 + RRF + Rerank）的设计目标是找**最相关的**文档——"哪15个离职员工最匹配查询"。用户需要的是**所有的**匹配记录——"全部离职员工"。

两类查询的本质差异：

| 维度 | 语义类查询 | 列举/统计类查询 |
|------|-----------|---------------|
| 示例 | "离职流程是什么" | "有哪些人已经离职" |
| 需要 | 最相关的文档段落 | **全部**匹配的记录 |
| 排序 | 相关性排序 ✓ | 相关性排序 ✗（按字母/时间） |
| topK 截断 | 合理（取最相关） | **不合理（丢失数据）** |
| 合适工具 | 向量检索 | 结构化 SQL 查询 |

---

## 二、前置依赖

本方案依赖于同目录下 `结构化字段过滤SQL-LLM自动提取启用方案.md` 中已设计的 **`FilterConditionExtractor`** 组件。该组件负责从自然语言中提取结构化过滤条件（如 "离职" → `{fieldName:"status", fieldType:"ENUM", value:"离职"}`），状态为"待实施"，默认 `enabled: false`。

### 2.1 FilterConditionExtractor 与本方案的关系

```
FilterConditionExtractor（文档已设计）    本方案 AggregationQueryService（新增）
        │                                          │
        │  NL → FilterCondition[]                  │  拿到 FilterCondition 后：
        │  "有哪些人离职"                           │  1. 无界 SQL 查询全部匹配 chunkId
        │  → [{fieldName:"status",                 │  2. 跳过 HybridRetriever 和 Reranker
        │      fieldType:"ENUM",                   │  3. 批量取 chunk 内容
        │      value:"离职"}]                       │  4. 紧凑 JSON 格式化
        │                                          │  5. 传给 LLM 生成完整回答
        ▼                                          ▼
    输入到本方案                                这是本方案的核心
```

| | FilterConditionExtractor | AggregationQueryService（本方案新增） |
|---|---|---|
| **做什么** | NL → FilterCondition[]，缩小候选集 | 获取**全部**匹配记录，绕过 topK 截断 |
| **走哪个检索** | 仍走 HybridRetriever（含 finalTopK=15） | **跳过** HybridRetriever，直接 SQL 全量查 |
| **解决截断？** | ❌ 不解决 — 只提高精度，结果仍被截断到15条 | ✅ 解决 — 不经过任何 topK 截断 |

**结论**：`FilterConditionExtractor` 是必要的前置组件（负责"理解用户问的条件"），但单独它不能解决截断问题。本方案在其之上增加聚合查询路径（负责"拿到所有匹配记录"）。

---

## 三、解决方案（三层递进）

### 第一层：短期配置调优

**目的**：立即缓解，无需改代码。**但单独依靠此层不能根本解决问题。**

修改 `application-p2.yml`：

```yaml
agent-qr:
  retrieval:
    wide-top-k: 50          # 30 → 50，扩大宽召回
    final-top-k: 30         # 15 → 30，翻倍精排保留数
    max-context-tokens: 16000  # 8000 → 16000，更多文档可进入上下文
```

**效果**：约 2-3x 提升。但列举200人时依然最多只能拿到30条候选，不够。

---

### 第二层：查询意图分类 + 聚合查询路径（核心修复）

**目的**：根本解决列举/统计类查询的截断问题。

#### 3.2.1 架构总览

```
用户问题："公司有哪些人已经离职"
  │
  ├─ resolveRouting(query)  → 域路由（现有，不变）
  │
  ├─ QueryIntentClassifier.classify(query)  ← 【新增】
  │   │
  │   ├─ SEMANTIC（"离职流程是什么"）
  │   │   └─ 现有混合检索管道（完全不变）
  │   │       ├─ FilterConditionExtractor.extract()（可选开启，提升精度）
  │   │       ├─ StructuredFilterService.filterChunkIds()（LIMIT 500）
  │   │       └─ HybridRetriever.hybridSearch() → finalTopK=15
  │   │
  │   └─ AGGREGATION（"有哪些人已经离职"、"一共有多少"）  ← 【新增分支】
  │       │
  │       ├─ 1. FilterConditionExtractor.extract(query, domain)
  │       │     → [{fieldName:"status", fieldType:"ENUM", value:"离职"}]
  │       │     （复用文档中已设计的 FilterConditionExtractor）
  │       │
  │       ├─ 2. StructuredFilterService.filterChunkIdsUnbounded(domain, conditions)
  │       │     → 返回**全部**匹配 chunkId（去掉 LIMIT 500，加安全上限 2000）
  │       │
  │       ├─ 3. 批量查询 chunk 内容（MyBatis-Plus selectBatchIds）
  │       │     → List<Chunk> 全部匹配的切片
  │       │
  │       ├─ 4. 紧凑上下文构建（ContextTokenManager.buildAggregationContext）
  │       │     从 chunk 的 kb_chunk_structured 字段提取实体信息
  │       │     转为紧凑 JSON：[{"name":"张三","status":"离职","date":"2025-03"}, ...]
  │       │     按 token 预算自适应截断
  │       │
  │       └─ 5. LLM 基于完整数据集生成回答
  │             Prompt 指示 LLM："以下是完整的记录列表，共N条，请全部列出/统计"
  ```

#### 3.2.2 两条路径对比

```
语义类查询（现有管道，不变）：
  FilterConditionExtractor(可选) → StructuredFilter(限500) → HybridRetriever(双路召回) → RRF融合 → Rerank(限15) → TokenBudget → LLM

聚合类查询（新增管道）：
  FilterConditionExtractor → StructuredFilter(无界) → 批量取chunk内容 → 紧凑JSON → TokenBudget → LLM
                           ↑                            ↑
                    去掉 LIMIT 500                 跳过了 HybridRetriever 和 Reranker
```

#### 3.2.3 QueryIntentClassifier — 意图分类器

**新增文件**：`agent-qr-rag/src/main/java/org/example/agent_qr/rag/classifier/QueryIntentClassifier.java`

```java
@Component
public class QueryIntentClassifier {

    enum IntentType {
        AGGREGATION,  // 列举/统计类：需要完整数据集
        SEMANTIC      // 语义类：需要最相关文档
    }

    /**
     * 分类策略：规则匹配优先（零延迟），LLM 分类兜底（仅规则未命中时）。
     */
    public IntentType classify(String query) {
        // 1. 规则匹配
        if (matchesAggregationPattern(query)) {
            return IntentType.AGGREGATION;
        }
        // 2. LLM 兜底（可选，默认关闭，先只用规则）
        // if (llmFallbackEnabled) { return llmClassify(query); }
        return IntentType.SEMANTIC;
    }

    /**
     * 聚合类查询关键词模式。
     * 列举：哪些人|有哪些|列出|都有谁|所有.*的|名单|哪些.*已经|都有哪些
     * 统计：有多少|统计|一共|总计|数量|几个|多少人|计数|总共|汇总
     */
    private boolean matchesAggregationPattern(String query) {
        // 列举模式
        if (query.matches(".*(哪些人|有哪些|列出|都有谁|所有.*的|名单|哪些.*已经|都有哪些|全部.*的).*")) {
            return true;
        }
        // 统计模式
        if (query.matches(".*(有多少|统计|一共|总计|数量|几个|多少人|计数|总共|汇总|合计).*")) {
            return true;
        }
        return false;
    }
}
```

#### 3.2.4 StructuredFilterService — 无界查询扩展

**修改文件**：`StructuredFilterService.java`

新增方法（去掉 subList 截断，SQL 层面加安全上限 2000）：

```java
/**
 * 无界结构化过滤（聚合查询路径专用）。
 * 去掉 500 条硬截断，SQL 层安全上限 2000。
 * 仅当 FilterConditionExtractor 已提取到条件时才调用，
 * 避免无条件全表扫描。
 */
public List<Long> filterChunkIdsUnbounded(String domain, List<FilterCondition> conditions) {
    if (conditions == null || conditions.isEmpty()) {
        return filterChunkIds(domain, conditions); // 回退到原方法
    }

    Set<Long> resultSet = null;
    for (FilterCondition condition : conditions) {
        List<Long> ids = dispatchConditionUnbounded(condition);
        if (resultSet == null) {
            resultSet = new HashSet<>(ids);
        } else {
            resultSet.retainAll(ids);
        }
    }

    // 域过滤
    if (domain != null && !domain.isBlank()) {
        Set<Long> domainIds = new HashSet<>();
        domainIds.addAll(chunkStructuredFilterMapper.selectChunkIdsByDomainUnbounded(domain));
        domainIds.addAll(chunkStructuredFilterMapper.selectChunkIdsByDocumentDomainUnbounded(domain));
        if (resultSet == null) {
            resultSet = domainIds;
        } else {
            resultSet.retainAll(domainIds);
        }
    }

    if (resultSet == null || resultSet.isEmpty()) {
        return List.of();
    }

    List<Long> result = new ArrayList<>(resultSet);
    result.sort(Comparator.naturalOrder());
    // 安全上限 2000（防止极端情况），但不做 500 的硬截断
    if (result.size() > 2000) {
        log.warn("无界查询结果超过安全上限: size={}, domain={}", result.size(), domain);
        result = result.subList(0, 2000);
    }
    return result;
}
```

#### 3.2.5 ChunkStructuredFilterMapper — 新增 SQL

**修改文件**：`ChunkStructuredFilterMapper.java`

在原有 5 条带 `LIMIT 500` 的 SQL 基础上，新增以下方法：

```java
// ========== FilterConditionExtractor 所需（按结构化字段过滤SQL方案文档） ==========

/** 查询域下可用字段定义 */
@Select("SELECT DISTINCT field_name AS fieldName, field_type AS fieldType " +
        "FROM kb_chunk_structured WHERE domain = #{domain} ORDER BY field_name")
List<FieldDefinition> selectDistinctFieldsByDomain(@Param("domain") String domain);

/** 查询枚举字段的所有可选值 */
@Select("SELECT DISTINCT field_value FROM kb_chunk_structured " +
        "WHERE field_name = #{fieldName} AND domain = #{domain} " +
        "AND field_type = 'ENUM' ORDER BY field_value LIMIT 100")
List<String> selectEnumValues(@Param("fieldName") String fieldName,
                               @Param("domain") String domain);

// ========== 聚合查询路径所需（无 LIMIT 500，安全上限 2000） ==========

@Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
        "WHERE field_name = #{fieldName} AND field_value = #{value} ORDER BY chunk_id LIMIT 2000")
List<Long> selectAllChunkIdsByStringValue(@Param("fieldName") String fieldName,
                                           @Param("value") String value);

@Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
        "WHERE field_name = #{fieldName} AND numeric_value >= #{min} AND numeric_value <= #{max} " +
        "ORDER BY chunk_id LIMIT 2000")
List<Long> selectAllChunkIdsByNumberRange(@Param("fieldName") String fieldName,
                                           @Param("min") BigDecimal min,
                                           @Param("max") BigDecimal max);

@Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
        "WHERE field_name = #{fieldName} AND date_value >= #{start} AND date_value <= #{end} " +
        "ORDER BY chunk_id LIMIT 2000")
List<Long> selectAllChunkIdsByDateRange(@Param("fieldName") String fieldName,
                                         @Param("start") LocalDate start,
                                         @Param("end") LocalDate end);

@Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
        "WHERE domain = #{domain} ORDER BY chunk_id LIMIT 2000")
List<Long> selectChunkIdsByDomainUnbounded(@Param("domain") String domain);

@Select("SELECT c.id FROM kb_chunk c INNER JOIN kb_document d ON c.document_id = d.id " +
        "WHERE d.domain = #{domain} AND c.deleted = 0 AND d.deleted = 0 ORDER BY c.id LIMIT 2000")
List<Long> selectChunkIdsByDocumentDomainUnbounded(@Param("domain") String domain);
```

#### 3.2.6 AggregationQueryService — 聚合查询编排

**新增文件**：`agent-qr-rag/src/main/java/org/example/agent_qr/rag/service/AggregationQueryService.java`

```java
@Slf4j
@Service
public class AggregationQueryService {

    @Autowired
    private FilterConditionExtractor filterConditionExtractor;

    @Autowired
    private StructuredFilterService structuredFilterService;

    @Autowired
    private ChunkMapper chunkMapper;  // agent-qr-knowledge 模块

    @Autowired
    private ContextTokenManager contextTokenManager;

    @Autowired
    private PromptTemplate promptTemplate;

    /**
     * 执行聚合查询（列举/统计类问题专用）。
     *
     * @param query   用户自然语言问题
     * @param routing 域路由结果
     * @return 检索结果列表（可能包含大量文档）
     */
    public List<RetrievedDocument> aggregate(String query, DomainRoutingResult routing) {
        String domain = routing != null ? routing.getPrimaryDomain() : null;

        // 1. 提取结构化过滤条件（复用 FilterConditionExtractor）
        List<FilterCondition> conditions;
        try {
            conditions = filterConditionExtractor.extract(query, domain);
        } catch (Exception e) {
            log.warn("聚合查询：过滤条件提取失败，降级空条件", e);
            conditions = List.of();
        }

        if (conditions.isEmpty()) {
            log.info("聚合查询：未提取到过滤条件，降级语义检索");
            return List.of(); // 返回空，ChatQueryService 降级到语义路径
        }

        // 2. 无界查询全部匹配 chunkId
        List<Long> allChunkIds = structuredFilterService.filterChunkIdsUnbounded(domain, conditions);
        log.info("聚合查询：匹配 chunk 数={}, conditions={}", allChunkIds.size(), conditions.size());

        if (allChunkIds.isEmpty()) {
            return List.of();
        }

        // 3. 批量获取 chunk 内容
        List<Chunk> chunks = chunkMapper.selectBatchIds(allChunkIds);

        // 4. 转为 RetrievedDocument 列表
        return chunks.stream()
                .map(chunk -> {
                    RetrievedDocument doc = new RetrievedDocument();
                    doc.setDocumentId(String.valueOf(chunk.getId()));
                    doc.setChunkId(chunk.getId());
                    doc.setContent(chunk.getContent());
                    doc.setDocumentTitle("chunk-" + chunk.getId());
                    doc.setSimilarity(1.0); // 聚合查询无相关性排序
                    return doc;
                })
                .toList();
    }
}
```

#### 3.2.7 ContextTokenManager — 紧凑上下文构建

**修改文件**：`ContextTokenManager.java`

新增方法：

```java
/**
 * 构建聚合查询的紧凑上下文。
 * <p>
 * 与 buildContextWithBudget() 的区别：
 * - 语义路径：保留完整 chunk 文本，按相关性排序
 * - 聚合路径：提取结构化字段为紧凑 JSON，按自然顺序排列
 * <p>
 * 紧凑 JSON 例：[{"name":"张三","status":"离职"},{"name":"李四","status":"离职"},...]
 * 密度对比：完整文本 ~50 tokens/条 vs 紧凑 JSON ~10 tokens/条，密度提升 5x
 * </p>
 *
 * @param documents 全部匹配的文档（已去重、已排序）
 * @param promptBase 系统提示词基础文本
 * @param query 用户问题
 * @param totalCount 匹配总数（可能 > documents.size()，如果发生截断）
 * @return 聚合上下文文本
 */
public String buildAggregationContext(List<RetrievedDocument> documents,
                                       String promptBase,
                                       String query,
                                       int totalCount) {
    int fixedTokens = estimateTokens(promptBase)
                    + estimateTokens(query)
                    + RESPONSE_RESERVED_TOKENS;
    int availableTokens = maxContextTokens - fixedTokens;

    if (availableTokens <= 0) {
        availableTokens = maxContextTokens / 2;
    }

    StringBuilder ctx = new StringBuilder();
    int usedTokens = 0;
    int includedCount = 0;

    ctx.append("【匹配记录总数: ").append(totalCount).append(" 条】\n");

    for (RetrievedDocument doc : documents) {
        // 尝试从 content 中提取紧凑表示（或直接用结构化字段构建）
        String compactEntry = buildCompactEntry(doc);
        int entryTokens = estimateTokens(compactEntry) + 1; // +1 for newline

        if (usedTokens + entryTokens > availableTokens) {
            ctx.append("\n[Token预算已满，以下展示 ").append(includedCount)
               .append("/").append(totalCount).append(" 条记录]");
            break;
        }

        ctx.append(compactEntry).append("\n");
        usedTokens += entryTokens;
        includedCount++;
    }

    log.info("聚合上下文构建: {}/{} 条, tokens: {}/{}",
            includedCount, totalCount, usedTokens, availableTokens);
    return ctx.toString();
}

/**
 * 从 RetrievedDocument 构建紧凑的单条记录。
 * 优先使用结构化字段 JSON，降级使用 content 截断。
 */
private String buildCompactEntry(RetrievedDocument doc) {
    // 如果 content 已经是 JSON 格式，直接使用
    String content = doc.getContent();
    if (content != null && content.trim().startsWith("{")) {
        return content.trim();
    }
    // 否则截取前 100 字符
    if (content != null && content.length() > 100) {
        return content.substring(0, 100) + "...";
    }
    return content != null ? content : "";
}
```

#### 3.2.8 超大数据集策略

| 结果数量 | 策略 |
|----------|------|
| ≤100条 | 全部传给 LLM（紧凑 JSON 格式） |
| 100-500条 | 全部传给 LLM，可能触发 token 截断，末尾标注截断信息 |
| 500-2000条 | 先传 COUNT + 前 100 条，LLM 回答中注明总数和部分展示 |
| >2000条 | 安全上限截断，LLM 明确告知"结果超过2000条，请缩小查询范围" |

---

### 第三层：长期架构演进（后续版本）

1. **强化 kb_chunk_structured 的实体关联能力**：
   - 当前 `kb_chunk_structured` 已经是 EAV（实体-属性-值）模式，按 chunk 粒度存储字段。
     不同业务域（HR、FINANCE、RD）的"实体"字段完全不同（HR 有 name/status/dept，
     FINANCE 有 contract_no/amount），强行合入一张实体宽表没有意义——EAV 模式反而是正确的抽象。
   - 改进方向：ETL 时自动识别并标记"实体标识字段"（如 `name`、`contract_no`），
     让聚合查询时能按实体去重和 GROUP BY。
   - 预计算聚合统计缓存（如各域枚举字段的 COUNT DISTINCT），在数据入库时增量刷新，
     避免每次查询都扫全表。

2. **Agentic RAG（Tool Calling）**：
   - 当前方案通过硬编码的 `QueryIntentClassifier` 做意图分派，规则覆盖 80%+ 场景。
   - 长期可让 LLM 通过 Tool Calling 自主选择检索策略：语义搜索 Tool vs 结构化聚合 Tool vs 两者组合。
   - 优点：无需维护规则，LLM 自主判断何时需要完整列表、何时需要最相关文档。

3. **NL-to-SQL 直查**：
   - 对于纯统计查询（"每个部门各有多少人离职"），让 LLM 直接生成 SQL 查询
     `kb_chunk_structured`，利用已有的 EAV 结构做 COUNT / GROUP BY / DISTINCT。
   - 这可完全跳过 chunk 内容检索，直接返回聚合数字，速度最快。

---

## 四、实施步骤

### Step 0: 实施前置组件 FilterConditionExtractor（约1天）

**依据文档**：`doc/未来补充/结构化字段过滤SQL-LLM自动提取启用方案.md`

实施内容：
- 新增 `rag/filter/FilterConditionExtractor.java`（按文档 4.2 节完整实现）
- 新增 `rag/filter/FieldDefinition.java`（按文档 4.4 节）
- 在 `ChunkStructuredFilterMapper.java` 新增 `selectDistinctFieldsByDomain` 和 `selectEnumValues`
- 在 `StructuredFilterService.java` 新增 `filterChunkIdsUnbounded()` 方法（去掉 subList(0,500)）
- 在 `ChunkStructuredFilterMapper.java` 新增 5 条无 LIMIT 500 的 Mapper 方法（安全上限 2000）
- 在 `application-p3.yml` 新增 `agent-qr.filter.llm-extract` 配置项，默认 `enabled: false`
- **注意**：此步骤只实现组件，`enabled` 保持 `false`，不改变现有检索行为

### Step 1: 配置调优（5分钟）

修改 `application-p2.yml`：

```yaml
agent-qr:
  retrieval:
    wide-top-k: 50          # 30 → 50
    final-top-k: 30         # 15 → 30
    max-context-tokens: 16000  # 8000 → 16000
```

### Step 2: 创建 QueryIntentClassifier（约2小时）

- 新增 `rag/classifier/QueryIntentClassifier.java`
- 规则匹配优先（关键词正则），LLM 分类兜底（可选，默认关闭）

### Step 3: 创建 AggregationQueryService（约3小时）

- 新增 `rag/service/AggregationQueryService.java`
- 编排：FilterConditionExtractor → 无界查询 → 批量取 chunk → 返回 RetrievedDocument 列表

### Step 4: 扩展 ContextTokenManager + PromptTemplate（约2小时）

- `ContextTokenManager`: 新增 `buildAggregationContext()`，支持紧凑 JSON 序列化
- `PromptTemplate`: 新增聚合类 System Prompt 模板

### Step 5: 集成到 ChatQueryService（约1小时）

- 注入 `QueryIntentClassifier` 和 `AggregationQueryService`
- 在 `ask()` 和 `askStream()` 中增加意图分派逻辑

### Step 6: 集成测试（约半天）

---

## 五、涉及文件清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `agent-qr-rag/.../filter/FilterConditionExtractor.java` | LLM 提取过滤条件（Step 0，按结构化字段过滤方案文档） |
| `agent-qr-rag/.../filter/FieldDefinition.java` | 字段定义 DTO（Step 0） |
| `agent-qr-rag/.../classifier/QueryIntentClassifier.java` | 查询意图分类器（Step 2） |
| `agent-qr-rag/.../service/AggregationQueryService.java` | 聚合查询编排服务（Step 3） |

### 修改文件

| 文件 | 改动 | 步骤 |
|------|------|------|
| `agent-qr-rag/.../filter/mapper/ChunkStructuredFilterMapper.java` | 新增 8 条 SQL（3条FilterConditionExtractor + 5条无界查询） | Step 0 |
| `agent-qr-rag/.../filter/StructuredFilterService.java` | 新增 `filterChunkIdsUnbounded()` + `dispatchConditionUnbounded()` | Step 0 |
| `agent-qr-rag/.../service/ChatQueryService.java` | 注入意图分派分支（ask + askStream 两处） | Step 5 |
| `agent-qr-rag/.../util/ContextTokenManager.java` | 新增 `buildAggregationContext()` + `buildCompactEntry()` | Step 4 |
| `agent-qr-rag/.../prompt/PromptTemplate.java` | 新增聚合类 System Prompt 模板 | Step 4 |
| `agent-qr-web/.../application-p2.yml` | 配置调优 | Step 1 |
| `agent-qr-web/.../application-p3.yml` | 新增 `agent-qr.filter.llm-extract` 配置 | Step 0 |

---

## 六、验证方案

### 6.1 功能验证

| 测试场景 | 输入 | 预期 |
|----------|------|------|
| 列举-少量 | "有哪些人已经离职"（实际5人） | 返回5人完整名单 |
| 列举-大量 | "有哪些人已经离职"（实际200人） | 返回完整名单或"N人，包括：..." |
| 统计-计数 | "一共有多少人离职" | 返回准确数字 |
| 统计-分组 | "每个部门各有多少人离职" | 返回准确的分组统计 |
| 语义-不变 | "离职流程是什么" | 走语义路径，回答质量不降级 |
| 边界-无结果 | "有哪些人已经离职"（实际0人） | 返回"知识库中没有离职记录" |
| 边界-无结构化数据 | 域下 kb_chunk_structured 无数据 | 降级到语义检索路径 |
| 边界-无过滤条件 | 问题不含结构化过滤意图（如"公司怎么样"） | IntentClassifier 判为 SEMANTIC，走现有管道 |

### 6.2 回归验证

| 场景 | 预期 |
|------|------|
| 普通语义问答 | 与原系统一致，不受影响 |
| SSE 流式输出 | 正常逐 token 推送 |
| 会话管理 | 创建、列表、删除正常 |
| 满意度反馈 | 正常记录 |

### 6.3 性能验证

- 聚合路径延迟：不超过语义路径的 2x（即使查询 2000 条 chunk）
- 内存峰值：聚合 2000 条 chunk 时堆内存 < 100MB
- LLM token 消耗：紧凑 JSON 格式下 200 条记录约 2000 tokens，在预算内

---

## 七、风险与降级

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| FilterConditionExtractor 提取错误条件 | 中 | 过滤掉正确结果或返回无关结果 | 字段名校验 + 枚举值校验 + 提取失败降级到语义路径 |
| IntentClassifier 错误分类 | 低 | 列举类走语义路径→结果截断 | 规则覆盖 80%+ 常见中文表达；LLM 兜底 |
| 无界查询返回过多（>2000） | 低 | 内存压力 + LLM 无法处理 | 安全上限 2000 + 告知用户缩小范围 |
| kb_chunk_structured 无数据 | 高（初期） | 聚合路径完全不可用 | getAvailableFields() 为空时降级语义路径 |
| LLM 提取增加延迟 | 低 | 用户感知变慢 | 用轻量模型；FilterConditionExtractor 可并行调用 |
| 聚合路径影响语义路径 | 无 | — | 语义路径代码完全不变，仅新增分支 |

**核心原则**：任何异常都降级到现有语义检索路径，不影响问答主流程可用性。

---

## 八、附录：关键配置汇总

```yaml
# application-p2.yml（修改）
agent-qr:
  retrieval:
    semantic-weight: 0.55
    keyword-weight: 0.45
    wide-top-k: 50              # 30 → 50
    final-top-k: 30             # 15 → 30
    rrf-k: 15
    max-context-tokens: 16000   # 8000 → 16000

# application-p3.yml（新增）
agent-qr:
  filter:
    llm-extract:
      enabled: false            # 默认关闭，灰度验证后开启
      model: qwen2.5:4b         # 提取用轻量模型
      timeout-seconds: 5        # LLM 调用超时

  aggregation:
    max-chunk-ids: 2000         # 无界查询安全上限
    compact-json: true          # 使用紧凑 JSON 格式化
```
