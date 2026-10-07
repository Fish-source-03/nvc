package org.example.agent_qr.dataquality.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字符集检测与转码测试（批次 09 · 任务 9.2，问题 25）。
 * <p>
 * <b>拦截的缺陷</b>：旧实现 {@code detect(String)} 接收的是<b>已解码的 String</b>，
 * 方法内用 JVM 默认字符集重新编码为字节再送检测器——在 UTF-8 的 JVM 上<b>恒返回 UTF-8</b>，
 * 且回退循环用"编码/解码往返相等"判定（对任何合法 String 恒真）判别力为零；
 * 全仓库另无任何"转码"实现（只标记、不转换）。
 * </p>
 * <p>
 * 因此本测试的第一组用例是"修复前必失败"的判别性用例：GBK 字节流必须<b>不被</b>判成 UTF-8
 * 且能被正确转码回原文。
 * </p>
 * <p>
 * ⚠️ 已知边界（实测 juniversalchardet 1.0.3 的行为，非本任务引入）：
 * 极短的非 ASCII 样本（如 4 个汉字的 GBK 字节）会被库判成 ISO-8859-5/KOI8-R 等单字节编码；
 * 本测试使用真实长度的样本（≥ 6 个汉字 / ≥ 12 字节）。
 * </p>
 *
 * @author agent-qr
 */
class CharsetDetectorTest {

    /** GB 族编码名（juniversalchardet 对 GBK 数据通常报 GB18030 —— 它是 GBK 的超集，解码结果一致） */
    private static final Set<String> GB_FAMILY = Set.of("GBK", "GB18030", "GB2312", "GB_2312-80");

    /** 真实的 GBK 业务文本（≥ 12 字节，库可正确识别） */
    private static final String CHINESE_TEXT =
            "本数据源用于员工信息管理，包含姓名、部门、岗位与入职时间等字段。";

    private final CharsetDetector detector = new CharsetDetector();

    // ==================== ★ 缺陷复现点 ====================

    @Test
    @DisplayName("★ GBK 字节流被识别为 GB 族编码，且转码还原原文（旧实现恒返回 UTF-8）")
    void detect_shouldRecognizeGbkBytes() {
        byte[] gbkBytes = CHINESE_TEXT.getBytes(Charset.forName("GBK"));

        String detected = detector.detect(gbkBytes);

        assertThat(detected)
                .as("旧实现把已被错误解码的 String 再按默认字符集编码，恒返回 UTF-8")
                .isNotEqualToIgnoringCase("UTF-8");
        assertThat(detected).isIn(GB_FAMILY);
        assertThat(detector.transcodeToUtf8(gbkBytes))
                .as("转码必须还原出原文；若按 UTF-8 解码则得到乱码")
                .isEqualTo(CHINESE_TEXT);
    }

    @Test
    @DisplayName("★ 回退逻辑有判别力：GBK 字节在 UTF-8 下无法无损解码（不再'必然命中 UTF-8'）")
    void fallback_shouldRejectUtf8_forNonUtf8Bytes() {
        byte[] gbkBytes = CHINESE_TEXT.getBytes(Charset.forName("GBK"));
        byte[] utf8Bytes = CHINESE_TEXT.getBytes(StandardCharsets.UTF_8);

        assertThat(CharsetDetector.canDecodeCleanly(gbkBytes, StandardCharsets.UTF_8))
                .as("旧回退用'编码/解码往返相等'判定，首项 UTF-8 恒命中 → 判别力为零")
                .isFalse();
        assertThat(CharsetDetector.canDecodeCleanly(gbkBytes, Charset.forName("GBK"))).isTrue();
        assertThat(CharsetDetector.canDecodeCleanly(utf8Bytes, StandardCharsets.UTF_8)).isTrue();
    }

    @Test
    @DisplayName("★ 检测入口为字节层：不存在接受 String 的 detect 重载（防止回退到旧形态）")
    void detectApi_shouldBeByteOriented() throws NoSuchMethodException {
        assertThat(CharsetDetector.class.getMethod("detect", byte[].class)).isNotNull();

        assertThatThrownBy(() -> CharsetDetector.class.getMethod("detect", String.class))
                .as("String 入参无法表达文件真实编码（信息在解码时已丢失），该入口必须不存在")
                .isInstanceOf(NoSuchMethodException.class);
    }

    // ==================== BOM / 常规编码 ====================

