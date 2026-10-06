# 结构化字段过滤 SQL — LLM 自动提取启用方案

> 状态：待实施 | 日期：2026-06-30 | 依赖：P3 阶段基础已完成

---

## 一、目标

用户在 Chat 界面用自然语言提问时，系统自动从问题中提取结构化过滤条件（数值范围、日期区间、枚举值），转换为 SQL 在向量检索前缩小候选集，提升检索精度和速度。

**效果对比**：

| 维度 | 当前（域过滤 only） | 启用后（域 + 结构化 SQL） |
|------|-------------------|-------------------------|
| 候选集大小 | 域内全量（可能数千条） | ≤ 500 条精确候选 |
| 检索延迟 | Chroma 扫描全量 | Chroma 仅扫描候选集 |
| 过滤依据 | 仅 domain | domain + 任意结构化字段组合 |
| 用户体验 | 无法按字段筛选 | 自然语言即可，无需学习 |

---

## 二、架构总览

```
用户: "去年研发部金额超过100万的采购合同有哪些？"
  │
  ▼
ChatQueryService.resolveRouting(query)
  │
  ├─ DomainRouterV2.route(query)
  │   → 匹配到 domain = "FINANCE" (相似度 0.65)
  │
  ▼
FilterConditionExtractor.extract(query, domain)  ← 【本次新增】
  │
  ├─ 1. 查询该 domain 的可用结构化字段定义
  │     SELECT DISTINCT field_name, field_type
  │     FROM kb_chunk_structured WHERE domain = 'FINANCE'
  │     → [{fieldName:"amount", fieldType:"NUMBER"},
  │        {fieldName:"dept", fieldType:"ENUM"},
  │        {fieldName:"signDate", fieldType:"DATE"}]
  │
  ├─ 2. 构造 Prompt（字段定义 + few-shot + 用户问题）
  │
  ├─ 3. 调用 LLM → 结构化 JSON 输出
  │     [
  │       {"fieldName":"dept","fieldType":"ENUM","operator":"EQ","value":"研发部"},
  │       {"fieldName":"amount","fieldType":"NUMBER","operator":"GT","value":"1000000"},
  │       {"fieldName":"signDate","fieldType":"DATE","operator":"BETWEEN",
  │        "minValue":"2025-01-01","maxValue":"2025-12-31"}
  │     ]
  │
  └─ 4. 校验：字段名是否合法、值类型是否匹配
  │
  ▼
HybridRetriever.hybridSearch(query, embedding, routing, filterConditions)
  │
  ├─ Step 0: StructuredFilterService.filterChunkIds("FINANCE", conditions)
  │   → SQL 前置过滤 → 获得候选 chunkId 小集合
  │
  ├─ Step 1: ChromaDB + BM25 双路召回
  ├─ Step 1.5: 域后过滤（候选集裁剪）
  ├─ Step 2: RRF 融合
  └─ Step 3: Rerank 精排
```

---

## 三、前置条件检查

### 3.1 确认结构化数据存在

```sql
-- 查看当前 kb_chunk_structured 表中可用的字段
SELECT domain, field_name, field_type, COUNT(*) AS cnt
FROM kb_chunk_structured
WHERE domain IS NOT NULL
GROUP BY domain, field_name, field_type
ORDER BY domain, cnt DESC;
```

如果没有数据，说明 ETL 数据同步还未产生结构化字段。此时 LLM 提取无意义（没有字段可过滤），应走全量检索降级路径。

### 3.2 确认依赖就绪

当前项目已具备的依赖（无需新增 Maven 坐标）：

| 依赖 | 状态 | 用途 |
|------|------|------|
| `StructuredFilterService` | ✅ 已实现 | SQL 过滤执行 |
| `ChunkStructuredFilterMapper` | ✅ 已实现 | 4 条 MyBatis SQL（NUMBER/DATE/ENUM/domain） |
| `FilterCondition` | ✅ 已实现 | 条件 DTO（fieldName/fieldType/operator/value/minValue/maxValue） |
| `DomainRouterV2` | ✅ 已实现 | Embedding 语义域路由 |
| `ProviderFactory` | ✅ 已实现 | 获取 LLM/Embedding 实例 |
| `KnowledgeCatalogService` | ✅ 已实现 | 域目录元数据查询 |
| `ObjectMapper` (Jackson) | ✅ Spring Boot 内置 | JSON 序列化/反序列化 |
| `FilterCondition` 类 | ✅ 已实现 | 五种操作符常量: EQ/GT/GTE/LT/LTE/BETWEEN |

