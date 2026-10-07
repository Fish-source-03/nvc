package org.example.agent_qr.dataquality.util;

import lombok.extern.slf4j.Slf4j;
import org.mozilla.universalchardet.UniversalDetector;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 字符集检测与转码工具（设计 §17.5 字符集自动检测与转码）。
 * <p>
 * <b>批次 09 · 任务 9.2（问题 25）的三处改造</b>：
 * </p>
 * <ol>
 *   <li><b>9.2.1 检测移到字节层</b>：主入口由 {@code detect(String)} 改为 {@code detect(byte[])}。
 *       旧实现的入参是<b>已解码的 String</b>，方法内又用 JVM 默认字符集重新编码成字节再送检测器——
 *       编码特征在传入前就已丢失，在 UTF-8 的 JVM 上恒返回 UTF-8。
 *       现在直接分析<b>原始字节流</b>，调用方应在读到文件/流的第一时间调用（见
 *       {@link #transcodeToUtf8(byte[])}）；</li>
 *   <li><b>9.2.2 回退逻辑有判别力</b>：旧回退用
 *       {@code text.equals(new String(text.getBytes(cs), cs))} 判定，而首项 UTF-8 对任何合法
 *       String 都能无损往返 → 循环必然在第一次迭代命中，判别力为零。
 *       现在按"BOM → 库检测 → 严格解码可行性"三步判定：候选编码必须能<b>无损解码</b>
 *       整段字节（{@link CodingErrorAction#REPORT}），非法/不可映射的候选直接淘汰；</li>
 *   <li><b>9.2.3 转码链路</b>：新增 {@link #transcodeToUtf8(byte[])}——
 *       按检测结果解码后产出 UTF-8 文本（设计 §17.5 步骤 ③），不再只"标记"不转换。</li>
 * </ol>
 * <p>
 * <b>关于设计 §17.5 步骤 ② 的"置信度 ≥ 0.8"</b>：{@code UniversalDetector} 只返回
 * charset 名或 {@code null}，<b>不暴露任何置信度数值</b>，因此本实现不设阈值常量
 * （避免留下一个永远为真的假分支）。实际处理方式为：
 * </p>
 * <ul>
 *   <li>返回非 null → 直接采纳（等价于"置信度足够"）；</li>
 *   <li>返回 null（样本过短/纯 ASCII/特征不足）→ 走<b>可判别的</b>回退序列
 *       {@link #FALLBACK_CHARSETS}，取第一个能无损解码整段字节的编码；</li>
 *   <li>回退序列全部失败 → 记 WARN 并回退 UTF-8（宽松解码，损坏处产生 {@code U+FFFD}），
 *       由 {@link org.example.agent_qr.dataquality.rule.EncodingRule} 在质检报告中标记。</li>
 * </ul>
 * <p>
 * ⚠️ <b>能力边界</b>：编码检测只能在<b>字节层</b>做。质检记录（{@code Map<String, Object>}）
 * 里的值已是解码后的 String，无法反推原编码——因此 {@link EncodingRule} 侧改为检测
 * "是否已含 {@code U+FFFD} 替换字符"（上游用错编码解码的不可逆痕迹），
 * 两者互补：字节层负责"读对"，字符串层负责"发现已读错"。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class CharsetDetector {

    /** 默认/兜底编码名（检测不出来时使用） */
    public static final String UTF_8 = "UTF-8";

    /** Unicode 替换字符 U+FFFD —— 解码失败的显式痕迹 */
    public static final char REPLACEMENT_CHARACTER = '\uFFFD';

    /** 字节序标记 BOM（U+FEFF） */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * 回退编码序列（按优先级排序，沿用设计 §17.5 的清单）。
     * <p>仅当 {@code UniversalDetector} 返回 null 时使用；判定方式是"能否无损解码整段字节"。</p>
     */
    private static final String[] FALLBACK_CHARSETS = {
            "UTF-8", "GBK", "GB2312", "ISO-8859-1", "Windows-1252"
    };

    /**
     * 检测字节流的字符编码（<b>主入口</b>，任务 9.2.1）。
     *
     * @param bytes 原始字节流（应为读取文件/流的<b>第一手</b>字节，不得是 String 重新编码的结果）
     * @return 检测到的编码名称；无法判定时返回 {@link #UTF_8}
     */
    public String detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return UTF_8;
        }

        // ① BOM 优先：BOM 是唯一 100% 确定的信号，且可同时确定字节序
        String bomCharset = detectByBom(bytes);
        if (bomCharset != null) {
            log.debug("字符编码检测: detected={}, 依据=BOM", bomCharset);
            return bomCharset;
        }

        // ② juniversalchardet（不做置信度阈值——该 API 不暴露置信度，见类注释）
        String detected = detectByLibrary(bytes);
        if (detected != null) {
            log.debug("字符编码检测: detected={}, 依据=juniversalchardet", detected);
            return detected;
        }

        // ③ 可判别的回退：候选编码必须能无损解码整段字节
        String fallback = detectByDecodability(bytes);
        log.debug("字符编码检测: detected={}, 依据=无损解码回退", fallback);
        return fallback;
    }

    /**
     * 按检测结果解码并以 UTF-8 输出（设计 §17.5 步骤 ③ 的转码，任务 9.2.3）。
     * <p>
     * 修复前全仓库 {@code 转码}/{@code transcode} 零命中——只做"标记"不做转换。
     * 本方法把步骤 ①→②→③ 串起来：检测编码 → 用它解码 → 得到可直接写入下游
     * （UTF-8 落库/入向量）的文本。
     * </p>
     * <p>
     * 若检测结果为带 BOM 的 UTF-8/UTF-16，解码后<b>去掉 BOM</b>（{@code U+FEFF}）——
     * 否则它会被当作正文首字符进入切片，污染检索文本。
     * </p>
     *
     * @param bytes 原始字节流
     * @return 解码后的文本（UTF-8 语义）；入参为 null/空时返回空串
     */
    public String transcodeToUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String charsetName = detect(bytes);
        String text = decode(bytes, charsetName);

        // 去掉解码后残留的 BOM（U+FEFF）
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        return text;
    }

    /**
     * 按指定编码解码（宽松模式：非法/不可映射字节替换为 {@code U+FFFD}，不抛异常）。
     *
     * @param bytes       原始字节流
     * @param charsetName 编码名
     * @return 解码文本
     */
    public String decode(byte[] bytes, String charsetName) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        Charset charset = toCharset(charsetName);
        return new String(bytes, charset);
    }

    /**
     * 统计文本中的替换字符 {@code U+FFFD} 数量。
     * <p>
     * 供字符串层判定"上游是否已用错误编码解码"——解码损坏是<b>不可逆</b>的，
     * 在 String 上表现为若干 {@code U+FFFD}。
     * </p>
     *
     * @param text 待检查文本（可为 null）
     * @return 替换字符个数
     */
    public int countReplacementCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == REPLACEMENT_CHARACTER) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断文本是否已出现解码损坏痕迹（含 {@code U+FFFD}）。
     *
     * @param text 待检查文本
     * @return true 表示含替换字符
     */
    public boolean isDamaged(String text) {
        return countReplacementCharacters(text) > 0;
    }

    // ==================== 内部实现 ====================

    /**
     * 按 BOM 判定编码（确定性信号，优先于统计式检测）。
     *
     * @param bytes 字节流
     * @return BOM 对应的编码名；无 BOM 时返回 null
     */
    private static String detectByBom(byte[] bytes) {
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE
                && (bytes[2] & 0xFF) == 0x00 && (bytes[3] & 0xFF) == 0x00) {
            return "UTF-32LE";
        }
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x00 && (bytes[1] & 0xFF) == 0x00
                && (bytes[2] & 0xFF) == 0xFE && (bytes[3] & 0xFF) == 0xFF) {
            return "UTF-32BE";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return "UTF-16BE";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        return null;
    }

    /**
     * 调用 juniversalchardet 检测。
     * <p>该库不暴露置信度（只返回编码名或 null），因此检出即采纳，不做阈值判断。</p>
     *
     * @param bytes 字节流
     * @return 检测到的编码名；未检出返回 null
     */
    private static String detectByLibrary(byte[] bytes) {
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(bytes, 0, bytes.length);
        detector.dataEnd();
        return detector.getDetectedCharset();
    }

    /**
     * 回退检测：按优先级返回第一个能<b>无损解码</b>整段字节的编码（任务 9.2.2）。
     * <p>
     * 旧实现用"编码/解码往返相等"判定，而该条件对任何合法 String 恒真（首项 UTF-8 必命中），
     * 判别力为零。此处改用严格解码（{@link CodingErrorAction#REPORT}）：
     * 只要出现非法字节序列或不可映射字符即淘汰该候选，
     * 因此"GBK 字节流"不会被 UTF-8 抢先命中（GBK 字节不是合法 UTF-8）。
     * </p>
     * <p>
     * {@code ISO-8859-1} 能解码任意字节序列，故本方法总能给出结果（兜底为它）。
     * </p>
     *
     * @param bytes 字节流
     * @return 回退判定的编码名
     */
    private static String detectByDecodability(byte[] bytes) {
        for (String charsetName : FALLBACK_CHARSETS) {
            if (!Charset.isSupported(charsetName)) {
                continue;
            }
            if (canDecodeCleanly(bytes, Charset.forName(charsetName))) {
                return charsetName;
            }
        }
        log.warn("字符编码回退失败：候选编码（{}）均无法无损解码该字节流，按 UTF-8 宽松解码",
                String.join(",", FALLBACK_CHARSETS));
        return UTF_8;
    }

    /**
     * 判断字节流能否被指定编码无损解码。
     *
     * @param bytes   字节流
     * @param charset 候选编码
     * @return true 表示整段字节均可正确解码（无非法序列、无不可映射字符）
     */
    static boolean canDecodeCleanly(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer ignored = decoder.decode(ByteBuffer.wrap(bytes));
            return ignored != null;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /**
     * 解析编码名（不支持的编码回退 UTF-8，不抛异常）。
     *
     * @param charsetName 编码名
     * @return {@link Charset}
     */
    private static Charset toCharset(String charsetName) {
        if (charsetName == null || charsetName.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(charsetName);
        } catch (Exception e) {
            log.warn("未知编码 {}，回退 UTF-8", charsetName);
            return StandardCharsets.UTF_8;
        }
    }
}
