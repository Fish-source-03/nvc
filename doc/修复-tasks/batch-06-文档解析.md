# 批次 06 · 文档解析修复

> **涵盖问题**：10（PDF 表格结构化保留为空实现，复盘偏差 1）、11（PdfParser 流式解析与内存保护未实现）
> **前置依赖**：批次 01（测试基建）
> **批次内顺序**：两个任务同改 `PdfParser`，**建议一次改完**
> **可并行**：与批次 02、03、08、09 无文件交集

---

## 批次目标

让含表格的 PDF 能被正确结构化，并让大文件解析不再有 OOM 风险。

> **合并说明**：任务 6.1 与 6.2 都改 `PdfParser`——6.1 修改每页调用的 `extractTablesAsMarkdown`，6.2 修改加载方式与分页循环。**若分两次改，第二次会覆盖第一次的调用点。建议一次改完。**

---

## 涉及文件

| 文件 | 任务 |
|---|---|
| `agent-qr-knowledge/.../parser/PdfParser.java` | 6.1、6.2（**合并修改**） |
| `agent-qr-knowledge/.../splitter/TextSplitter.java` | 6.1（第三层） |
| `agent-qr-knowledge/.../entity/Chunk.java` | 6.1（如需新增字段） |
| `agent-qr-web/src/main/resources/db/p2-schema.sql` | 6.1（如需新增列） |
| `agent-qr-knowledge/pom.xml` | 6.1（新增 tabula-java） |
| `agent-qr-web/src/main/resources/application-p2.yml` | 6.2（配置键对齐） |

**不得修改**：本批次之外的任何文件。

---

## 任务 6.1 — PDF 表格结构化保留（问题 10，复盘偏差 1）

> 问题详情：`doc/问题清单/10-PDF表格结构化保留为空实现.md`
> 参考方案：`doc/未来补充/PDF带表格的结构保留切片方案.md`（三层策略，含伪代码与验证方案）

### 第①层：PDF 表格提取

- [ ] **6.1.1** 引入 tabula-java（**已确认的决策**）
  - **决策：走 tabula-java**，不走 Tika
  - **背景**：代码注释（`PdfParser.java:138`）写的是「P2 简化实现，**P3 集成 Apache Tika**」，但补救方案文档（`doc/未来补充/PDF带表格的结构保留切片方案.md:55`）已论证 Tika 的 PDF 表格检测能力弱（其底层也用 PDFBox，优势在格式检测而非表格结构化）
  - **待办**：
    - 引入 `tabula-java` 依赖（需确认版本与当前 PDFBox 版本兼容）
    - **移除未使用的 `tika-parsers-standard-package:2.9.2`**——该依赖已引入但全仓**零代码引用**（死依赖，且体积大）
  - 注释中"P3 集成 Tika"的表述应一并更正

- [ ] **6.1.2** 实现 `extractTablesAsMarkdown()`
  - 当前 `PdfParser.java:140-143` 为 `return null` 空实现，调用点的判空分支永不进入
  - 按方案文档第①层实现：提取表格区域并转为 Markdown
  - **保留** PDFBox 提取的普通文本，仅把表格区域替换为 Markdown（避免丢失非表格内容）

### 第②层：结构化标记与字段

- [ ] **6.1.3** 为表格内容加 `[TBL]` 前缀标记
  - 当前全仓库 `[TBL]` 零命中

- [ ] **6.1.4** 给 `Chunk` 增加 `contentType` / `tableCaption` 字段
  - 当前 `Chunk.java` 无此二字段
  - 需同步修改 `kb_chunk` 表结构（`db/p2-schema.sql`）

### 第③层：表格感知切片

- [ ] **6.1.5** 改造 `TextSplitter` 识别 `[TBL]` 段落
  - 当前 `splitLongText` 的滑动窗口会**拦腰截断**长表格
  - 改为识别 `[TBL]` 段落并作为整体处理；超长表格按"保留表头分段"策略切割
  - **注意**：`TextSplitter` 当前已修复了原设计的死循环风险（`Math.max(start+1, ...)`），改动时**不要回退该修复**

- [ ] **6.1.6** 说明 DOCX 侧的收益
  - `DocxParser.tableToMarkdown()` 已正确实现，但因为 `TextSplitter` 不感知表格，其收益被削弱
  - 第③层改造对 DOCX 同样生效，属**低成本高收益项，可优先实施**

### 补充测试