**结论：不需要新增任何 Maven 依赖。**

---

## 四、新增组件

### 4.1 文件清单

```
agent-qr-rag/src/main/java/org/example/agent_qr/rag/filter/
  ├── FilterCondition.java              ← 已有，无需修改
  ├── StructuredFilterService.java      ← 已有，无需修改
  ├── FilterConditionExtractor.java     ← 【新增】
  └── mapper/
      └── ChunkStructuredFilterMapper.java  ← 需新增 1 条 SQL
```

### 4.2 FilterConditionExtractor（核心新增）

```java
package org.example.agent_qr.rag.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.filter.mapper.ChunkStructuredFilterMapper;
import org.example.agent_qr.rag.provider.LLMProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM 结构化过滤条件提取器（P3 扩展）。
 * <p>
 * 从用户自然语言问题中自动提取结构化过滤条件（数值范围、日期区间、枚举值），
 * 转为 {@link FilterCondition} 列表，传入 {@link StructuredFilterService} 做 SQL 前置过滤。
 * </p>
 *
 * <p><b>触发时机：</b>域路由成功匹配后，在调用 HybridRetriever 之前。</p>
 *
 * <p><b>降级策略：</b></p>
 * <ul>
 *   <li>LLM 调用异常 → 返回空列表，走全量检索</li>
 *   <li>JSON 解析失败 → 返回空列表</li>
 *   <li>字段校验不通过 → 丢弃该条件，其余保留</li>
 *   <li>无可用字段定义 → 跳过提取</li>
 * </ul>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class FilterConditionExtractor {

    @Autowired
    private ProviderFactory providerFactory;

    @Autowired
    private ChunkStructuredFilterMapper structuredFilterMapper;

    /** 是否启用 LLM 自动提取（可配置开关，方便调试和 A/B 测试） */
    @Value("${agent-qr.filter.llm-extract.enabled:false}")
    private boolean enabled;

    /** 提取用 LLM 模型（默认用本地轻量模型，节省成本） */
    @Value("${agent-qr.filter.llm-extract.model:qwen2.5:4b}")
    private String extractModel;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String FILTER_SYSTEM_PROMPT = """
        你是一个结构化查询条件提取器。从用户问题中提取过滤条件。

        可用字段定义（JSON数组，每个字段包含fieldName/fieldType/enumValues）：
        {availableFields}

        输出规则：
        1. 只输出 JSON 数组，不要任何额外文字
        2. 每个条件对象必须包含：fieldName, fieldType, operator, value
        3. 一个字段最多产生一个条件
        4. 如果用户问题中没有明确的结构化过滤意图（没有提到具体数值、日期、或枚举值），输出空数组 []
        5. operator 取值：
           - NUMBER: EQ, GT, GTE, LT, LTE, BETWEEN
           - DATE: EQ, GT, GTE, LT, LTE, BETWEEN
           - ENUM: EQ
        6. BETWEEN 时需要同时提供 minValue 和 maxValue
        7. 日期值格式为 yyyy-MM-dd
        8. 枚举值必须精确匹配可用字段中的 enumValues

        Few-shot 示例：
        问题："去年研发部金额超过100万的采购合同有哪些？"
        可用字段：[{"fieldName":"amount","fieldType":"NUMBER","enumValues":[]},
                   {"fieldName":"dept","fieldType":"ENUM","enumValues":["研发部","财务部","销售部"]},
                   {"fieldName":"signDate","fieldType":"DATE","enumValues":[]}]
        输出：[{"fieldName":"dept","fieldType":"ENUM","operator":"EQ","value":"研发部"},
               {"fieldName":"amount","fieldType":"NUMBER","operator":"GT","value":"1000000"},
               {"fieldName":"signDate","fieldType":"DATE","operator":"BETWEEN",
                "minValue":"2025-01-01","maxValue":"2025-12-31"}

        问题："上个月销售额排行"
        可用字段：[{"fieldName":"salesAmount","fieldType":"NUMBER","enumValues":[]},
                   {"fieldName":"salesDate","fieldType":"DATE","enumValues":[]}]
        输出：[{"fieldName":"salesDate","fieldType":"DATE","operator":"BETWEEN",
                "minValue":"2026-05-01","maxValue":"2026-05-31"}

        问题："请介绍一下公司的考勤制度"
        可用字段：[{"fieldName":"dept","fieldType":"ENUM","enumValues":["HR","研发部"]},
                   {"fieldName":"level","fieldType":"ENUM","enumValues":["L1","L2","L3"]}]
        输出：[]
        """;

    /**
     * 从用户自然语言问题中提取结构化过滤条件。
     *
     * @param query  用户原始问题
     * @param domain 域路由匹配的业务域（如 "HR"、"FINANCE"）
     * @return 过滤条件列表（提取失败或不可用时返回空列表）
     */
    public List<FilterCondition> extract(String query, String domain) {
        if (!enabled) {
            log.debug("LLM 过滤条件提取未启用");
            return List.of();
        }

        if (domain == null || domain.isBlank()) {
            log.debug("域为空，跳过过滤条件提取");
            return List.of();
        }

        // 1. 查询可用字段定义
        List<FieldDefinition> availableFields = getAvailableFields(domain);
        if (availableFields.isEmpty()) {
            log.debug("域 {} 无可用结构化字段，跳过提取", domain);
            return List.of();
        }

        // 2. 构造 Prompt
        String userPrompt = buildExtractionPrompt(query, availableFields);

        // 3. 调用 LLM
        String llmResponse;
        try {
            LLMProvider llm = providerFactory.getLLMProvider(extractModel);
            // 使用 LangChain4j 的 ChatLanguageModel 直接生成（不走 SystemMessage 拼接）
            // 如果 ProviderFactory 不支持指定模型，则用默认 LLM
            if (llm == null) {
                llm = providerFactory.getLLMProvider();
            }
            // 此处构造简单的 ChatMessage 调用
            var messages = List.of(
                dev.langchain4j.data.message.SystemMessage.from(FILTER_SYSTEM_PROMPT
                    .replace("{availableFields}", OBJECT_MAPPER.writeValueAsString(availableFields))),
                dev.langchain4j.data.message.UserMessage.from(query)
            );
            llmResponse = llm.generate(messages);
        } catch (Exception e) {
            log.warn("LLM 过滤条件提取调用失败，降级全量检索: domain={}, query={}", domain, query, e);
            return List.of();
        }

        // 4. 解析 JSON
        List<FilterCondition> rawConditions;
        try {
            rawConditions = parseResponse(llmResponse);
        } catch (Exception e) {
            log.warn("LLM 过滤条件 JSON 解析失败，降级全量检索: response={}", llmResponse, e);
            return List.of();
        }

        // 5. 校验
        List<FilterCondition> validConditions = validate(rawConditions, availableFields);
        log.info("结构化过滤条件提取完成: domain={}, query={}, raw={}, valid={}",
                domain, query, rawConditions.size(), validConditions.size());
        return validConditions;
    }

    // ==================== 内部方法 ====================

    /**
     * 查询指定域下的可用结构化字段定义。
     */
    private List<FieldDefinition> getAvailableFields(String domain) {
        try {
            return structuredFilterMapper.selectDistinctFieldsByDomain(domain);
        } catch (Exception e) {
            log.warn("查询可用字段失败: domain={}", domain, e);
            return List.of();
        }
    }

    /**
     * 构造提取 Prompt（注入字段定义）。
     */
    private String buildExtractionPrompt(String query, List<FieldDefinition> fields) {
        // 问题本身足够明确，Prompt 模板已包含 few-shot，这里只传问题文本
        // SystemPrompt 中已注入了 availableFields
        return query;
    }

    /**
     * 清理 LLM 响应中的非 JSON 内容，解析为 FilterCondition 列表。
     */
    List<FilterCondition> parseResponse(String llmResponse) {
        String json = llmResponse.trim();
        // 移除可能的 markdown 代码块包裹
        if (json.startsWith("```")) {
            int start = json.indexOf("\n");
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }
        if (json.startsWith("```json")) {
            json = json.substring(7).trim();
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3).trim();
            }
        }
        return OBJECT_MAPPER.readValue(json, new TypeReference<List<FilterCondition>>() {});
    }

    /**
     * 校验提取的条件字段名是否合法、类型是否匹配。
     */
    List<FilterCondition> validate(List<FilterCondition> conditions, List<FieldDefinition> validFields) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }

        Set<String> validFieldNames = validFields.stream()
                .map(FieldDefinition::getFieldName)
                .collect(Collectors.toSet());
        Map<String, String> fieldTypeMap = validFields.stream()
                .collect(Collectors.toMap(FieldDefinition::getFieldName, FieldDefinition::getFieldType));
        Map<String, Set<String>> enumValueMap = validFields.stream()
                .filter(f -> f.getEnumValues() != null && !f.getEnumValues().isEmpty())
                .collect(Collectors.toMap(FieldDefinition::getFieldName, f -> new HashSet<>(f.getEnumValues())));

        List<FilterCondition> valid = new ArrayList<>();
        for (FilterCondition c : conditions) {
            // 字段名校验
            if (c.getFieldName() == null || !validFieldNames.contains(c.getFieldName())) {
                log.debug("过滤条件字段名无效，丢弃: {}", c);
                continue;
            }
            // 类型校验
            String expectedType = fieldTypeMap.get(c.getFieldName());
            if (!expectedType.equals(c.getFieldType())) {
                log.debug("过滤条件类型不匹配，丢弃: expected={}, actual={}", expectedType, c.getFieldType());
                continue;
            }
            // 枚举值校验
            if ("ENUM".equals(c.getFieldType())) {
                Set<String> validValues = enumValueMap.get(c.getFieldName());
                if (validValues != null && !validValues.contains(c.getValue())) {
                    log.debug("枚举值无效，丢弃: field={}, value={}, valid={}",
                            c.getFieldName(), c.getValue(), validValues);
                    continue;
                }
            }
            valid.add(c);
        }
        return valid;
    }
}
```

### 4.3 ChunkStructuredFilterMapper 新增 SQL

在已有 `ChunkStructuredFilterMapper.java` 中新增一条查询：

```java
/**
 * 查询指定域下的所有可用字段定义（去重）。
 * 用于 FilterConditionExtractor 构建 LLM Prompt 中的字段列表。
 */
