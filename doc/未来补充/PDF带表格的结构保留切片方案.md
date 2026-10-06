# PDF 带表格文档的结构保留切片方案

> 状态：待实施（P3+ 阶段） | 日期：2026-06-30

---

## 一、Context（问题背景）

当前项目 `agent-qr` 的 PDF → RAG 流水线存在两个关键缺口，导致含表格的 PDF 在切片后表格结构完全丢失：

1. **PDF 表格提取是空实现**：`PdfParser.extractTablesAsMarkdown()` 返回 `null`，PDFBox 的 `PDFTextStripper` 会把表格单元格线性化为无序文本
2. **切片器不感知表格边界**：`TextSplitter` 按 `\n\n` 分割段落，Markdown 表格行之间只有 `\n`，长表格必然被拦腰截断

对比：`DocxParser` 已经通过 `tableToMarkdown()` 正确将 DOCX 表格转为 Markdown，但由于 `TextSplitter` 不感知表格，即使 DOCX 的表格在切片时也可能被截断。

**目标**：让含表格的 PDF 在切片后，每个表格作为一个（或若干个保留表头的）完整语义单元进入向量库，检索时能还原表格上下文。

---

## 二、问题分析

### 问题 1：PDF 文本提取丢失表格结构

- PDFBox `PDFTextStripper.setSortByPosition(true)` 按坐标排序输出文本
- 表格单元格内容被当作普通文本行线性排列，列关系完全丢失
- 例如一个 3 列表格 "姓名 | 年龄 | 部门" 变成三行独立文字

### 问题 2：Markdown 表格被切片器截断

- `TextSplitter.split()` 第一步就是 `text.split("\n\n")`（按空行分段）
- Markdown 表格行之间是 `\n` 而非 `\n\n`，所以整个表格被视为一个段落
- 但如果表格超过 `chunkSize=500` 字符，`splitLongText()` 的滑动窗口会在表格中间截断
- 截断后 LLM 看到的是不完整的表格行，无法理解数据

### 问题 3：切片间丢失表格上下文

- 即使表格完整保留在一个切片中，检索时该切片独立返回
- LLM 看到孤立的表格数据，不知道这是什么表、列含义是什么
- 需要将表格标题/表头与数据行关联

---

## 三、解决方案（三层策略 + 一层增强）

### 第一层：PDF 表格提取 — 实现 `extractTablesAsMarkdown()`

#### 推荐方案：`tabula-java`

专为 PDF 表格设计的 Java 库，基于文本坐标聚类算法检测行列。

**理由**：
- 支持有边框和无边框表格
- 输出格式灵活（CSV/JSON/TSV），易于转为 Markdown
- 社区活跃，久经考验
- Apache Tika 虽已添加依赖，但其 PDF 表格检测能力弱（Tika 底层也用 PDFBox，优势在格式检测和元数据，不在表格结构化）

**备选方案**：PDFBox 自定义文本坐标分析
- 通过 `PDFTextStripper` 子类获取每个字符的 x/y 坐标
- Y 坐标聚类 → 行检测；X 坐标聚类 → 列检测
- 适合无边框表格，但开发成本高

**推荐采用混合策略**：
- 主路径：tabula-java 检测表格区域 → 提取为 `List<List<String>>` → 转为 Markdown
- 降级路径：tabula 未检测到表格时，用 PDFBox 文本坐标做启发式检测
- 大文件流式解析分支也加入表格提取

**新增依赖**（`agent-qr-knowledge/pom.xml`）：

```xml
<dependency>
    <groupId>technology.tabula</groupId>
    <artifactId>tabula</artifactId>
    <version>1.0.5</version>
</dependency>
```

#### 实现伪码

```java
// PdfParser.extractTablesAsMarkdown(PDDocument, int pageIndex)
private String extractTablesAsMarkdown(PDDocument document, int pageIndex) {
    // 1. 使用 tabula-java 提取页面表格
    ObjectExtractor extractor = new ObjectExtractor(document);
    PageArea area = new PageArea(pageIndex + 1, 0f, 0f, 100f, 100f);
    PageIterator pages = extractor.extract(List.of(area));
    
    // 2. 尝试 SpreadsheetExtractionAlgorithm（有边框），降级 BasicExtractionAlgorithm（无边框）
    // 3. 将 Table 对象转为 Markdown 字符串
    // 4. 返回 Markdown 表格
}
```

---

### 第二层：结构化标记 — 段落/表格边界标记

