package org.example.agent_qr.dataquality.rule;

import org.example.agent_qr.dataquality.entity.RuleResult;
import org.example.agent_qr.dataquality.util.CharsetDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编码检查规则测试（批次 09 · 任务 9.2，问题 25）。
 * <p>
 * <b>拦截的缺陷</b>：旧实现把已解码的 String 交给
 * {@code charsetDetector.detect(text)}——该方法在 UTF-8 的 JVM 上恒返回 UTF-8，
 * 于是 <b>GBK 乱码记录也能"通过"</b>，质检侧对编码问题完全不设防。
 * </p>
 * <p>
 * 修复后规则按取值形态分流：{@code byte[]}（未解码的原始字节）走字节层检测；
 * {@code String}（已解码）检查替换字符 {@code U+FFFD}——上游用错编码解码的不可逆痕迹。
 * </p>
 *
 * @author agent-qr
 */
class EncodingRuleTest {

    private static final String CHINESE_TEXT =
            "本数据源用于员工信息管理，包含姓名、部门、岗位与入职时间等字段。";

    private EncodingRule rule;

    @BeforeEach
    void setUp() {
        rule = new EncodingRule();
        ReflectionTestUtils.setField(rule, "charsetDetector", new CharsetDetector());
    }

    @Test
    @DisplayName("★ 含 U+FFFD 的记录判失败（修复前恒通过）")
    void evaluate_shouldFail_whenTextContainsReplacementCharacter() {
        Map<String, Object> record = one("content", "员工姓名：张三，部门：�研发部�");

        RuleResult result = rule.evaluate(record);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getReason())
                .contains("content")
                .contains("U+FFFD");
    }

    @Test
    @DisplayName("正常中文 UTF-8 文本通过（不得误报）")
    void evaluate_shouldPass_forCleanUtf8Text() {
        RuleResult result = rule.evaluate(one("content", CHINESE_TEXT));

        assertThat(result.isPassed()).isTrue();
    }

    @Test
    @DisplayName("纯 ASCII 文本通过（库不检出编码时不得误判）")
    void evaluate_shouldPass_forAsciiText() {
        assertThat(rule.evaluate(one("name", "Zhang San")).isPassed()).isTrue();
    }

    @Test
    @DisplayName("★ byte[] 取值走字节层检测：GBK 原始字节判失败（任务 9.2.1 的字节层入口）")
    void evaluate_shouldFail_forRawGbkBytes() {
        Map<String, Object> record = one("raw", CHINESE_TEXT.getBytes(Charset.forName("GBK")));

        RuleResult result = rule.evaluate(record);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getReason()).contains("raw").contains("非 UTF-8");
    }

    @Test
    @DisplayName("byte[] 取值为 UTF-8 字节时通过")
    void evaluate_shouldPass_forRawUtf8Bytes() {
        Map<String, Object> record = one("raw", CHINESE_TEXT.getBytes(StandardCharsets.UTF_8));

        assertThat(rule.evaluate(record).isPassed()).isTrue();
    }

    @Test
    @DisplayName("空记录 / 空值不抛异常且判通过（回归）")
    void evaluate_shouldPass_forEmptyRecord() {
        assertThat(rule.evaluate(null).isPassed()).isTrue();
        assertThat(rule.evaluate(Map.of()).isPassed()).isTrue();
        assertThat(rule.evaluate(one("blank", "   ")).isPassed()).isTrue();
    }

    @Test
    @DisplayName("规则名保持「编码」（质检报告中的展示名不变）")
    void getName_shouldBeStable() {
        assertThat(rule.getName()).isEqualTo("编码");
    }

    private static Map<String, Object> one(String key, Object value) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(key, value);
        return record;
    }
}
