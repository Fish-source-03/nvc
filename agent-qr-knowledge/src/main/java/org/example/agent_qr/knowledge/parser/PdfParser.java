package org.example.agent_qr.knowledge.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.agent_qr.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * PDF 文件解析器（P2 增强版）。
 * <p>
 * P1 原有：PDFBox 基础文本提取。
 * P2 增强：大文件内存保护 + 逐页解析、tabula-java 表格结构化（转 Markdown）、扫描件检测。
 * </p>
 * <p>
 * 批次 06 改动（问题 10 + 问题 11）：
 * <ol>
 *   <li>{@link #extractTablesAsMarkdown} 不再是空实现——用 tabula-java 提取表格并转 Markdown，
 *       以 {@code [TBL]}/{@code [/TBL]} 包裹（第二层结构化标记），供
 *       {@code TextSplitter} 做表格感知切片；</li>
 *   <li>{@link #parseStreaming} 真正使用 {@link MemoryUsageSetting} 限制主内存占用，
 *       并与标准路径共用逐页提取逻辑（修复"大文件分支比普通分支更危险"的退化）；</li>
 *   <li>配置键名对齐设计 §8.15.2：{@code parser.pdf.max-memory-mb}
 *       （原 {@code agent-qr.pdf.max-memory-mb} 已废弃）。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class PdfParser {

    /**
     * 表格块起始标记（第二层：结构化标记）。
     * <p>与 {@code TextSplitter} 的表格感知切片约定一致：该标记包裹的内容是原子语义单元，
     * 切片时不得拦腰截断。</p>
     */
    static final String TABLE_MARKER_START = "[TBL]";

    /** 表格块结束标记 */
    static final String TABLE_MARKER_END = "[/TBL]";

    /**
     * 有效表格的最小行数/列数。
     * <p>低于该阈值视为误检：{@link BasicExtractionAlgorithm} 对普通文本页会返回
     * "单列、每行一句"的伪表格（实测结论），必须用列数下限把它挡掉。</p>
     */
    private static final int MIN_TABLE_ROWS = 2;
    private static final int MIN_TABLE_COLS = 2;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 内存模式最大文件大小（MB），同时作为流式分支的 PDFBox 主内存上限。
     * <p>批次 06 · 任务 6.2.3：键名与设计《系统详细设计说明书》§8.15.2 对齐
     * （原 {@code agent-qr.pdf.max-memory-mb} 已废弃，全仓无其他引用）。</p>
     */
    @Value("${parser.pdf.max-memory-mb:256}")
    private int maxMemoryMb;

    /**
     * OCR 开关（默认 false）。
     * <p>⚠️ OCR 本体<b>未实现</b>（设计标为 P3 集成 Tesseract），详见 {@link #performOcr}。</p>
     */
    @Value("${agent-qr.pdf.ocr.enabled:false}")
    private boolean ocrEnabled;

    /**
     * 解析 PDF 文件（P2 增强版）。
     * <p>
     * 文件 &gt; maxMemoryMb → 流式解析分支（内存受限 + 逐页）；
     * 否则标准解析（同样逐页 + 表格结构化）；
     * OCR 启用且检测为扫描件时尝试 OCR（当前未实现，回退 PDFBox 文本）。
     * </p>
     *
     * @param filePath 文件相对路径
     * @return 提取的文本内容
     */
    public String parse(String filePath) {
        Path fullPath = Paths.get(uploadDir, filePath);
        File file = fullPath.toFile();

        if (!file.exists()) {
            throw new BusinessException("文件不存在: " + filePath);
        }

        // 大文件 → 流式解析
        if (needsStreaming(file.length())) {
            log.info("PDF 文件过大({}MB)，使用流式解析: {}", file.length() / 1024 / 1024, filePath);
            return parseStreaming(file, filePath);
        }

        // 标准解析
        try (PDDocument document = Loader.loadPDF(file)) {
            return extractPagesAsText(document, file, filePath);
        } catch (Exception e) {
            log.error("PDF 文件解析失败: {}", filePath, e);
            throw new BusinessException("PDF 文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 分流判断：文件大小超过 {@link #maxMemoryMb} 时走内存受限的流式路径。
     * <p>抽成独立方法便于单测直接验证配置项（{@code parser.pdf.max-memory-mb}）生效。</p>
     *
     * @param fileLength 文件字节数
     * @return true 表示应走流式（内存受限）分支
     */
    boolean needsStreaming(long fileLength) {
        return fileLength > (long) maxMemoryMb * 1024 * 1024;
    }

    /**
     * 流式解析大文件（P2 新增；批次 06 · 任务 6.2.1 / 6.2.2 修复）。
     * <p>
     * <b>修复内容</b>：原实现是一次性 {@code stripper.getText(document)} 全量提取，
     * 既不流式也不分页——大文件反而比普通文件更危险。现改为：
     * </p>
     * <ol>
     *   <li>用 {@link MemoryUsageSetting#setupMixed(long)} 限制 PDFBox 主内存占用，
     *       超出部分落临时文件（PDFBox 3 通过 {@code streamCache} 传给 Loader）；</li>
     *   <li>与标准路径共用 {@link #extractPagesAsText} 逐页处理，逐页释放。</li>
     * </ol>
     *
     * @param file     文件
     * @param filePath 文件相对路径（日志用）
     * @return 提取的文本内容
     */
    private String parseStreaming(File file, String filePath) {
        // 主内存上限 = maxMemoryMb，超出后溢出到临时文件，避免大 PDF 直接把堆打满
        MemoryUsageSetting memSetting = MemoryUsageSetting.setupMixed((long) maxMemoryMb * 1024 * 1024);
        log.debug("流式解析内存设置: {}", memSetting);
        try (PDDocument document = Loader.loadPDF(file, memSetting.streamCache)) {
            return extractPagesAsText(document, file, filePath);
        } catch (Exception e) {
            log.error("PDF 流式解析失败: {}", filePath, e);
            throw new BusinessException("PDF 流式解析失败: " + e.getMessage());
        }
    }

    /**
     * 两条路径共用的逐页提取逻辑（批次 06 · 任务 6.2.2）。
     * <p>
     * 逐页取文本（每页处理完即可被 GC 回收，不把全文整页对象留在内存），
     * 每页再追加该页表格的 Markdown（{@code [TBL]} 块）。
     * </p>
     *
     * @param document 已加载的 PDF 文档
     * @param file     文件（OCR 回退用）
     * @param filePath 文件相对路径（日志用）
     * @return 提取的文本内容
     * @throws IOException 文本提取失败
     */
    private String extractPagesAsText(PDDocument document, File file, String filePath) throws IOException {
        int pageCount = document.getNumberOfPages();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        // ⚠️ 不要用 try-with-resources 关闭 ObjectExtractor：tabula 1.0.5 的
        // ObjectExtractor.close() 会直接调用传入的 PDDocument.close()（javap 实证：
        // close() → PDDocument.close()）。文档一旦被关闭，后续所有页的文本与表格提取都会
        // 静默返回空白（实测：第 2 页起正文全部丢失），因此整个文档共享一个 extractor 且
        // 不调用 close()——PDDocument 的生命周期由本方法调用方的 try-with-resources 负责。
        ObjectExtractor tableExtractor = new ObjectExtractor(document);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);
            sb.append(stripper.getText(document));

            // 表格结构化（批次 06 · 任务 6.1.2）：普通文本照常保留，表格另以 Markdown 追加
            String tableMarkdown = extractTablesAsMarkdown(tableExtractor, i);
            if (tableMarkdown != null && !tableMarkdown.isBlank()) {
                sb.append("\n").append(tableMarkdown).append("\n");
            }
        }

        String text = sb.toString();

        // OCR 检测（当前仅检测；OCR 本体未实现，见 performOcr）
        if (ocrEnabled && isScannedPdf(text, pageCount)) {
            log.info("检测到扫描件，尝试 OCR: {}", filePath);
            String ocrText = performOcr(file);
            if (ocrText != null && !ocrText.isBlank()) {
                text = ocrText;
            }
        }

        log.debug("PDF 解析完成: {}, 页数={}, 字符数={}", filePath, pageCount, text.length());
        return text;
    }

    /**
     * 检测是否为扫描件：平均每页字符数 &lt; 50。
     */
    boolean isScannedPdf(String text, int pageCount) {
        if (pageCount <= 0) return false;
        double avgCharsPerPage = (double) text.length() / pageCount;
        return avgCharsPerPage < 50;
    }

    /**
     * 执行 OCR。
     * <p>
     * ⚠️ <b>当前未实现（有意留空）</b>：设计稿将 OCR 标注为 P3 集成 Tesseract，
     * 工程内既无 tesseract 依赖、也无识别实现，本方法恒返回 {@code null}。
     * 因此当 {@code agent-qr.pdf.ocr.enabled=true} 且判定为扫描件时，
     * {@link #parse} 只会记录日志并<b>回退到 PDFBox 已提取的文本</b>。
     * 批次 06 · 任务 6.2.4 只要求明确该状态，不实现 OCR。
     * </p>
     *
     * @param file 待识别文件
     * @return 恒为 {@code null}（未实现）
     */
    private String performOcr(File file) {
        log.warn("OCR 未实现（P3 计划集成 Tesseract），本次跳过并回退 PDFBox 提取结果: {}", file.getName());
        return null;
    }

    /**
     * 提取指定页的表格并转为 Markdown（批次 06 · 任务 6.1.2，问题 10）。
     * <p>
     * 实现策略（方案文档《PDF带表格的结构保留切片方案》第一层）：
     * </p>
     * <ol>
     *   <li>主路径 {@link SpreadsheetExtractionAlgorithm}——识别有边框表格；</li>
     *   <li>主路径无结果时降级 {@link BasicExtractionAlgorithm}——识别无边框（按列间距对齐）表格；
     *       降级结果必须通过列数下限校验，避免把普通文本页的"单列文本行"误判为表格；</li>
     *   <li>结果转 Markdown，并以 {@code [TBL]} / {@code [/TBL]} 包裹（第二层结构化标记）。</li>
     * </ol>
     * <p>
     * 失败隔离：单页表格提取异常（如畸形 PDF）只记录告警并返回 null，
     * <b>不影响</b>该页 PDFBox 普通文本的产出。
     * </p>
     *
     * @param extractor 文档级共享的 tabula 提取器（<b>调用方负责不关闭它</b>，见
     *                  {@link #extractPagesAsText} 的说明）
     * @param pageIndex 页下标（0 基）
     * @return 该页全部表格的 Markdown（带标记）；无表格或提取失败时返回 {@code null}
     */
    private String extractTablesAsMarkdown(ObjectExtractor extractor, int pageIndex) {
        try {
            Page page = extractor.extract(pageIndex + 1);
            if (!page.hasText()) {
                return null;
            }

            List<Table> tables = new SpreadsheetExtractionAlgorithm().extract(page);
            if (tables.isEmpty()) {
                tables = new BasicExtractionAlgorithm().extract(page);
            }

            StringBuilder sb = new StringBuilder();
            for (Table table : tables) {
                if (!isStructuredTable(table)) {
                    continue;
                }
                String markdown = tableToMarkdown(table);
                if (markdown.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(TABLE_MARKER_START).append('\n')
                  .append(markdown)
                  .append(TABLE_MARKER_END);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            log.warn("PDF 第 {} 页表格提取失败，降级为纯文本: {}", pageIndex + 1, e.getMessage());
            return null;
        }
    }

    /**
     * 表格有效性判定：至少 {@value #MIN_TABLE_ROWS} 行且 {@value #MIN_TABLE_COLS} 列。
     */
    private boolean isStructuredTable(Table table) {
        if (table == null) {
            return false;
        }
        return table.getRowCount() >= MIN_TABLE_ROWS && table.getColCount() >= MIN_TABLE_COLS;
    }

    /**
     * 将 tabula {@link Table} 转为 Markdown 表格（首行作表头并补分隔行）。
     * <p>
     * tabula 1.0.5 的 {@code Table} 没有 {@code getCols()}，列数以 {@code getColCount()} 为准
     * （行内单元格数不足时补空列，保证行列对齐）。
     * </p>
     *
     * @param table tabula 表格
     * @return Markdown 表格文本（不含 {@code [TBL]} 标记）
     */
    private String tableToMarkdown(Table table) {
        List<List<RectangularTextContainer>> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        int colCount = Math.max(table.getColCount(), rows.get(0).size());
        if (colCount <= 0) {
            return "";
        }

        StringBuilder md = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<RectangularTextContainer> row = rows.get(r);
            md.append('|');
            for (int c = 0; c < colCount; c++) {
                String cell = c < row.size() ? row.get(c).getText() : "";
                md.append(' ').append(escapeCell(cell)).append(" |");
            }
            md.append('\n');

            // 首行后补 Markdown 分隔行
            if (r == 0) {
                md.append('|');
                for (int c = 0; c < colCount; c++) {
                    md.append(" --- |");
                }
                md.append('\n');
            }
        }
        return md.toString();
    }

    /**
     * 单元格文本清洗：折叠换行/制表符为空格，转义 Markdown 竖线，去除首尾空白。
     */
    private String escapeCell(String cell) {
        if (cell == null) {
            return "";
        }
        return cell.replace('\r', ' ')
                   .replace('\n', ' ')
                   .replace('\t', ' ')
                   .replace("|", "\\|")
                   .trim();
    }
}
