# 43 · ETL 半结构化路径 canonicalText 生成错误

> **严重程度**：🟡 中
> **所属模块**：agent-qr-etl（DataNormalizer）
> **设计依据**：《系统详细设计说明书》§8.9.1 DataNormalizer（半结构化应调用 `extractSemiStructured(mapped)`）、§17.6
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

`DataNormalizer` 的三条数据分类路径中，"半结构化"路径的实现与设计不符：

1. **使用了 `rawRecord` 而非 `mappedRecord`**——跳过了字段映射环节，源字段名与原始值直接进入文本；
2. **用 `Map.toString()` 代替 JSON 序列化**——产出的是 Java 的 Map 字符串形式（形如 `{name=张三, age=25}`），而非合法 JSON（`{"name":"张三","age":25}`）；
3. 设计要求的 `extractSemiStructured(mapped)` 方法在代码中**不存在**。

该文本（`canonicalText`）是后续**向量化的输入**，其质量直接决定检索效果。当前产出既丢失了字段映射（如中文名翻译、字典转换），又使用了非标准的序列化格式，会降低该类数据的检索命中率。

---

## 二、推断依据

### 依据 1：半结构化分支的实现

`agent-qr-etl/src/main/java/org/example/agent_qr/etl/normalizer/DataNormalizer.java:76-95`

```java
String canonicalText;
if (dataType == DataType.STRUCTURED) {
    canonicalText = structuredDataConverter.convert(mappedRecord, fieldMappings, sourceName);
} else if (dataType == DataType.UNSTRUCTURED) {
    // 非结构化数据直接取内容字段
    canonicalText = extractUnstructuredText(rawRecord);
} else {
    // 半结构化：取 rawRecord 的 JSON 字符串表示
    canonicalText = rawRecord.toString();      // ← 用 rawRecord，且是 Map.toString()
}

CanonicalRecord record = CanonicalRecord.builder()
        .sourceSystem(sourceName)
        .domain(domain)
        .dataType(dataType)
        .canonicalText(canonicalText)
        .metadata(mappedRecord)                // ← metadata 用的是 mappedRecord
        ...
```

对比同一构造中的 `metadata(mappedRecord)`——**同一个对象里，metadata 用映射后的记录，canonicalText 用映射前的原始记录**，两者不一致。

### 依据 2：注释与实现不符

代码注释写的是"取 `rawRecord` 的 **JSON 字符串**表示"，但 `Map.toString()` 输出的是 Java 的 `{key=value}` 形式：

| | 期望（JSON） | 实际（Map.toString） |
|---|---|---|
| 示例 | `{"name":"张三","age":25}` | `{name=张三, age=25}` |
| 字符串值 | 带引号 | 无引号 |
| 分隔符 | `":"` | `=` |

对 Embedding 模型而言，后者并非标准结构，但影响主要在语义清晰度与后续可解析性上。

### 依据 3：设计要求的类与方法

- §8.9.1 的分类逻辑要求 `SEMI_STRUCTURED → extractSemiStructured(mapped)`；
- 全仓库检索 `extractSemiStructured` **零命中**——该方法从未实现。

### 依据 4：`classify` 的实现也与设计不同

设计 §8.9.1 要求按 `config.sourceType`（JDBC→STRUCTURED 等）与 `_json_path` / `_xml_path` 标记分类；实际实现（`DataNormalizer.java:113-128`）改为按"是否含 `_file_type`/`_file_key`"与"是否含嵌套 Map/List"来判断——**不看 `config.sourceType`，也不识别 `_json_path`/`_xml_path`**。

该改动本身有合理性（基于数据形态分类更稳健），但属于未回填文档的偏差（参见文档 39）。

### 依据 5：与质检侧存在契约敞口

`CompletenessRule.java:27` 的必填字段列表可由数据源的 `content_fields` 配置覆盖，而 `DataNormalizer` 的非结构化提取是**硬编码** `_content` / `content` / `text` 回退（`:133-142`）。

若某数据源配置 `content_fields = title,desc`，质检判定"通过"的记录在 ETL 侧可能产出空 `canonicalText`，进而写入**空内容的切片**。该组合缺陷无测试拦截（两模块均无测试）。

---

## 三、影响范围

1. **半结构化数据检索质量下降**：字段映射被跳过意味着源字段名（可能是英文列名、编码值）直接进入向量文本，而映射环节本应把它们转换为中文可读名称（`FieldMappingEngine` 中已实现字典翻译、日期转中文、金额格式化等能力）。
2. **与结构化路径不一致**：同一数据源的结构化记录会走完整映射，半结构化记录不会——用户体验不统一。
3. **空切片风险**：依据 5 的组合缺陷会产出空内容切片，污染向量库。
4. **`_table_comment` 未实现**：设计 §17.6 要求段落标题取 `_table_comment`，实际固定为 `【sourceName】`，该字段全仓无实现。

---

## 四、修复方向

1. **半结构化路径改用 `mappedRecord`**，并实现 `extractSemiStructured`：建议用 Jackson 序列化为标准 JSON（`objectMapper.writeValueAsString(...)`），保留嵌套结构。
2. **对齐非结构化路径的字段来源**：与质检侧的 `content_fields` 配置保持一致，避免"质检通过但产出空文本"。
3. **实现 `_table_comment`**：按 §17.6 用作段落标题，无该字段时回退到当前行为。
4. **回填文档**：`classify` 的分类依据已变（依据 4），需同步设计文档 §8.9.1。
5. **补测试**：至少覆盖"半结构化记录产出的 canonicalText 是合法 JSON 且字段已映射""质检通过的非结构化记录不应产出空文本"。

---

## 五、核查边界

- 静态分析，未运行 ETL 管道、未检查实际产出的 `canonicalText` 内容。
- 未连接数据库确认 `kb_chunk` 中是否存在空内容切片（可作为本缺陷的验证手段）。
- 未评估半结构化数据在真实数据源中的占比（决定本缺陷的实际影响面）。