@Select("SELECT DISTINCT field_name AS fieldName, field_type AS fieldType " +
        "FROM kb_chunk_structured WHERE domain = #{domain} " +
        "ORDER BY field_name")
List<FieldDefinition> selectDistinctFieldsByDomain(@Param("domain") String domain);
```

### 4.4 FieldDefinition 内部类/新文件

放在 `FilterConditionExtractor.java` 同文件内或 `filter/` 包下：

```java
package org.example.agent_qr.rag.filter;

import lombok.Data;
import java.util.List;

/**
 * 结构化字段定义，用于 LLM 提取 Prompt 中的字段描述。
 */
@Data
public class FieldDefinition {
    /** 字段名 */
    private String fieldName;
    /** 字段类型：NUMBER / DATE / ENUM / STRING */
    private String fieldType;
    /** 枚举值列表（仅 ENUM 类型有值） */
    private List<String> enumValues;
}
```

**注意**：`enumValues` 字段在 SQL 查询结果中为 null，需要在 `FilterConditionExtractor.getAvailableFields()` 中二次查询枚举值：

```java
private List<FieldDefinition> getAvailableFields(String domain) {
    List<FieldDefinition> fields = structuredFilterMapper.selectDistinctFieldsByDomain(domain);
    for (FieldDefinition f : fields) {
        if ("ENUM".equals(f.getFieldType())) {
            List<String> values = structuredFilterMapper.selectEnumValues(f.getFieldName(), domain);
            f.setEnumValues(values);
        }
    }
    return fields;
}
```

对应的 Mapper SQL：

```java
@Select("SELECT DISTINCT field_value FROM kb_chunk_structured " +
        "WHERE field_name = #{fieldName} AND domain = #{domain} " +
        "ORDER BY field_value LIMIT 50")
