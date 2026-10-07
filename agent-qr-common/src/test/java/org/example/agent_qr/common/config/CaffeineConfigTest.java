package org.example.agent_qr.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caffeine 缓存配置接线测试（批次 10 · 任务 10.5.1，问题 38）。
 * <p>
 * 修复前 {@code agent-qr.cache.max-size} / {@code ttl-hours} 在 yml 中已声明但<b>无读取点</b>，
 * {@code CaffeineConfig} 硬编码 10000 / 1h —— 改配置不会有任何效果且没有报错。
 * 本测试固化"改配置 → 缓存参数随之变化"。
 * </p>
 *
 * @author agent-qr
 */
class CaffeineConfigTest {

    @Test
    @DisplayName("★ 配置 agent-qr.cache.max-size / ttl-hours 后，Caffeine 的 maximumSize 与过期时间随之变化")
    void llmResponseCache_shouldHonorConfiguredMaxSizeAndTtl() {
        CaffeineConfig config = new CaffeineConfig();
        ReflectionTestUtils.setField(config, "maxSize", 123L);
        ReflectionTestUtils.setField(config, "ttlHours", 7L);

        Cache<String, String> cache = config.llmResponseCache();

        assertThat(cache.policy().eviction().orElseThrow().getMaximum())
                .as("maximumSize 必须来自 agent-qr.cache.max-size")
                .isEqualTo(123L);
        assertThat(cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter(TimeUnit.HOURS))
                .as("过期时间必须来自 agent-qr.cache.ttl-hours")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("未配置时回退默认值：10000 条 / 1 小时（与接线前的硬编码值一致，行为不回退）")
    void llmResponseCache_shouldFallBackToDefaults() {
        CaffeineConfig config = new CaffeineConfig();

        Cache<String, String> cache = config.llmResponseCache();

        assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(10000L);
        assertThat(cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter(TimeUnit.HOURS)).isEqualTo(1L);
    }

    @Test
    @DisplayName("yml 必须声明 agent-qr.cache.*（配置键与读取点同时存在，防止再次脱节）")
    void yml_shouldDeclareCacheKeys() throws Exception {
        Path yml = locateApplicationP2Yml();
        assertThat(yml).as("未找到 application-p2.yml").isNotNull();
        String content = Files.readString(yml, StandardCharsets.UTF_8);

        assertThat(matches(content, "max-size:")).as("yml 缺少 max-size").isTrue();
        assertThat(matches(content, "ttl-hours:")).as("yml 缺少 ttl-hours").isTrue();
    }

    private static boolean matches(String content, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)).matcher(content);
        return matcher.find();
    }

    private static Path locateApplicationP2Yml() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", "application-p2.yml"));
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