- [ ] 用例：含表格的 PDF 解析后，表格完整保留为 Markdown 格式
- [ ] 用例：长表格（超过 `chunkSize`）不被拦腰截断，或按保留表头的策略分段
- [ ] 用例：含表格的 DOCX 在切片后表格结构完整（验证第③层对 DOCX 的收益）
- [ ] 用例：不含表格的 PDF 解析结果与修复前一致（回归）

### 验收标准

- [ ] `extractTablesAsMarkdown()` 不再是空实现
- [ ] 三层策略全部落地
- [ ] SRS §5.3.3 的要求可被验证（"表格保留为 Markdown 格式"）
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要删除 PDFBox 的普通文本提取（表格只是其中一部分内容）
- ❌ 不要回退 `TextSplitter` 已有的死循环修复
- ❌ 不要为支持表格而改变 `chunkSize` / `chunkOverlap` 的默认值（500/50）

---

## 任务 6.2 — PDF 流式解析与内存保护（问题 11）

> 问题详情：`doc/问题清单/11-PdfParser流式解析与内存保护未实现.md`

**核心事实：当前"大文件分支"比"普通分支"更危险**——普通文件逐页处理，大文件一次性全量加载。

- [ ] **6.2.1** 在 `parseStreaming` 中真正使用内存限制
  - 当前 `PdfParser.java:107-116` 是一次性 `stripper.getText(document)`，无分页、无内存设置
  - 引入 `MemoryUsageSetting`（全仓库当前零命中）：
    ```java
    MemoryUsageSetting memSetting = MemoryUsageSetting.setupMixed(maxMemoryMb * 1024L * 1024L);
    try (PDDocument document = Loader.loadPDF(file, memSetting)) { ... }
    ```

- [ ] **6.2.2** 统一两条路径的分页逻辑
  - 逐页循环当前实现在**标准路径**（`PdfParser.java:68-84`）
  - 建议提取为公共方法，让两条路径都逐页处理，避免再次出现"分流后大文件路径反而退化"

- [ ] **6.2.3** 对齐配置键名
  - 设计 §8.15.2 写 `parser.pdf.max-memory-mb`，实际为 `agent-qr.pdf.max-memory-mb`
  - 二选一对齐，并在设计文档中回填实际键名

- [ ] **6.2.4** 确认 OCR 路径的状态
  - `isScannedPdf()` 是真实实现，但 `performOcr()` 恒 `return null`（`:133`），且无 tesseract 依赖
  - 设计稿本身将 OCR 标为 P3 集成，属"有意留空"
  - **本任务只需在代码注释中明确标注"未实现"状态**，不要尝试实现 OCR

### 补充测试

- [ ] 用例：接近阈值的大 PDF 解析不 OOM（可用较小 `maxMemoryMb` + 较大 PDF 验证）
- [ ] 用例：`parseStreaming` 与标准路径的解析结果一致（同一文件两条路径输出相同）
- [ ] 用例：配置键可覆盖（修改 `agent-qr.pdf.max-memory-mb` 后生效）

### 验收标准

- [ ] `MemoryUsageSetting` 被实际使用
- [ ] `parseStreaming` 逐页处理（与标准路径一致）
- [ ] 配置键名与设计文档对齐
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要实现 OCR（明确超出本批次范围，且设计稿标注为 P3）
- ❌ 不要修改 `agent-qr.pdf.ocr.enabled` 的默认值（保持 false）

---

## 批次验收

- [ ] 任务 6.1、6.2 全部完成
- [ ] `PdfParser` 只被修改一次（两个任务的改动合并提交）
- [ ] 项目可编译，`mvn test` 通过
- [ ] 已更新 `progress.md`

## 回归验证建议

1. 用一份含表格的 PDF 走完整链路（解析 → 切片 → 入库），检查切片内容中表格是否为 Markdown 格式
2. 用一份含表格的 DOCX 走同样流程，验证第③层改造的效果
3. 用一份大 PDF（接近或超过内存阈值）验证不 OOM
4. 用一份不含表格的普通 PDF 验证解析结果未变化

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-06-文档解析.md 的全部任务。

任务 6.1 与 6.2 都修改 PdfParser.java，请合并为一次修改，不要分两次改（会互相覆盖调用点）。

重要背景：当前的"大文件分支 parseStreaming"比普通分支更危险——
普通文件逐页处理，大文件反而一次性全量加载。修复方向是让两条路径都逐页处理。

参考方案文档：doc/未来补充/PDF带表格的结构保留切片方案.md（三层策略）

不要实现 OCR（超出范围，设计稿标注为 P3）。
不要把 TextSplitter 已有的死循环修复改回去。

只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
不输出任何 API Key、Token、密码原文。
```
