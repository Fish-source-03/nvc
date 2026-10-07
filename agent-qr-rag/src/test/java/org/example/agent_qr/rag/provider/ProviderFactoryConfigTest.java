package org.example.agent_qr.rag.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProviderFactory} 的配置生效测试（批次 07 · 任务 7.1 / 7.2.8）。
 * <p>
 * 拦截的两个问题：
 * <ol>
 *   <li>{@code getEmbeddingModelName()} 原实现返回 <b>Provider 类型</b>而非模型名，
 *       使 Collection 隔离命名退化为 {@code kb_ollama_ollama}——"切换模型自动隔离"名存实亡；</li>
 *   <li>配置冗余：{@code embedding.provider}（生效）与
 *       {@code agent-qr.provider.preferred-embedding}（不参与决策）表达同一件事，
 *       后者属"改了没反应"的误导键，按决策删除。</li>
 * </ol>
 *
 * @author agent-qr
 */
class ProviderFactoryConfigTest {

    @Test
    @DisplayName("★ ollama.embedding.model 配置生效：修改后模型名随之变化（不再返回 Provider 类型）")
    void getEmbeddingModelName_shouldFollowConfiguredModel() {
        ProviderFactory factory = new ProviderFactory();
        ReflectionTestUtils.setField(factory, "embeddingProviderType", "ollama");
        ReflectionTestUtils.setField(factory, "ollamaEmbeddingModel", "qwen3-embedding:4b");

        assertThat(factory.getEmbeddingModelName())
                .as("返回 'ollama'（Provider 类型）说明模型名配置未接通")
                .isEqualTo("qwen3-embedding:4b");

        ReflectionTestUtils.setField(factory, "ollamaEmbeddingModel", "nomic-embed-text");

        assertThat(factory.getEmbeddingModelName()).isEqualTo("nomic-embed-text");
    }

    @Test
    @DisplayName("非 ollama Provider 时回退为类型标识，命名规则仍可用")
    void getEmbeddingModelName_shouldFallBackToProviderType() {
        ProviderFactory factory = new ProviderFactory();
        ReflectionTestUtils.setField(factory, "embeddingProviderType", "deepseek");
        ReflectionTestUtils.setField(factory, "ollamaEmbeddingModel", "qwen3-embedding:4b");

        assertThat(factory.getEmbeddingModelName()).isEqualTo("deepseek");
    }

    @Test
    @DisplayName("★ 配置冗余已消除：preferred-embedding 不再出现在 application-p3.yml")
    void applicationP3Yml_shouldNotDeclarePreferredEmbedding() throws Exception {
        String yml = readYml("application-p3.yml");

        assertThat(declares(yml, "preferred-embedding"))
                .as("该键不参与 decideEmbeddingProvider()，留着就是'改了没反应'的误导配置")
                .isFalse();
    }

    @Test
    @DisplayName("生效的 embedding.provider 键仍保留在 application-p1.yml")
    void applicationP1Yml_shouldStillDeclareEmbeddingProvider() throws Exception {
        String yml = readYml("application-p1.yml");

        assertThat(declares(yml, "provider")).isTrue();
        assertThat(yml).contains("provider: ollama");
    }

    private static boolean declares(String yml, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*\\S+").matcher(yml);
        return matcher.find();
    }

    private static String readYml(String fileName) throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", fileName));
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 " + fileName);
    }
}
