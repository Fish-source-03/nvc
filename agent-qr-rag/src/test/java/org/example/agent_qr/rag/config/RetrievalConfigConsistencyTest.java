package org.example.agent_qr.rag.config;

import org.example.agent_qr.rag.retriever.HybridRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索配置一致性测试（批次 04 · 任务 4.3.3 / 4.4.1，问题 12 / 13）。
 * <p>
 * 拦截的核心缺陷：
 * <ol>
 *   <li>{@code final-top-k} 在 yml（15）与代码默认值（5）两处不一致——配置一旦缺失就静默回落到更小的值；</li>
 *   <li>灰度开关若被改成默认开启，会绕过"先验证再灰度"的流程（本测试固化"默认关闭"）。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
class RetrievalConfigConsistencyTest {

    private static final Path P2_YML = Path.of("agent-qr-web", "src", "main", "resources", "application-p2.yml");

    /** 从模块目录（mvn 运行目录）向上寻找 application-p2.yml，兼容从仓库根目录运行。 */
    private String p2YmlContent() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(P2_YML);
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 application-p2.yml（当前目录: " + Path.of("").toAbsolutePath() + "）");
    }

    private String codeDefaultOf(String fieldName) throws NoSuchFieldException {
        Field field = HybridRetriever.class.getDeclaredField(fieldName);
        Value value = field.getAnnotation(Value.class);
        assertThat(value).as("%s 应由 @Value 注入", fieldName).isNotNull();
        return value.value();
    }

    @Test
    @DisplayName("★ final-top-k：yml（30）与代码默认值必须一致（任务 4.4.1）")
    void finalTopK_shouldBeConsistentBetweenYmlAndCodeDefault() throws Exception {
        String yml = p2YmlContent();
        Matcher matcher = Pattern.compile("final-top-k:\\s*(\\d+)").matcher(yml);
        assertThat(matcher.find()).as("application-p2.yml 必须显式配置 final-top-k").isTrue();
        assertThat(matcher.group(1))
                .as("问题 13：15 → 30（列举类查询的主瓶颈）")
                .isEqualTo("30");

        assertThat(codeDefaultOf("finalTopK"))
                .as("代码默认值必须与 yml 统一，否则配置缺失时静默回落")
                .isEqualTo("${agent-qr.retrieval.final-top-k:30}");
    }

    @Test
    @DisplayName("★ LLM 结构化过滤灰度开关默认关闭（任务 4.3.3）")
    void llmExtractSwitch_shouldBeDisabledByDefault() throws Exception {
        String yml = p2YmlContent();

        assertThat(yml).containsPattern(Pattern.compile("llm-extract:\\s*\\n\\s*enabled:\\s*false"));
        Field enabledField = org.example.agent_qr.rag.filter.FilterConditionExtractor.class
                .getDeclaredField("enabled");
        assertThat(enabledField.getAnnotation(Value.class).value())
                .as("代码侧默认值也必须是 false（未开启时行为与修复前完全一致）")
                .isEqualTo("${agent-qr.filter.llm-extract.enabled:false}");
    }

    @Test
    @DisplayName("聚合路径配置：安全上限 2000 + 意图分类 LLM 兜底默认关闭（任务 4.4）")
    void aggregationConfig_shouldMatchDecision() throws Exception {
        String yml = p2YmlContent();

        assertThat(yml).containsPattern(Pattern.compile("aggregation:\\s*\\n\\s*max-chunk-ids:\\s*2000"));
        assertThat(yml).containsPattern(Pattern.compile("llm-classify:\\s*\\n\\s*enabled:\\s*false"));

        Field limitField = org.example.agent_qr.rag.filter.StructuredFilterService.class
                .getDeclaredField("unboundedLimit");
        assertThat(limitField.getAnnotation(Value.class).value())
                .isEqualTo("${agent-qr.aggregation.max-chunk-ids:2000}");
    }
}