修改 `PdfParser.parse()` 和 `DocxParser.parse()` 的输出格式，在表格前后插入结构标记，让下游切片器能识别原子语义单元。

**输出格式示例**：

```text
[PAGE:1]
普通文本段落内容，这是表格前面的描述文字。

[TBL]
| 姓名 | 年龄 | 部门 |
| --- | --- | --- |
| 张三 | 30 | 研发 |
| 李四 | 28 | 财务 |
[/TBL]

继续普通文本，这是表格后面的内容...

[PAGE:2]
...
```

**标记设计**：

| 标记 | 含义 | 用途 |
|------|------|------|
| `[TBL]...[/TBL]` | 表格块 | 原子语义单元，切片器不得截断 |
| `[TBL:n/m]...[/TBL:n/m]` | 拆分后的表格片段 | 大表格的第 n/m 片段 |
| `[PAGE:N]` | 页边界 | 可选，用于溯源 |

---

### 第三层：表格感知切片器 — 增强 TextSplitter

在 `TextSplitter.split()` 中增加预处理步骤。

**核心算法流程**：

```
输入：带结构标记的文本
  │
  ├─ 1. 提取所有 [TBL]...[/TBL] 块，记录位置和内容
  │
  ├─ 2. 将表格块替换为占位符（短标记），形成"骨架文本"
  │     原始: "文本... [TBL]\n| a | b |\n| 1 | 2 |\n[/TBL] 文本..."
  │     骨架: "文本... __TBL_0__ 文本..."
  │
  ├─ 3. 对骨架文本按现有逻辑（\n\n 段落分割 + 滑动窗口）切片
  │
  ├─ 4. 将占位符还原为完整表格
  │
  └─ 5. 超大表格（> chunkSize）特殊处理：
        ├─ 表格 ≤ chunkSize → 整个表格作为一个切片
        └─ 表格 > chunkSize → 按行拆分，每个子表重复表头
           [TBL:1/3] 表头 + 前N行 [/TBL:1/3]
           [TBL:2/3] 表头 + 中N行 [/TBL:2/3]
           [TBL:3/3] 表头 + 后N行 [/TBL:3/3]
```

**TextSplitter 新增/修改的方法**：

| 方法 | 类型 | 说明 |
|------|------|------|
| `split(String text)` | 修改 | 开头调预处理，结尾调还原 |
| `preprocessTables(String text)` | 新增 | 提取表格块，返回 `(骨架文本, Map<占位符, 表格>)` |
| `restoreTables(List<String>, Map)` | 新增 | 将占位符还原为完整表格 |
| `splitLargeTable(String markdown)` | 新增 | 超大表格带表头拆分 |

**新增配置项**（`application-p3.yml`）：

```yaml
rag:
  table-aware-split: true        # 启用表格感知切片
  table-header-repeat: true      # 超大表格拆分时重复表头
  table-max-rows-per-chunk: 15   # 每个表格切片最多行数（不含表头）
```

---

### 第四层：切片元数据增强 — Chunk 实体扩展

在 `Chunk` 实体中新增字段，标记切片内容类型，帮助检索时还原上下文。

**数据库变更**：

```sql
ALTER TABLE kb_chunk ADD COLUMN content_type VARCHAR(16) DEFAULT 'TEXT';
-- 取值：TEXT / TABLE / TABLE_FRAGMENT / MIXED

ALTER TABLE kb_chunk ADD COLUMN table_caption VARCHAR(512);
-- 表格标题/表格前文本，用于检索时的上下文还原
```

**Java 实体变更**（`Chunk.java`）：

```java
/** 切片内容类型：TEXT / TABLE / TABLE_FRAGMENT / MIXED */
private String contentType;

/** 表格标题/上下文描述 */
private String tableCaption;
```

**ChunkEmbeddingListener 变更**：
- 创建 Chunk 时检测内容是否包含 `[TBL]` 标记
- 自动填充 `contentType` 和 `tableCaption`
- 在 `TextSegment.metadata` 中携带这些信息，写入 ChromaDB

---

## 四、实施步骤

```
Step 1 ──→ Step 2 ──→ Step 3 ──→ Step 4 ──→ Step 5 ──→ Step 6
 添加依赖    PDF提取    解析标记   切片增强   实体扩展   检索增强
 (pom.xml)  (PdfParser) (Parser)  (Splitter) (Chunk)   (Prompt)
```

### Step 1：添加 tabula-java 依赖
- **文件**：`agent-qr-knowledge/pom.xml`
- **内容**：新增 `technology.tabula:tabula:1.0.5`

