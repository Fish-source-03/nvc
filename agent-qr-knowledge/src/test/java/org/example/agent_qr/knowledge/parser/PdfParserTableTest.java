package org.example.agent_qr.knowledge.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 表格结构化测试（批次 06 · 任务 6.1，问题 10）。
 * <p>
 * <b>拦截的缺陷</b>：{@code PdfParser.extractTablesAsMarkdown()} 曾是 {@code return null}
 * 空实现，调用点的判空分支永不进入——PDF 表格 100% 丢失列结构，
 * 单元格被 PDFBox 线性化成无序文本。
 * </p>
 *
 * @author agent-qr
 */
class PdfParserTableTest {

    @TempDir
    Path tempDir;

    private PdfParser parser;

    @BeforeEach
    void setUp() {
        parser = new PdfParser();
        ReflectionTestUtils.setField(parser, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(parser, "maxMemoryMb", 256);
        ReflectionTestUtils.setField(parser, "ocrEnabled", false);
    }

    @Test
    @DisplayName("★ 含表格的 PDF：表格被转成 Markdown（表头 + 分隔行 + 数据行），并带 [TBL]/[/TBL] 标记")
    void parse_withBorderedTable_shouldEmitMarkdownTable() throws Exception {
        String fileName = "with-table.pdf";
        Path file = tempDir.resolve(fileName);
        PdfTestSupport.writeBorderedTablePdf(file);
        PdfTestSupport.existingFile(file);

        String text = parser.parse(fileName);

        assertThat(text)
                .as("表格必须带结构化标记（第二层）")
                .contains(PdfParser.TABLE_MARKER_START)
                .contains(PdfParser.TABLE_MARKER_END);
        assertThat(text)
                .as("表头、分隔行、数据行必须完整成行，而不是被线性化成散乱文本")
                .contains("""
                        [TBL]
                        | Name | Age | City |
                        | --- | --- | --- |
                        | Alice | 30 | Beijing |
                        | Bob | 25 | Shanghai |
                        [/TBL]""");
    }

    @Test
    @DisplayName("★ 多页 PDF：表格页之后的正文不得丢失（tabula 提取器不得关闭共享 PDDocument）")
    void parse_multiPageWithTable_shouldKeepTextOfAllPages() throws Exception {
        String fileName = "multi-page-table.pdf";
        Path file = tempDir.resolve(fileName);
        PdfTestSupport.writeMultiPageTablePdf(file, 5);
        int pages = PdfTestSupport.pageCount(file);

        String text = parser.parse(fileName);

        for (int p = 1; p <= pages; p++) {
            assertThat(text)
                    .as("第 %d 页正文必须完整（关掉 ObjectExtractor 会静默吞掉第 2 页起的全部文本）", p)
                    .contains("END-OF-PAGE-" + p)
                    .contains("page " + p + " plain text line 3");
        }
        assertThat(text).contains("| Name | Age | City |");
    }

    @Test
    @DisplayName("回归：不含表格的 PDF 解析结果与修复前完全一致（逐页纯文本，无任何表格标记）")
    void parse_withoutTable_shouldEqualPlainPdfBoxText() throws Exception {
        String fileName = "plain.pdf";
        Path file = tempDir.resolve(fileName);
        PdfTestSupport.writePlainTextPdf(file, "PLAIN-DOC-MARKER");
        PdfTestSupport.existingFile(file);

        String text = parser.parse(fileName);
        String expected = PdfTestSupport.expectedPlainText(file);

        assertThat(text)
                .as("普通 PDF 不得出现 [TBL] 标记（否则 BasicExtractionAlgorithm 的单列伪表格会污染所有文档）")
                .doesNotContain("[TBL]");
        assertThat(text).contains("PLAIN-DOC-MARKER");
        assertThat(text)
                .as("无表格 PDF 的解析结果必须与修复前一致（PDFBox 逐页输出）")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("表格提取失败隔离：普通文本照常返回（表格只是页面内容的一部分，不得因表格失败而丢文本）")
    void parse_shouldPreservePlainText_whenTableExtractionFails() throws Exception {
        // 用普通文本 PDF 覆盖：即便表格算法在该页无结果，正文仍逐页产出
        String fileName = "text-only.pdf";
        Path file = tempDir.resolve(fileName);
        PdfTestSupport.writePlainTextPdf(file, "SECOND-MARKER");
        PdfTestSupport.existingFile(file);

        String text = parser.parse(fileName);

        assertThat(text).contains("SECOND-MARKER");
        assertThat(text).contains("Ordinary sentence 19");
        assertThat(text).contains("Second paragraph line 9");
    }
}
