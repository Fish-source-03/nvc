# 10 · PDF 表格结构化保留为空实现（复盘报告偏差 1）

> **严重程度**：🔴 高
> **所属模块**：agent-qr-knowledge（PdfParser、DocxParser、TextSplitter）
> **设计依据**：《系统详细设计说明书》§5.2.3 PdfParser（表格 → Markdown）、§5.0 包结构；SRS §5.3.3
> **复盘报告对应**：偏差 1（`doc/项目复盘报告.md:44-54`）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

SRS §5.3.3 要求"PDF 和 Word 中的表格，提取时保留为 Markdown 表格格式，而非打乱成无结构的文本"。核查确认该需求**完全未落地**：

1. `PdfParser.extractTablesAsMarkdown()` 仍是 `return null` 空实现，调用点的判空分支永不进入；
2. `doc/未来补充/PDF带表格的结构保留切片方案.md` 提出的三层补救策略（tabula-java 提取 / `[TBL]` 标记 / TextSplitter 表格感知）在代码中**零落地**；
3. `TextSplitter` 不感知表格边界，会拦腰截断长表格——这一点**同时削弱了已正确实现的 DOCX 表格转换收益**。

即：PDF 表格 100% 丢失结构；DOCX 表格虽已转成 Markdown，但在切片阶段仍可能被破坏。

---

## 二、推断依据

### 依据 1：`extractTablesAsMarkdown` 返回 null

`agent-qr-knowledge/src/main/java/org/example/agent_qr/knowledge/parser/PdfParser.java:138-143`

```java
/**
 * P2 简化实现，P3 集成 Apache Tika。
 */
private String extractTablesAsMarkdown(PDDocument document, int pageIndex) {
    // P3 集成 Tika 表格识别
    return null;
}
```

### 依据 1 补充：代码注释记录过实现意图（但与补救方案不一致）

该方法的注释写的是「**P2 简化实现，P3 集成 Apache Tika**」——说明：

1. 作者当时**有明确的实现计划**（P3 集成 Tika），而 P3 已交付、计划未执行；
2. 但 **`return null` 并不是"简化实现"，而是完全没有实现**——注释的措辞比实际状态乐观；
3. **⚠️ 实现路径存在分歧**：代码注释指向 **Apache Tika**，而 `doc/未来补充/PDF带表格的结构保留切片方案.md:55` 的补救方案推荐 **tabula-java**，并明确说明理由——"Tika 虽已添加依赖，但其 PDF 表格检测能力弱（底层也用 PDFBox，优势在格式检测和元数据，不在表格结构化）"。

**实施时必须先确认采用哪条路径**（详见批次 06 任务 6.1.1），不要因为代码注释提到 Tika 就默认走 Tika。

同类情况对照：`PdfParser.performOcr()` 的注释同样写「P2 简化实现，P3 集成 Tesseract」，属**同类未执行计划**（详见问题 11 与批次 06 任务 6.2.4）。

### 依据 2：调用点的判空分支永不进入

`PdfParser.java:79-82` 附近的调用逻辑：

```java
String tableMarkdown = extractTablesAsMarkdown(document, pageIndex);
if (tableMarkdown != null && !tableMarkdown.isBlank()) {
    // 合并表格 Markdown —— 因恒为 null，此分支永不执行
}
```

结构上"考虑了表格"，实际"永远拿不到表格"——这正是复盘报告 §3 问题 1 描述的"任务形式完成、需求未落地"。

### 依据 3：三层补救策略零落地

| 策略层 | 方案文档要求 | 代码现状 | 验证方式 |
|---|---|---|---|
| ① tabula-java | 引入表格提取库 | `pom.xml` 无该依赖，全仓 `tabula` 零命中 | grep |
| ② `[TBL]` 标记 | 结构化标记 | 全仓 `*.java`/`*.yml`/`*.sql` **零命中** | grep |
| ③ 表格感知切片 | TextSplitter 支持 | `TextSplitter.java:40-67` 无表格预处理，`splitLongText`（`:79-108`）无表格逻辑 | 阅读 |

方案 Step 5 要求的 `Chunk.contentType` / `tableCaption` 字段在 `Chunk.java:25-88` 中也不存在。

### 依据 4：DOCX 侧已实现，但受切片器拖累

`DocxParser.java:91-127` 的 `tableToMarkdown()` 是**真实实现**（按最大列数补空列、首行后插入分隔行）。这部分是符合设计的。

但 `doc/未来补充/PDF带表格的结构保留切片方案.md:12` 已自述：Markdown 表格行之间是 `\n` 而非 `\n\n`，整个表格会被视为一个段落；若超过 `chunkSize=500`，`splitLongText()` 的滑动窗口会在表格中间截断。核查确认 `TextSplitter` 当前无任何表格感知逻辑，因此**DOCX 表格转换的收益在切片阶段被部分抵消**——这是复盘报告偏差 1 未提及的"下游放大项"。

### 依据 5：Tika 依赖是死依赖

`agent-qr-knowledge/pom.xml:78-80` 引入了 `tika-parsers-standard-package:2.9.2`（体积较大），但全仓库代码中 `tika` 仅出现在 `PdfParser.java` 的注释里（`:20`、`:44`、`:138`、`:141`），**无任何实际调用**。方案文档自己也承认 Tika 的表格检测能力不足。

### 依据 6：无测试约束

`agent-qr-knowledge/src/test` 不存在，方案文档 §6.1 规划的 `PdfParserTest` / `TextSplitterTest` / `DocxParserTest` 均未创建。缺少验收标准正是该需求被稀释的原因（复盘报告 §3 问题 1）。

---

## 三、影响范围

1. **检索质量**：所有含表格的 PDF 在切片后表格语义破碎，检索时无法还原行列对应关系；含表格的 DOCX 部分受损。
2. **LLM 输出可信度**：模型基于失真的表格上下文作答，可能给出错误的数值/对应关系结论，且用户难以察觉。
3. **与偏差 3 叠加**：表格类问题（如"各部门预算是多少"）本身就是"列举型查询"，恰好同时命中聚合截断缺陷（见文档 13）。

---

## 四、修复方向

方案文档 `doc/未来补充/PDF带表格的结构保留切片方案.md` 已给出完整设计（含伪代码与验证方案），建议按三层策略实施：

1. **第①层**：引入 tabula-java，实现 `extractTablesAsMarkdown()`（替换当前 `return null`）。注意方案文档建议**保留** PDFBox 提取的普通文本，仅把表格区域替换为 Markdown，避免丢失非表格内容。
2. **第②层**：为表格内容加 `[TBL]` 前缀标记，并给 `Chunk` 增加 `contentType` / `tableCaption` 字段（需同步改 `kb_chunk` 表结构）。
3. **第③层**：改造 `TextSplitter`，识别 `[TBL]` 段落并作为一个整体处理；超长表格按"保留表头分段"策略切割。
4. **补充测试**：至少覆盖"含表格 PDF → 切片后表格完整且带表头""长表格不被拦腰截断"两条。

**注意**：第③层对 DOCX 同样有效，属于低成本高收益项，可优先实施。

---

## 五、核查边界

- 静态分析，未实际解析任何 PDF 文件验证输出。
- 未评估 tabula-java 与当前 PDFBox/Java 21 的兼容性。
- 未检查 `uploads/` 目录下是否存在真实的含表格 PDF 样本可用于验证。
- 未评估 OCR 路径（`PdfParser.performOcr` 亦为 `return null` 占位，见相关设计 §5.2.3）对本缺陷的影响。