### Step 2：实现 PDF 表格提取
- **文件**：`agent-qr-knowledge/.../parser/PdfParser.java`
- **内容**：实现 `extractTablesAsMarkdown(PDDocument, int pageIndex)` 方法
  - 使用 tabula-java 的 `ObjectExtractor` + `SpreadsheetExtractionAlgorithm` / `BasicExtractionAlgorithm`
  - 将提取结果转为 Markdown 格式
  - `parseStreaming()` 分支同步加入表格提取

### Step 3：修改解析输出格式
- **文件**：`PdfParser.java`, `DocxParser.java`
- **内容**：表格前后插入 `[TBL]` / `[/TBL]` 标记
  - `PdfParser`：`extractTablesAsMarkdown()` 返回值包裹标记
  - `DocxParser`：`tableToMarkdown()` 返回值包裹标记

### Step 4：增强 TextSplitter
- **文件**：`agent-qr-knowledge/.../splitter/TextSplitter.java`
- **内容**：
  - 新增 `preprocessTables()` / `restoreTables()` / `splitLargeTable()` 方法
  - 修改 `split()` 集成预处理/还原流程
  - 新增配置项注入

### Step 5：扩展 Chunk 实体
- **文件**：`agent-qr-knowledge/.../entity/Chunk.java`
- **内容**：新增 `contentType`, `tableCaption` 字段
- **文件**：`ChunkEmbeddingListener.java` — 创建 Chunk 时填充新字段

### Step 6：检索侧增强（可选）
- **文件**：`PromptTemplate.java`
- **内容**：在 system prompt 中说明表格 Markdown 格式，引导 LLM 正确解读
- **文件**：`ContextTokenManager.java` — 优先保留含表格的 chunk（表格信息密度高）

---

## 五、涉及文件清单

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `agent-qr-knowledge/pom.xml` | 新增依赖 | 添加 tabula-java 1.0.5 |
| `agent-qr-knowledge/.../parser/PdfParser.java` | 重写方法 | 实现 `extractTablesAsMarkdown()`，集成 tabula |
| `agent-qr-knowledge/.../parser/DocxParser.java` | 小幅修改 | `tableToMarkdown()` 输出包裹 `[TBL]` 标记 |
| `agent-qr-knowledge/.../splitter/TextSplitter.java` | 核心修改 | 新增表格预处理/还原/拆分逻辑 |
| `agent-qr-knowledge/.../entity/Chunk.java` | 新增字段 | `contentType`, `tableCaption` |
| `agent-qr-knowledge/.../listener/ChunkEmbeddingListener.java` | 小幅修改 | 填充 Chunk 新字段 |
| `agent-qr-web/.../application-p3.yml` | 新增配置 | 表格感知切片相关参数 |
| `agent-qr-rag/.../prompt/PromptTemplate.java` | 可选修改 | 表格上下文提示 |

---

## 六、验证方案

### 6.1 单元测试

| 测试类 | 验证点 |
|--------|--------|
| `PdfParserTest` | 含表格 PDF → 正确输出 Markdown 表格 |
| `TextSplitterTest` | 表格切片完整性、超大表格带表头拆分、表格前后文本正常切片 |
| `DocxParserTest` | 表格输出带 `[TBL]` 标记 |

### 6.2 集成测试

1. 上传含表格 PDF → 检查 `kb_chunk` 切片内容，确认表格行完整
2. 检索表格相关查询 → 验证返回上下文包含完整 Markdown 表格
3. A/B 对比：同样的表格查询，优化前后 LLM 回答质量差异

### 6.3 测试样本

| 样本 | 特征 |
|------|------|
| 简单有边框表格 | 3列 × 5行，基线场景 |
| 跨页长表格 | 20行以上，跨 2 页 |
| 无边框表格 | 仅靠列间距对齐 |
| 混合页面 | 文本段落 + 多个表格 |

---

## 七、风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| tabula-java 对无边框表格检测率低 | 部分表格仍丢失 | PDFBox 坐标分析降级路径 |
| 大表格拆分后语义割裂 | 跨片段表格数据难以理解 | 表头重复 + 分片标记 `[TBL:n/m]` |
| 表格 Markdown 增加上下文 token 消耗 | 可能挤压其他 chunk 空间 | `table-max-rows-per-chunk` 限制 + token 预算平衡 |
| tabula-java 依赖体积较大 | 打包体积增加 | 约 ~15MB，可接受 |
