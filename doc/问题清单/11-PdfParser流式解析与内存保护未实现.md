# 11 · PdfParser 流式解析与内存保护未实现

> **严重程度**：🟠 中高
> **所属模块**：agent-qr-knowledge（PdfParser）
> **设计依据**：《系统详细设计说明书》§8.15.2 PdfParser — 流式解析 + 内存保护
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行、未解析真实 PDF）

---

## 一、问题描述

设计 §8.15.2 要求 PdfParser 对大文件使用"流式解析 + 内存保护"：通过 PDFBox 的 `MemoryUsageSetting.setMaxMemoryUsage(...)` 限制内存占用，并逐页处理。

实际实现存在三个问题，且**大文件分支比普通分支更不安全**：

1. `MemoryUsageSetting` 全仓库**零命中**，内存保护完全未实现；
2. 名为 `parseStreaming` 的大文件分支，实际是**一次性 `getText(document)` 全量提取**，既不流式也不分页；
3. 逐页循环反而实现在**标准路径**中——也就是说，普通文件是逐页处理的，大文件是全量加载的，与"为保护内存而分流"的意图完全相反。

另外，配置键名与设计文档不一致（文档 `parser.pdf.max-memory-mb` ↔ 实际 `agent-qr.pdf.max-memory-mb`），且该配置项**没有被任何代码使用**于内存控制，只被读进字段后闲置。

---

## 二、推断依据

### 依据 1：`MemoryUsageSetting` 全仓零命中

```bash
grep -rn "MemoryUsageSetting" --include=*.java . | grep -v target
# 输出为空
```

### 依据 2：大文件分支是全量提取

`agent-qr-knowledge/src/main/java/org/example/agent_qr/knowledge/parser/PdfParser.java:107-116`

```java
/**
 * 流式解析大文件（P2 新增）。
 */
private String parseStreaming(File file) {
    try (PDDocument document = Loader.loadPDF(file)) {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return stripper.getText(document);      // ← 一次性提取全文档
    } catch (IOException e) { ... }
}
```

无 `setStartPage` / `setEndPage` 分页，无 `MemoryUsageSetting`，无流式写入。注释声称"流式"，实现是全量。

### 依据 3：逐页循环反而在标准路径

`PdfParser.java:68-84`（标准解析路径）

```java
PDFTextStripper stripper = new PDFTextStripper();
stripper.setSortByPosition(true);

StringBuilder sb = new StringBuilder();
for (int i = 0; i < pageCount; i++) {
    stripper.setStartPage(i + 1);
    stripper.setEndPage(i + 1);
    String pageText = stripper.getText(document);
    sb.append(pageText);
    ...
}
```

即：**普通文件逐页处理（内存友好），大文件全量加载（内存最危险）**。分流的意图是保护大文件，实际效果相反。

### 依据 4：配置项存在但未用于内存控制

`PdfParser.java:32-33`

```java
/** 内存模式最大文件大小：256MB */
@Value("${agent-qr.pdf.max-memory-mb:256}")
private int maxMemoryMb;
```

该字段被读入后，仅用于**分流判断**（决定走标准路径还是 `parseStreaming`），不会作用于 PDFBox 的内存策略——因为内存策略压根没有配置。

配置键名也与设计文档 §8.15.2（`parser.pdf.max-memory-mb`）不一致；对应 yml 位置在 `agent-qr-web/src/main/resources/application-p2.yml:55-58`。

### 依据 5：OCR 路径同样为空占位

`PdfParser.java:121-134`：`isScannedPdf()` 是真实实现（按平均每页字符数 < 50 判定），但 `performOcr()` 恒 `return null`（`:133`），且 `pom.xml` 中无 tesseract 依赖。设计稿本身也将 OCR 标注为 P3 集成，属"有意留空"，但需在文档中明确状态。

---

## 三、影响范围

1. **大文件 OOM 风险**：分流逻辑本意是保护内存，实际上把最大的文件交给最激进的全量加载路径。大 PDF 上传可能触发 `OutOfMemoryError`，进而影响整个应用（而非仅该次上传失败）。
2. **配置误导**：`agent-qr.pdf.max-memory-mb: 256` 让运维以为内存已受控，实际未生效。
3. **与文档 01 无关但同类**：属于"配置项/注释宣称的能力与实际实现不符"的同一模式。

---

## 四、修复方向

1. **在 `parseStreaming` 中真正使用内存限制**：
   ```java
   MemoryUsageSetting memSetting = MemoryUsageSetting.setupMixed(maxMemoryMb * 1024L * 1024L);
   try (PDDocument document = Loader.loadPDF(file, memSetting)) { ... }
   ```
   并改为逐页处理（可复用标准路径的循环结构）。
2. **统一两条路径**：建议把逐页循环提取为公共方法，避免再次出现"分流后大文件路径反而退化"的情况。
3. **对齐配置键名**：把 `agent-qr.pdf.max-memory-mb` 与设计文档任一方向对齐，并在文档中回填实际键名。
4. **补充大文件测试**：至少覆盖"接近阈值的 PDF 解析不 OOM"，避免分流逻辑再次退化而无人发现。

---

## 五、核查边界

- 静态分析，未构造大文件实测内存占用，未复现 OOM。
- 未确认 PDFBox 版本对 `MemoryUsageSetting.setupMixed` 的支持情况（需在实施时确认依赖版本）。
- 未评估 `parseStreaming` 的实际触发频率（取决于上传文件大小分布）。