    @Test
    @DisplayName("UTF-8 带 BOM 的字节流被识别为 UTF-8，且转码结果不含 BOM 字符")
    void detect_shouldRecognizeUtf8WithBom() {
        byte[] body = CHINESE_TEXT.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertThat(detector.detect(withBom)).isEqualToIgnoringCase("UTF-8");

        String text = detector.transcodeToUtf8(withBom);
        assertThat(text).isEqualTo(CHINESE_TEXT);
        assertThat(text.charAt(0))
                .as("BOM 若不剥离会作为正文字符进入切片，污染检索文本")
                .isNotEqualTo('\uFEFF');
    }

    @Test
    @DisplayName("UTF-8 无 BOM 的字节流被识别为 UTF-8")
    void detect_shouldRecognizeUtf8WithoutBom() {
        byte[] utf8Bytes = CHINESE_TEXT.getBytes(StandardCharsets.UTF_8);

        assertThat(detector.detect(utf8Bytes)).isEqualToIgnoringCase("UTF-8");
        assertThat(detector.transcodeToUtf8(utf8Bytes)).isEqualTo(CHINESE_TEXT);
    }

    @Test
    @DisplayName("纯 ASCII 字节流：识别为 ASCII 兼容编码（US-ASCII/UTF-8 皆可），转码结果原样保留")
    void detect_shouldFallbackToUtf8_forAscii() {
        String asciiText = "id,name,dept,salary\n1,Zhang San,RD,15000";
        byte[] ascii = asciiText.getBytes(StandardCharsets.US_ASCII);

        String detected = detector.detect(ascii);

        assertThat(detected).isIn("US-ASCII", "ASCII", "UTF-8");
        assertThat(detector.transcodeToUtf8(ascii))
                .as("ASCII 是 UTF-8 的子集，转码必须原样保留")
                .isEqualTo(asciiText);
    }

    @Test
    @DisplayName("极短 ASCII 样本（库返回 null）→ 走回退判定，结果仍为 ASCII 兼容编码")
    void detect_shouldFallback_forShortAsciiSample() {
        byte[] ascii = "abc".getBytes(StandardCharsets.US_ASCII);

        String detected = detector.detect(ascii);

        assertThat(detected).isIn("UTF-8", "US-ASCII", "ASCII");
        assertThat(detector.transcodeToUtf8(ascii)).isEqualTo("abc");
    }

    @Test
    @DisplayName("UTF-16LE（带 BOM）被识别并按该编码正确转码")
    void detect_shouldRecognizeUtf16LeWithBom() {
        byte[] utf16 = CHINESE_TEXT.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[utf16.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, withBom, 2, utf16.length);

        assertThat(detector.detect(withBom)).isEqualToIgnoringCase("UTF-16LE");
        assertThat(detector.transcodeToUtf8(withBom)).isEqualTo(CHINESE_TEXT);
    }

    @Test
    @DisplayName("空字节流不抛异常，返回 UTF-8（边界不变）")
    void detect_shouldHandleEmptyAndNull() {
        assertThat(detector.detect(null)).isEqualTo("UTF-8");
        assertThat(detector.detect(new byte[0])).isEqualTo("UTF-8");
        assertThat(detector.transcodeToUtf8(null)).isEmpty();
        assertThat(detector.transcodeToUtf8(new byte[0])).isEmpty();
    }

    // ==================== 转码链路（设计 §17.5 步骤 ③） ====================

    @Test
    @DisplayName("★ 转码链路存在：GBK → UTF-8 后可按 UTF-8 无损往返（不再是'只标记不转换'）")
    void transcodeToUtf8_shouldProduceUtf8Semantics() {
        byte[] gbkBytes = CHINESE_TEXT.getBytes(Charset.forName("GBK"));

        String text = detector.transcodeToUtf8(gbkBytes);

        // 转码后的文本以 UTF-8 落库/入向量时应能无损往返（含非 ASCII 字符）
        byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
        assertThat(new String(utf8Bytes, StandardCharsets.UTF_8)).isEqualTo(CHINESE_TEXT);
        assertThat(text).doesNotContain(String.valueOf(CharsetDetector.REPLACEMENT_CHARACTER));
    }

    @Test
    @DisplayName("替换字符统计：U+FFFD 是'上游解码失败'的判别依据")
    void countReplacementCharacters_shouldCountDamage() {
        assertThat(detector.countReplacementCharacters(null)).isZero();
        assertThat(detector.countReplacementCharacters("正常文本")).isZero();
        assertThat(detector.countReplacementCharacters("损坏�文本�")).isEqualTo(2);
        assertThat(detector.isDamaged("损坏�")).isTrue();
        assertThat(detector.isDamaged("正常")).isFalse();
    }
}