List<String> selectEnumValues(@Param("fieldName") String fieldName,
                              @Param("domain") String domain);
```

---

## 五、ChatQueryService 集成

### 5.1 改动点

`ChatQueryService.java` 中两处（`ask()` 和 `askStream()` 方法）做相同修改。

**改动前**（当前代码）：

```java
// Line 112-114 (ask) & Line 196-198 (askStream)
DomainRoutingResult routing = resolveRouting(query);
List<RetrievedDocument> retrievedDocs = hybridRetriever.hybridSearch(
        query, queryEmbedding, routing, List.of());
```

**改动后**：

```java
DomainRoutingResult routing = resolveRouting(query);

// ★ 新增：LLM 自动提取结构化过滤条件
List<FilterCondition> filterConditions = extractFilterConditions(query, routing);

List<RetrievedDocument> retrievedDocs = hybridRetriever.hybridSearch(
        query, queryEmbedding, routing, filterConditions);
```

### 5.2 新增方法

在 `ChatQueryService` 中新增：

```java
@Autowired(required = false)
private FilterConditionExtractor filterConditionExtractor;

/**
 * 提取结构化过滤条件（P3 扩展）。
 * <p>只在域路由成功时触发 LLM 提取，不可用时返回空列表。</p>
 */
private List<FilterCondition> extractFilterConditions(String query, DomainRoutingResult routing) {
    if (filterConditionExtractor == null) {
        return List.of();
    }
    if (routing == null || routing.isFallbackToGlobal()) {
        return List.of();
    }
    try {
        return filterConditionExtractor.extract(query, routing.getPrimaryDomain());
    } catch (Exception e) {
        log.warn("结构化过滤条件提取异常，降级全量检索: query={}", query, e);
        return List.of();
    }
}
```

### 5.3 提取时机决策树

```
用户问题进入 ChatQueryService
  │
  ├─ resolveRouting(query)
  │   ├─ DomainRouterV2.route(query) → 匹配到域
  │   │   → domain = "FINANCE", isFallbackToGlobal = false
  │   │   → 进入 LLM 提取 ✅
  │   │
  │   ├─ DomainRouter.route(query) → P2 关键词匹配降级
  │   │   → domain = "FINANCE", 同样 IS 一个明确域
  │   │   → 进入 LLM 提取 ✅
  │   │
  │   └─ 路由失败 → DomainRoutingResult.fallback()
  │       → isFallbackToGlobal = true
  │       → 跳过 LLM 提取，全量检索 ❌
  │
  └─ extractFilterConditions()
      ├─ filterConditionExtractor 为 null（未注入）
      │   → 跳过，返回 List.of()
      ├─ enabled = false（配置关闭）
      │   → 跳过
      ├─ 域下无结构化字段
      │   → 跳过
      └─ LLM 调用成功 → 返回 List<FilterCondition>
