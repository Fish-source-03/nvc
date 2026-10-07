package org.example.agent_qr.knowledge.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 批次 06 测试用 PDF 生成工具。
 * <p>
 * 测试样本全部由 PDFBox 现场生成（ASCII 文本 + 画线表格），<b>不使用</b>仓库
 * {@code uploads/} 下的真实文档——那是个人的简历文件，只做本地人工验证，不进入测试。
 * </p>
 *
 * @author agent-qr
 */
final class PdfTestSupport {

    /** 每页每行的字符数（大文件样本用） */
    private static final int CHARS_PER_LINE = 220;

    /** 每页行数（大文件样本用） */
    private static final int LINES_PER_PAGE = 120;

    private PdfTestSupport() {
    }

    /**
     * 生成含一个 3 列 × 3 行（含表头）带边框表格的 PDF。
     *
     * @param file 输出路径
     */
    static void writeBorderedTablePdf(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.beginText();
                cs.newLineAtOffset(70, 760);
                cs.showText("Quarterly report preface before the table.");
                cs.endText();

                float x0 = 100;
                float y0 = 600;
                float w = 300;
                float h = 90;
                int cols = 3;
                int rows = 3;
                cs.setLineWidth(1);
                for (int i = 0; i <= cols; i++) {
                    float x = x0 + i * (w / cols);
                    cs.moveTo(x, y0);
                    cs.lineTo(x, y0 + h);
                }
                for (int j = 0; j <= rows; j++) {
                    float y = y0 + j * (h / rows);
                    cs.moveTo(x0, y);
                    cs.lineTo(x0 + w, y);
                }
                cs.stroke();

                String[][] data = {
                        {"Name", "Age", "City"},
                        {"Alice", "30", "Beijing"},
                        {"Bob", "25", "Shanghai"}};
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                        cs.newLineAtOffset(x0 + c * (w / cols) + 8, y0 + (rows - 1 - r) * (h / rows) + 12);
                        cs.showText(data[r][c]);
                        cs.endText();
                    }
                }
            }
            doc.save(file.toFile());
        }
    }

    /**
     * 生成不含表格的纯文本 PDF（多段落、多行，用于验证"无表格不产生伪表格标记"）。
     *
     * @param file   输出路径
     * @param marker 需要出现在正文中的标记文本
     */
    static void writePlainTextPdf(Path file, String marker) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.beginText();
                cs.newLineAtOffset(70, 720);
                cs.setLeading(16);
                cs.showText(marker);
                cs.newLine();
                for (int i = 0; i < 20; i++) {
                    cs.showText("Ordinary sentence " + i + " describes the policy in plain prose.");
                    cs.newLine();
                }
                cs.endText();

                cs.beginText();
                cs.newLineAtOffset(70, 380);
                cs.setLeading(16);
                for (int i = 0; i < 10; i++) {
                    cs.showText("Second paragraph line " + i + " keeps the layout linear.");
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(file.toFile());
        }
    }

    /**
     * 生成"大文件"PDF：内容流不压缩，逐页写入文本直到文件超过 {@code minBytes}。
     * <p>每页末尾写入 {@code END-OF-PAGE-<n>} 标记，供测试断言"最后一页也被处理"。</p>
     *
     * @param file     输出路径
     * @param minBytes 目标最小字节数
     * @return 实际生成的文件大小（字节）
     */
    static long writeLargePlainTextPdf(Path file, long minBytes) throws IOException {
        // 页数从小到大重建整份文档：PDDocument 不允许"保存后继续加页再保存"
        // （会产生悬空的 COSObject 引用），因此每轮都新建文档、只保存一次。
        long size = 0;
        for (int pages = 8; pages <= 512; pages *= 2) {
            generateLargePdf(file, pages);
            size = file.toFile().length();
            if (size >= minBytes) {
                break;
            }
        }
        return size;
    }

    private static void generateLargePdf(Path file, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int pageNo = 1; pageNo <= pages; pageNo++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                // compress=false：内容流不压缩，文件体积随文本量线性增长
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, false)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
                    cs.beginText();
                    cs.newLineAtOffset(20, 780);
                    cs.setLeading(6);
                    for (int line = 0; line < LINES_PER_PAGE; line++) {
                        cs.showText(pageLine(pageNo, line));
                        cs.newLine();
                    }
                    cs.showText("END-OF-PAGE-" + pageNo);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
    }

    private static String pageLine(int pageNo, int lineNo) {
        StringBuilder sb = new StringBuilder(CHARS_PER_LINE);
        sb.append("page-").append(pageNo).append("-line-").append(lineNo).append(' ');
        while (sb.length() < CHARS_PER_LINE) {
            sb.append("padding text for memory branch verification. ");
        }
        return sb.substring(0, CHARS_PER_LINE);
    }

    /**
     * 生成多页 PDF：第 1 页带边框表格，其余各页为纯文本，每页末尾带
     * {@code END-OF-PAGE-<n>} 标记。
     * <p>
     * 专用于拦截 "tabula 的 ObjectExtractor.close() 关闭共享 PDDocument" 这一陷阱：
     * 一旦误关闭，第 2 页起的正文会<b>静默</b>变成空白。
     * </p>
     *
     * @param file  输出路径
     * @param pages 页数
     */
    static void writeMultiPageTablePdf(Path file, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int pageNo = 1; pageNo <= pages; pageNo++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, false)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.beginText();
                    cs.newLineAtOffset(40, 760);
                    cs.setLeading(14);
                    for (int line = 0; line < 4; line++) {
                        cs.showText("page " + pageNo + " plain text line " + line);
                        cs.newLine();
                    }
                    cs.endText();

                    if (pageNo == 1) {
                        writeBorderedTable(cs);
                    }

                    cs.beginText();
                    cs.newLineAtOffset(40, 480);
                    cs.showText("END-OF-PAGE-" + pageNo);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
    }

    private static void writeBorderedTable(PDPageContentStream cs) throws IOException {
        float x0 = 100;
        float y0 = 600;
        float w = 300;
        float h = 90;
        int cols = 3;
        int rows = 3;
        cs.setLineWidth(1);
        for (int i = 0; i <= cols; i++) {
            float x = x0 + i * (w / cols);
            cs.moveTo(x, y0);
            cs.lineTo(x, y0 + h);
        }
        for (int j = 0; j <= rows; j++) {
            float y = y0 + j * (h / rows);
            cs.moveTo(x0, y);
            cs.lineTo(x0 + w, y);
        }
        cs.stroke();

        String[][] data = {
                {"Name", "Age", "City"},
                {"Alice", "30", "Beijing"},
                {"Bob", "25", "Shanghai"}};
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(x0 + c * (w / cols) + 8, y0 + (rows - 1 - r) * (h / rows) + 12);
                cs.showText(data[r][c]);
                cs.endText();
            }
        }
    }

    /** PDF 页数（用于断言"最后一页也被处理"）。 */
    static int pageCount(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return document.getNumberOfPages();
        }
    }

    /**
     * 用 PDFBox 逐页提取纯文本（与 {@link PdfParser} 的普通文本部分同参），用于回归对比。
     */
    static String expectedPlainText(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= document.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                sb.append(stripper.getText(document));
            }
            return sb.toString();
        }
    }

    /** 供测试断言使用：文件是否存在且非空。 */
    static File existingFile(Path file) {
        File f = file.toFile();
        if (!f.exists() || f.length() == 0) {
            throw new IllegalStateException("测试样本生成失败: " + f);
        }
        return f;
    }
}
