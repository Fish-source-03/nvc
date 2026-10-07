package org.example.agent_qr.knowledge.parser;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 流式解析与内存保护测试（批次 06 · 任务 6.2，问题 11）。
 * <p>
 * <b>拦截的缺陷</b>：名为 {@code parseStreaming} 的大文件分支实际是一次性
 * {@code getText(document)} 全量提取——"大文件比普通文件更危险"；
 * {@code MemoryUsageSetting} 全仓零命中，内存保护形同虚设；
 * 配置键名与设计 §8.15.2 不一致且读入后闲置。
 * </p>
 *
 * @author agent-qr
 */
class PdfParserStreamingTest {

    /** 大文件样本目标大小：1MB 出头，配合 1MB 阈值即可稳定走流式分支 */
    private static final long OVERSIZE_BYTES = 1_100_000L;

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
    @DisplayName("★ 大文件走流式分支（受限内存 + 逐页），解析结果与标准路径逐页结果完全一致")
    @Timeout(180)
    void parseStreaming_shouldMatchStandardPath_onOversizeFile() throws Exception {
        String fileName = "big.pdf";
        Path file = tempDir.resolve(fileName);
        long size = PdfTestSupport.writeLargePlainTextPdf(file, OVERSIZE_BYTES);
        PdfTestSupport.existingFile(file);
        assertThat(size)
                .as("测试样本必须超过 1MB 才能触发流式分支")
                .isGreaterThan(1_000_000L);

        // 标准路径（阈值 256MB）
        ListAppender<ILoggingEvent> standardLogs = attachLogCapture();
        String standard;
        try {
            standard = parser.parse(fileName);
        } finally {
            detachLogCapture(standardLogs);
        }
        assertThat(standard).contains("END-OF-PAGE-1");

        // 流式路径（阈值 1MB < 文件大小 → parseStreaming + MemoryUsageSetting.setupMixed(1MB)）
        ReflectionTestUtils.setField(parser, "maxMemoryMb", 1);
        ListAppender<ILoggingEvent> streamingLogs = attachLogCapture();
        String streaming;
        try {
            streaming = parser.parse(fileName);
        } finally {
            detachLogCapture(streamingLogs);
        }

        assertThat(standardLogs.list)
                .as("超过 256MB 阈值才应打印流式日志——本用例不能走流式分支")
                .noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("使用流式解析"));
        assertThat(streamingLogs.list)
                .as("超过 maxMemoryMb 的文件必须真正进入流式分支")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("使用流式解析"));

        assertThat(streaming)
                .as("流式分支必须逐页处理且与标准路径结果一致（修复前大文件路径是全量加载）")
                .isEqualTo(standard);
        // 最后一页也被处理：证明是逐页循环，而非只取首页/整册失败
        int pages = PdfTestSupport.pageCount(file);
        assertThat(pages).as("大文件样本应为多页").isGreaterThan(2);
        assertThat(streaming).contains("END-OF-PAGE-" + pages);
    }

    @Test
    @DisplayName("配置键生效：maxMemoryMb 决定分流（parser.pdf.max-memory-mb 不再是只读不用的字段）")
    void needsStreaming_shouldFollowConfigValue() throws Exception {
        ReflectionTestUtils.setField(parser, "maxMemoryMb", 1);
        assertThat(parser.needsStreaming(2L * 1024 * 1024)).isTrue();
        assertThat(parser.needsStreaming(512L * 1024)).isFalse();

        ReflectionTestUtils.setField(parser, "maxMemoryMb", 10);
        assertThat(parser.needsStreaming(9L * 1024 * 1024)).isFalse();
        assertThat(parser.needsStreaming(11L * 1024 * 1024)).isTrue();
    }

    @Test
    @DisplayName("★ 配置键一致性：代码注入键 = 设计 §8.15.2 的 parser.pdf.max-memory-mb，且 yml 已显式配置")
    void pdfMemoryKey_shouldBeAlignedWithDesignAndYml() throws Exception {
        Field field = PdfParser.class.getDeclaredField("maxMemoryMb");
        Value value = field.getAnnotation(Value.class);
        assertThat(value).as("maxMemoryMb 必须由 @Value 注入").isNotNull();
        assertThat(value.value())
                .as("键名必须与设计 §8.15.2 对齐（原 agent-qr.pdf.max-memory-mb 已废弃）")
                .isEqualTo("${parser.pdf.max-memory-mb:256}");

        String yml = readP2Yml();
        Matcher matcher = Pattern.compile(
                "(?m)^parser:\\s*\\n\\s*pdf:\\s*\\n\\s*max-memory-mb:\\s*(\\d+)").matcher(yml);
        assertThat(matcher.find())
                .as("application-p2.yml 必须显式配置 parser.pdf.max-memory-mb")
                .isTrue();
        assertThat(matcher.group(1))
                .as("yml 取值必须与代码默认值 256 一致（否则配置缺失时静默回落）")
                .isEqualTo("256");
        // 注释中会引用新旧键名，因此只统计"键行"（行首缩进 + max-memory-mb:）
        Matcher keyLines = Pattern.compile("(?m)^\\s*max-memory-mb:").matcher(yml);
        int keyLineCount = 0;
        while (keyLines.find()) {
            keyLineCount++;
        }
        assertThat(keyLineCount)
                .as("旧键名必须已从 yml 移除，只保留 parser.pdf 下的新键名一处")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("OCR 状态：开关默认 false 且键名未变；扫描件判定按平均每页字符数 < 50")
    void ocr_shouldRemainDisabledAndUnimplemented() throws Exception {
        Field field = PdfParser.class.getDeclaredField("ocrEnabled");
        Value value = field.getAnnotation(Value.class);
        assertThat(value).isNotNull();
        assertThat(value.value())
                .as("不得修改 agent-qr.pdf.ocr.enabled 的默认值（保持 false）")
                .isEqualTo("${agent-qr.pdf.ocr.enabled:false}");

        assertThat(parser.isScannedPdf("short text", 10)).isTrue();
        assertThat(parser.isScannedPdf("x".repeat(500), 10)).isFalse();
        assertThat(parser.isScannedPdf("anything", 0)).isFalse();
    }

    @Test
    @DisplayName("OCR 本体未实现：performOcr 恒返回 null（任务 6.2.4 只标注状态，不实现）")
    void performOcr_shouldStayUnimplemented() throws Exception {
        Method method = PdfParser.class.getDeclaredMethod("performOcr", File.class);
        method.setAccessible(true);
        assertThat(method.invoke(parser, new File("unused.pdf"))).isNull();
    }

    @Test
    @DisplayName("结构守护：PdfParser 必须实际使用 MemoryUsageSetting（问题 11 的验收点，防止再次退化为全量加载）")
    void source_mustUseMemoryUsageSetting() throws Exception {
        Path source = repositoryFile(Path.of("agent-qr-knowledge", "src", "main", "java",
                "org", "example", "agent_qr", "knowledge", "parser", "PdfParser.java"));
        String content = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(content)
                .as("流式分支必须用 MemoryUsageSetting 限制 PDFBox 主内存")
                .contains("MemoryUsageSetting.setupMixed(")
                .contains("Loader.loadPDF(file, memSetting.streamCache)");
        assertThat(content)
                .as("两条路径必须共用逐页提取逻辑（否则大文件路径会再次退化为全量加载）")
                .contains("extractPagesAsText(document, file, filePath)");
        assertThat(content)
                .as("不得用 try-with-resources 关闭 ObjectExtractor：tabula 1.0.5 的 close() "
                        + "会关闭共享 PDDocument，导致第 2 页起正文静默丢失")
                .doesNotContain("try (ObjectExtractor");
    }

    // ==================== 辅助 ====================

    private ListAppender<ILoggingEvent> attachLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(PdfParser.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachLogCapture(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(PdfParser.class);
        logger.detachAppender(appender);
    }

    private String readP2Yml() throws Exception {
        return Files.readString(repositoryFile(Path.of("agent-qr-web", "src", "main", "resources",
                "application-p2.yml")), StandardCharsets.UTF_8);
    }

    /** 从模块目录（mvn 运行目录）向上寻找仓库文件，兼容从仓库根目录运行。 */
    private Path repositoryFile(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到文件 " + relative + "（当前目录: " + Path.of("").toAbsolutePath() + "）");
    }
}