```

---

## 六、配置文件

`application-p3.yml` 新增：

```yaml
agent-qr:
  filter:
    llm-extract:
      enabled: false          # 默认关闭，灰度验证后开启
      model: qwen2.5:4b       # 提取用模型（轻量即可，不需要强推理能力）
      timeout-seconds: 5      # LLM 调用超时（秒），避免阻塞主流程
```

**为什么默认关闭**：
- 需要先在测试环境验证 LLM 提取的准确率
- 需要确认 `kb_chunk_structured` 中有实际数据
- 提取会额外增加一次 LLM 调用（~200ms），需要评估延迟影响

---

## 七、验证方案

### 7.1 单元测试（FilterConditionExtractorTest）

```java
@Test
void shouldExtractConditions_whenQueryContainsExplicitFilters() {
    // Given: 可用字段含 amount(NUMBER), dept(ENUM), signDate(DATE)
    // Query: "去年研发部金额超过100万的采购合同"
    // When: extract(query, "FINANCE")
    // Then: 返回 3 条 FilterCondition
    //   - dept EQ "研发部"
    //   - amount GT 1000000
    //   - signDate BETWEEN 2025-01-01 AND 2025-12-31
}

@Test
void shouldReturnEmpty_whenQueryHasNoFilterIntent() {
    // Query: "什么是采购合同的标准流程？"
    // Then: 返回空列表
}

