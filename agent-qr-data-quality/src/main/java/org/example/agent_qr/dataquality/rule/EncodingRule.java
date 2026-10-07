package org.example.agent_qr.dataquality.rule;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.dataquality.entity.RuleResult;
import org.example.agent_qr.dataquality.util.CharsetDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 编码检查规则（规则类型 {@code encoding}）—— 与 {@link CharsetDetector} 互补的"记录级"判定。
 * <p>
 * <b>批次 09 · 任务 9.2（问题 25）的改造</b>：旧实现把<b>已解码的 String</b>
 * 传给 {@code charsetDetector.detect(text)}，而检测器内部又用 JVM 默认字符集重新编码为字节，
 * 在 UTF-8 的 JVM 上恒返回 UTF-8——该规则形同虚设，GBK 乱码记录永远"通过"。
 * </p>
 * <p>
 * 检测已在字节层实现（{@link CharsetDetector#detect(byte[])} /
 * {@link CharsetDetector#transcodeToUtf8(byte[])}），但质检记录的取值是
 * {@code Map<String, Object>}——<b>值已是解码后的 String 时无法反推原编码</b>。
 * 因此本规则按取值形态分流：
 * </p>
 * <ol>
 *   <li><b>值为 {@code byte[]}</b>（原始字节，未被上游解码）→ 直接做字节层检测，
 *       非 UTF-8 即判失败，并提示可用 {@code transcodeToUtf8} 转码；</li>
 *   <li><b>值为 {@code String}</b>（已被上游解码）→ 检查是否含替换字符 {@code U+FFFD}。
 *       这是"用错编码解码"留下的<b>不可逆</b>痕迹，且是 String 层唯一可判别的信号；
 *       编码/解码往返等同这类条件对任何合法 String 恒真，不能作为判据。</li>
 * </ol>
 * <p>
 * <b>批次 10 · 任务 10.1（问题 35）</b>：新增 {@code quality_rule.params.charset}
 * 配置——字节层的"期望编码"由固定 UTF-8 变为可配置（默认仍为 UTF-8），
 * 使规则管理页的"字符集"参数真实生效；<b>未配置 charset 时判定与消息均与改造前完全一致</b>
 * （包括失败原因文案"非 UTF-8"，已有测试断言该文案）。
 * </p>
 * <p>
 * ⚠️ <b>已知边界</b>：若上游用某个"能解码全部字节"的编码（如 ISO-8859-1）读错了文件，
 * 结果是可逆的乱码而不含 {@code U+FFFD}，本规则在 String 层<b>无法</b>识别——
 * 根治手段是在读取处（连接器）接入字节层检测与转码，该改动不在本批次文件范围内。
 * </p>
 * <p>
 * ⚠️ <b>将来接入读入链路时的前置条件（R34）</b>：本规则是本类字节层能力的<b>唯一调用点</b>，
 * 且处于安全方向（{@code byte[]} 分支误判只导致判失败，不会写数据）。
 * 若要把 {@link CharsetDetector#transcodeToUtf8(byte[])} 接到文件/流读取处，
 * 必须先满足 {@link CharsetDetector} 类注释"接入读入链路前的前置条件"一节
 * （短样本结果只可作参考、落库前/后有校验、误判必须可见），
 * 否则会从"判失败"退化为"静默写坏数据"。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class EncodingRule implements QualityRule {

    /** 规则类型编码 */
    public static final String TYPE = "encoding";

    /** 校验参数名：期望编码（缺省为 UTF-8） */
    public static final String PARAM_CHARSET = "charset";

    /**
     * 中文（GB）族编码名。
     * <p>juniversalchardet 对 GBK 数据通常报 {@code GB18030}（GBK 的超集），
     * 故族内按等价处理。</p>
     */
    private static final Set<String> GB_FAMILY = Set.of(
            "GBK", "GB18030", "GB2312", "GB_2312-80", "GB18030-2000", "MS936", "CP936", "X-GBK");

    @Autowired
    private CharsetDetector charsetDetector;

    @Override
    public String getName() {
        return "编码";
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public RuleResult evaluate(Map<String, Object> record) {
        return evaluateInternal(record, null);
    }

    /**
     * 带配置的检查（批次 10）：{@code params.charset} 指定字节层期望编码。
     */
    @Override
    public RuleResult evaluate(Map<String, Object> record, RuleConfig config) {
        String expected = config == null ? null : config.stringParam(PARAM_CHARSET);
        if (expected == null || expected.isBlank()) {
            return evaluateInternal(record, null);
        }
        return evaluateInternal(record, expected.trim());
    }

    /**
     * 记录级检查实现。
     *
     * @param record          数据记录
     * @param expectedCharset 期望编码；{@code null} 表示默认（UTF-8，兼容 ASCII）
     * @return 检查结果
     */
    private RuleResult evaluateInternal(Map<String, Object> record, String expectedCharset) {
        if (record == null || record.isEmpty()) {
            return RuleResult.pass();
        }

        for (Map.Entry<String, Object> entry : record.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof byte[] raw) {
                RuleResult result = checkRawBytes(entry.getKey(), raw, expectedCharset);
                if (result != null) {
                    return result;
                }
            } else if (value instanceof String text && !text.isBlank()) {
                RuleResult result = checkDecodedText(entry.getKey(), text);
                if (result != null) {
                    return result;
                }
            }
        }
        return RuleResult.pass();
    }

    /**
     * 字节层检查：原始字节的检测编码不符合期望编码即判失败
     * （任务 9.2.1；批次 10 起期望编码可配置，默认 UTF-8）。
     *
     * @param field           字段名
     * @param raw             原始字节
     * @param expectedCharset 期望编码；{@code null} 表示默认 UTF-8
     * @return 失败结果；通过时返回 null
     */
    private RuleResult checkRawBytes(String field, byte[] raw, String expectedCharset) {
        if (raw.length == 0) {
            return null;
        }
        String detected = charsetDetector.detect(raw);
        if (matchesExpected(detected, expectedCharset)) {
            return null;
        }
        // R43①：reason 为不含记录取值的模板（期望编码属规则配置，可保留）；
        // 字段名与"本条记录检测出的编码"（随记录变化）放入 detail。
        if (expectedCharset == null) {
            log.warn("字段 '{}' 原始字节检测为 {}（非 UTF-8），需按该编码转码后入库", field, detected);
            return RuleResult.fail(
                    "原始字节编码非 UTF-8（应按检测结果转码）",
                    String.format("字段 '%s' 编码为 %s", field, detected));
        }
        log.warn("字段 '{}' 原始字节检测为 {}（期望 {}），需按该编码转码后入库",
                field, detected, expectedCharset);
        return RuleResult.fail(
                String.format("原始字节编码与期望编码 %s 不符（应按检测结果转码）", expectedCharset),
                String.format("字段 '%s' 编码为 %s", field, detected));
    }

    /**
     * 字符串层检查：已解码文本含替换字符 {@code U+FFFD} 说明上游解码失败。
     *
     * @param field 字段名
     * @param text  文本
     * @return 失败结果；通过时返回 null
     */
    private RuleResult checkDecodedText(String field, String text) {
        int damaged = charsetDetector.countReplacementCharacters(text);
        if (damaged == 0) {
            return null;
        }
        log.warn("字段 '{}' 含 {} 个 U+FFFD 替换字符，判定为上游解码失败", field, damaged);
        // R43①：字段名与替换字符个数随记录变化 → 放入 detail
        return RuleResult.fail(
                "含替换字符(U+FFFD)，上游按错误的字符集解码，内容已损坏",
                String.format("字段 '%s' 含 %d 个替换字符(U+FFFD)", field, damaged));
    }

    /**
     * 判断检测到的编码是否满足期望编码。
     * <p>
     * 两处等价处理：
     * </p>
     * <ol>
     *   <li>UTF-8 与 ASCII 在检测层等价（纯 ASCII 字节流两者皆合法），
     *       因此期望 UTF-8 时 ASCII 也视为通过——与改造前的判定一致；</li>
     *   <li>中文（GB）族内部等价（GB2312 ⊂ GBK ⊂ GB18030）——juniversalchardet 对 GBK 内容
     *       通常返回 {@code GB18030}，若按名称严格比较，"期望 GBK"的配置将永远判失败，
     *       使规则管理页的字符集选项形同虚设。族内等价的口径与
     *       {@code CharsetDetectorTest} 的 GB_FAMILY 一致。</li>
     * </ol>
     *
     * @param detected        检测到的编码名
     * @param expectedCharset 期望编码名；{@code null} 表示默认 UTF-8
     * @return true 表示符合期望
     */
    private static boolean matchesExpected(String detected, String expectedCharset) {
        if (detected == null || detected.isBlank()) {
            return true;
        }
        if (expectedCharset == null || expectedCharset.isBlank()) {
            return isUtf8Compatible(detected);
        }
        if (expectedCharset.equalsIgnoreCase(detected)) {
            return true;
        }
        if (isUtf8Compatible(expectedCharset) && isUtf8Compatible(detected)) {
            return true;
        }
        return isGbFamily(expectedCharset) && isGbFamily(detected);
    }

    /**
     * 是否属于中文（GB）编码族。
     *
     * @param charsetName 编码名
     * @return true 表示属于该族
     */
    private static boolean isGbFamily(String charsetName) {
        return charsetName != null
                && GB_FAMILY.contains(charsetName.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * UTF-8 / ASCII 均视为合规编码。
     *
     * @param charsetName 检测到的编码名
     * @return true 表示合规
     */
    private static boolean isUtf8Compatible(String charsetName) {
        if (charsetName == null || charsetName.isBlank()) {
            return true;
        }
        return StandardCharsets.UTF_8.name().equalsIgnoreCase(charsetName)
                || "ASCII".equalsIgnoreCase(charsetName)
                || "US-ASCII".equalsIgnoreCase(charsetName);
    }
}
