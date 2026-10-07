package org.example.agent_qr.dataquality.rule;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.dataquality.entity.RuleResult;
import org.example.agent_qr.dataquality.util.CharsetDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 编码检查规则 —— 与 {@link CharsetDetector} 互补的"记录级"判定。
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
 * ⚠️ <b>已知边界</b>：若上游用某个"能解码全部字节"的编码（如 ISO-8859-1）读错了文件，
 * 结果是可逆的乱码而不含 {@code U+FFFD}，本规则在 String 层<b>无法</b>识别——
 * 根治手段是在读取处（连接器）接入字节层检测与转码，该改动不在本批次文件范围内。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class EncodingRule implements QualityRule {

    @Autowired
    private CharsetDetector charsetDetector;

    @Override
    public String getName() {
        return "编码";
    }

    @Override
    public RuleResult evaluate(Map<String, Object> record) {
        if (record == null || record.isEmpty()) {
            return RuleResult.pass();
        }

        for (Map.Entry<String, Object> entry : record.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof byte[] raw) {
                RuleResult result = checkRawBytes(entry.getKey(), raw);
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
     * 字节层检查：非 UTF-8 编码的原始字节判失败（任务 9.2.1）。
     *
     * @param field 字段名
     * @param raw   原始字节
     * @return 失败结果；通过时返回 null
     */
    private RuleResult checkRawBytes(String field, byte[] raw) {
        if (raw.length == 0) {
            return null;
        }
        String detected = charsetDetector.detect(raw);
        if (isUtf8Compatible(detected)) {
            return null;
        }
        log.warn("字段 '{}' 原始字节检测为 {}（非 UTF-8），需按该编码转码后入库", field, detected);
        return RuleResult.fail(String.format(
                "字段 '%s' 编码为 %s，非 UTF-8（应按检测结果转码）", field, detected));
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
        return RuleResult.fail(String.format(
                "字段 '%s' 含 %d 个替换字符(U+FFFD)，上游按错误的字符集解码，内容已损坏", field, damaged));
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