@Test
void shouldDiscardInvalidField_whenValidationFails() {
    // Valid fields: [amount(NUMBER)]
    // LLM returns: [{fieldName:"nonexistent", fieldType:"NUMBER", ...}]
    // Then: 校验后返回空列表
}

@Test
void shouldParseJsonInMarkdownBlock() {
    // LLM response wrapped in ```json ... ```
    // Then: parseResponse 正确提取内部 JSON
}

@Test
void shouldValidateEnumValue() {
    // Valid enums: ["研发部", "财务部", "销售部"]
    // LLM returns: dept EQ "不存在的部门"
    // Then: 校验丢弃
}
```

### 7.2 集成测试

**准备数据**：

```sql
-- 在 kb_chunk_structured 中插入测试数据
INSERT INTO kb_chunk_structured (chunk_id, domain, field_name, field_type, numeric_value, field_value)
VALUES
  (1001, 'FINANCE', 'amount', 'NUMBER', 1500000, NULL),
  (1002, 'FINANCE', 'amount', 'NUMBER', 500000, NULL),
  (1003, 'FINANCE', 'dept', 'ENUM', NULL, '研发部'),
  (1004, 'FINANCE', 'signDate', 'DATE', NULL, '2025-06-15');
```

**测试用例**：

| 测试场景 | Query | 预期条件数 | 预期 SQL 命中 |
|---------|-------|-----------|-------------|
| 数值+枚举+日期 | "去年研发部金额>100万" | 3 | chunk_id IN (候选集) |
| 仅有数值 | "金额超过50万" | 1 | chunk_id = 1001 |
| 无过滤意图 | "介绍一下采购流程" | 0 (空) | 全量检索 |
| 枚举值不匹配 | "市场部金额>100万" | 条件被校验丢弃 | 全量检索 |

### 7.3 灰度验证步骤

```
Phase 1: 单元测试通过 + enabled=false
  → 部署上线，确认不影响现有功能

Phase 2: enabled=true，日志级别 DEBUG
  → 观察 100 条真实用户 query 的提取结果
  → 人工抽样检查准确率
  → 统计: 多少 query 触发了提取 / 提取准确率 / LLM 调用延迟

Phase 3: 准确率 > 80% → 默认开启
  → 监控 SQL 过滤命中率变化
  → 监控检索延迟变化（预期：候选集 < 500 时 Chroma 扫描更快）
```

---

## 八、风险与降级

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| LLM 提取错误条件 | 中 | 过滤掉正确结果 | 校验层拦截非法字段名/值；LLM 返回空时降级全量 |
| 提取增加延迟 | 低 | 用户感知变慢 | 用轻量模型（`qwen2.5:4b`），超时 5 秒降级 |
| 字段定义为空 | 高（初期） | 无 SQL 过滤 | `getAvailableFields()` 为空时直接跳过 |
| LLM 服务不可用 | 低 | 阻塞问答 | catch 后返回 `List.of()`，完全降级 |
| "去年"等时间词解析错误 | 中 | 日期区间不准 | Few-shot 示例覆盖常见时间表达 |

**核心原则**：**任何异常都降级为全量检索，不影响问答主流程可用性。**

---

## 九、后续优化方向

1. **缓存字段定义**：域的结构化字段不会频繁变化，可用 Caffeine 缓存 1 小时，避免每次查询 `SELECT DISTINCT` 扫描大表
2. **字段别名映射**：用户说"部门"但字段名是 `dept`，需要配置别名映射表
3. **Prompt 缓存**：`FILTER_SYSTEM_PROMPT` 加上 `availableFields` 注入后，整个 SystemMessage 可被支持 Prompt Caching 的 LLM 缓存
4. **条件优先级排序**：多个条件时，选择性最高的条件（如枚举 EQ）放在前面，减少 SQL 扫描
5. **前端透明展示**：如果 LLM 提取到了条件，在 Chat UI 中展示"已应用过滤：部门=研发部、金额>100万"，用户可以手动删除/修改
