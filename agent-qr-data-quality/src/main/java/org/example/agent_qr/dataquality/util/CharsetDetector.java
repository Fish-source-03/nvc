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
 * <h3>R34（批次 11）：短样本误判护栏</h3>
 * <p>
 * 实测（独立验证，14 个真实 GBK 短字段 8 个误判）：统计式检测在短样本上不可靠，
 * "张三"→KOI8-R、"研发部"→WINDOWS-1252、"这是一个测试"（6 汉字）→KOI8-R，
 * 且误判<b>无任何信号</b>。本类已加两道护栏：
 * </p>
 * <ol>
 *   <li>{@link #refineShortSampleDetection(byte[], String)}——短样本 + 检出编码无法承载中文时，
 *       按"严格 UTF-8 → 严格 GBK（≥2 汉字）"的顺序纠正，每次纠正记 WARN；</li>
 *   <li>{@link #transcodeToUtf8(byte[])} 对"短样本 + 非 ASCII"记 WARN，使误判在日志里可见。</li>
 * </ol>
 * <p>
 * <b>⚠️ 接入读入链路前的前置条件（适用于把 {@code transcodeToUtf8} 接到文件/流读取处的改动）</b>：
 * </p>
 * <ol>
 *   <li>短样本（&lt; {@link #SHORT_SAMPLE_THRESHOLD_BYTES} 字节）的检测结果<b>只可作参考</b>，
 *       不得作为"直接落库/入向量"的唯一依据——落库前必须满足下面任一条：
 *       ① 样本是 BOM 明确 / 严格 UTF-8 / 严格 GBK 且含 ≥2 汉字（本类已自动纠正）；
 *       ② 调用方按业务规则指定了编码（如数据源配置的 charset）；</li>
 *   <li>落库后必须有校验：转码结果不得含 {@link #REPLACEMENT_CHARACTER}（{@link #isDamaged(String)}），
 *       且质检侧 {@code EncodingRule} 的字节/字符串两层判定仍保留（它们是不变量，不是可选项）；</li>
 *   <li>短样本误判的后果必须"可见"：接入时保留本类的 WARN 日志并纳入告警；
 *       <b>禁止</b>在 catch/异常路径上静默回退 UTF-8 后继续写库。</li>
 * </ol>
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
     * 短样本阈值（字节，R34 护栏）。
     * <p>
     * 低于该长度的样本，juniversalchardet（统计式检测）信息不足，实测会把 GBK 短字段
     * 误判为 KOI8-R / WINDOWS-1252 等<b>无法承载中文</b>的编码
     * （"张三"、"研发部"、"这是一个测试"均实测误判，见 R34）。
     * 因此对这类样本启用"检测结果不可信"的修正与告警。
     * </p>
     */
    public static final int SHORT_SAMPLE_THRESHOLD_BYTES = 32;

    /** 短样本修正时要求的最低汉字个数（≥2 才能与"含单个高位字节的西方文本"区分开） */
    private static final int MIN_CJK_FOR_SHORT_SAMPLE_OVERRIDE = 2;

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
            String effective = refineShortSampleDetection(bytes, detected);
            log.debug("字符编码检测: detected={}, 依据=juniversalchardet", effective);
            return effective;
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
     * <p>
     * ⚠️ <b>短样本的可观测信号（R34）</b>：样本短于
     * {@link #SHORT_SAMPLE_THRESHOLD_BYTES} 且含非 ASCII 字节时，检测结果本质上不可靠
     * （统计式检测器信息不足），此处记 <b>WARN</b> 日志——任何"接入读入链路后静默写坏数据"
     * 的路径都会在日志里留下痕迹。当前唯一调用点（{@code EncodingRule} 的 byte[] 分支）
     * 只做判定不做落库，属安全方向。
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
        if (bytes.length < SHORT_SAMPLE_THRESHOLD_BYTES && hasNonAscii(bytes)) {
            log.warn("短样本转码（{} 字节 < {}）：检测结果 {} 对短样本本质不可靠，"
                            + "下游若直接落库/入向量须自行校验（见类注释『接入读入链路前的前置条件』）",
                    bytes.length, SHORT_SAMPLE_THRESHOLD_BYTES, charsetName);
        }
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
     * 短样本修正（R34 护栏）：对"库检出但明显不能承载样本内容"的判定做保守纠正。
     * <p>
     * 背景：统计式检测器在样本过短时不可靠——实测 14 个真实 GBK 短字段有 8 个被判成
     * KOI8-R / WINDOWS-1252 等单字节编码，且<b>无任何信号</b>。当前唯一调用点
     * （{@code EncodingRule} 的 byte[] 分支）误判后判失败，属安全方向；
     * 但 {@link #transcodeToUtf8(byte[])} 一旦接入读入链路，误判会<b>静默写坏数据</b>。
     * </p>
     * <p>
     * 修正规则（仅对短于 {@link #SHORT_SAMPLE_THRESHOLD_BYTES} 的样本、
     * 且检出编码<b>无法编码样本内容</b>时生效；两条都要求"证据充分"，不会覆盖普通样本）：
     * </p>
     * <ol>
     *   <li><b>能严格解码为 UTF-8 且含 ≥2 个汉字/多字节字符</b> → 按 UTF-8
     *       （合法的多字节 UTF-8 序列是强证据；检出的单字节编码连这些字符都表示不了）；</li>
     *   <li><b>不能严格解码为 UTF-8，但能严格解码为 GBK 且含 ≥2 个汉字</b> → 按 GBK
     *       （R34 建议的"短样本优先 GBK"；中文业务数据是本系统的绝对主流）。
     *       要求 ≥2 个汉字是为了避开 "Müller" 这类"西方文本恰好含一个可组成 GBK 双字节的
     *       高位字节"的误伤。</li>
     * </ol>
     * <p>
     * ⚠️ <b>已知取舍</b>：这是<b>中文优先</b>的启发式——极短的俄文 KOI8-R 等文本
     * 可能被改判为 GBK。此类数据在本系统不构成场景；每次改判都会记 WARN 日志，
     * 需要时可按日志核对。
     * </p>
     *
     * @param bytes    字节流
     * @param detected 库检出的编码名
     * @return 修正后的编码名（无需修正时原样返回）
     */
    private static String refineShortSampleDetection(byte[] bytes, String detected) {
        if (bytes.length >= SHORT_SAMPLE_THRESHOLD_BYTES || canRepresentChinese(detected)) {
            return detected;
        }

        Charset utf8 = StandardCharsets.UTF_8;
        if (canDecodeCleanly(bytes, utf8)) {
            String utf8Text = new String(bytes, utf8);
            if (countCjk(utf8Text) >= MIN_CJK_FOR_SHORT_SAMPLE_OVERRIDE) {
                log.warn("短样本（{} 字节）检测结果为 {}，但它无法表示样本中的中文内容；"
                                + "按严格 UTF-8 可无损解码改判为 UTF-8（R34 护栏）",
                        bytes.length, detected);
                return UTF_8;
            }
            return detected;
        }

        Charset gbk = Charset.forName("GBK");
        if (canDecodeCleanly(bytes, gbk)) {
            String gbkText = new String(bytes, gbk);
            if (countCjk(gbkText) >= MIN_CJK_FOR_SHORT_SAMPLE_OVERRIDE) {
                log.warn("短样本（{} 字节）检测结果为 {}（无法承载中文），但按 GBK 可无损解码出 {} 个汉字；"
                                + "改判为 GBK（R34 护栏：短样本优先 GBK）",
                        bytes.length, detected, countCjk(gbkText));
                return "GBK";
            }
        }
        return detected;
    }

    /**
     * 检出编码是否能表示中文（不能表示却在"疑似中文短样本"上被检出，即视为不可信）。
     *
     * @param charsetName 编码名
     * @return true 表示可编码汉字
     */
    private static boolean canRepresentChinese(String charsetName) {
        Charset charset = toCharset(charsetName);
        return charset.newEncoder().canEncode('中');
    }

    /**
     * 统计文本中的汉字个数（CJK 统一表意文字基本区）。
     *
     * @param text 文本
     * @return 汉字个数
     */
    private static int countCjk(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '一' && c <= '鿿') {
                count++;
            }
        }
        return count;
    }

    /**
     * 是否含非 ASCII 字节（短样本告警的触发条件之一）。
     *
     * @param bytes 字节流
     * @return true 表示存在非 ASCII 字节
     */
    private static boolean hasNonAscii(byte[] bytes) {
        for (byte b : bytes) {
            if ((b & 0x80) != 0) {
                return true;
            }
        }
        return false;
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
