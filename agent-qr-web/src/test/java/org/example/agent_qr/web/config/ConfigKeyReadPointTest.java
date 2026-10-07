package org.example.agent_qr.web.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置防漂移检查（批次 10 · 任务 10.5.5，问题 38 的收尾一环）。
 * <p>
 * 目的：防止再次积累"有键无读取方"的死配置（问题 38 的成因）。
 * 检查方式与问题核查一致：<b>扫描 yml 中的全部叶子键 → 全仓库反查读取点</b>。
 * 允许白名单（框架自己消费的键：{@code spring.*} / {@code server.*} / {@code mybatis-plus.*}）。
 * </p>
 * <p>
 * 另含一条前端检查：{@code .env.*} 中的 {@code VITE_*} 键必须在 {@code src/} 下有引用
 * （本批次接线的 {@code VITE_SSE_TIMEOUT} / {@code VITE_TOKEN_REFRESH_AHEAD} /
 * {@code VITE_SSE_MAX_RECONNECT} 正是此类）。
 * </p>
 * <p>
 * 结论：新增配置键时若忘了写读取点，本测试会直接失败并列出键名——
 * 相当于把"死配置"挡在提交之前。
 * </p>
 *
 * @author agent-qr
 */
class ConfigKeyReadPointTest {

    /** 框架自身消费的键前缀（不要求仓库内有读取点） */
    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            "spring.", "server.", "mybatis-plus.", "logging.");

    @Test
    @DisplayName("★ 后端 yml 中的每个叶子键都在仓库源码中有读取点（死后端配置防漂移）")
    void everyYamlKey_shouldHaveReadPoint() throws IOException {
        Path repoRoot = locateRepoRoot();
        Map<String, String> sources = readSources(repoRoot, List.of(".java", ".ts", ".vue", ".sql", ".xml"));
        Map<String, String> yamlKeys = new LinkedHashMap<>();
        for (Path yml : findYamlFiles(repoRoot)) {
            yamlKeys.putAll(parseYamlKeys(yml));
        }
        assertThat(yamlKeys).as("未扫描到任何 yml 键，检查路径逻辑").isNotEmpty();

        List<String> dead = findKeysWithoutReadPoint(yamlKeys.keySet(), sources, FRAMEWORK_PREFIXES);

        assertThat(dead)
                .as("以下 yml 键在全仓库没有读取点（死配置）：新增键必须同时补上 @Value 读取点，"
                        + "或加入 ConfigKeyReadPointTest 的白名单并说明理由")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 前端 .env 中的 VITE_* 键都在 src/ 下有引用（死前端配置防漂移）")
    void everyViteKey_shouldBeReferenced() throws IOException {
        Path repoRoot = locateRepoRoot();
        Path frontend = repoRoot.resolve(Path.of("agent-qr-web-frontend"));
        Map<String, String> sources = readSources(frontend.resolve("src"),
                List.of(".ts", ".vue", ".js"));
        Set<String> keys = new LinkedHashSet<>();
        for (String envFile : List.of(".env.development", ".env.production")) {
            Path path = frontend.resolve(envFile);
            if (!Files.exists(path)) {
                continue;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                keys.add(trimmed.substring(0, trimmed.indexOf('=')).trim());
            }
        }
        assertThat(keys).as("未扫描到任何 VITE_* 键，检查路径逻辑").isNotEmpty();

        List<String> dead = findKeysWithoutReadPoint(keys, sources, List.of());

        assertThat(dead)
                .as("以下 .env 键在 src/ 下没有任何引用（死配置）")
                .isEmpty();
    }

    @Test
    @DisplayName("检查器自测：人为构造的死配置必须被识别出来（用例有效性）")
    void scanner_shouldDetectSyntheticDeadKey() {
        Set<String> keys = Set.of("agent-qr.cache.max-size", "agent-qr.fake.dead-key", "spring.datasource.url");
        Map<String, String> sources = Map.of(
                "Cached.java", "@Value(\"${agent-qr.cache.max-size:10000}\") private long maxSize;",
                "App.java", "public class App {}");

        List<String> dead = findKeysWithoutReadPoint(keys, sources, FRAMEWORK_PREFIXES);

        assertThat(dead)
                .as("构造的死键必须被发现；有读取点的键与框架白名单键不得误报")
                .containsExactly("agent-qr.fake.dead-key");
    }

    // ==================== 扫描实现 ====================

    /**
     * 找出没有读取点的键。
     *
     * @param keys        待检查的键
     * @param sources     源码（文件名 → 内容）
     * @param allowPrefix 白名单前缀
     * @return 无读取点的键（按扫描顺序）
     */
    static List<String> findKeysWithoutReadPoint(Set<String> keys, Map<String, String> sources,
                                                 List<String> allowPrefix) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            if (allowPrefix.stream().anyMatch(key::startsWith)) {
                continue;
            }
            boolean found = sources.values().stream().anyMatch(content -> content.contains(key));
            if (!found) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * 按缩进解析 yml 的叶子键（点分形式）。
     *
     * @param path yml 文件
     * @return 键 → 原始值
     * @throws IOException 读文件失败
     */
    static Map<String, String> parseYamlKeys(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            if (line.isBlank()) {
                continue;
            }
            int indent = line.indexOf(line.trim());
            String text = line.trim();
            int colon = text.indexOf(':');
            if (colon < 0 || text.startsWith("-")) {
                continue;
            }
            String name = text.substring(0, colon).trim();
            String value = text.substring(colon + 1).trim();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                names.remove(names.size() - 1);
            }
            names.add(name);
            indents.add(indent);
            if (!value.isEmpty()) {
                values.put(String.join(".", names), value);
            }
        }
        return values;
    }

    /**
     * 定位仓库根目录（含 pom.xml 且含 agent-qr-web 模块）。
     *
     * @return 仓库根目录
     */
    static Path locateRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve("agent-qr-web"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到仓库根目录（pom.xml + agent-qr-web）");
    }

    /**
     * 收集 application*.yml。
     *
     * @param repoRoot 仓库根
     * @return yml 文件列表
     * @throws IOException 遍历失败
     */
    static List<Path> findYamlFiles(Path repoRoot) throws IOException {
        Path resources = repoRoot.resolve(Path.of("agent-qr-web", "src", "main", "resources"));
        try (Stream<Path> stream = Files.list(resources)) {
            return stream.filter(path -> path.getFileName().toString().startsWith("application")
                            && (path.toString().endsWith(".yml") || path.toString().endsWith(".yaml")))
                    .toList();
        }
    }

    /**
     * 读取源码文件内容（跳过构建产物与依赖目录）。
     *
     * @param root       根目录
     * @param extensions 扩展名（含点）
     * @return 文件名 → 内容
     * @throws IOException 遍历失败
     */
    static Map<String, String> readSources(Path root, List<String> extensions) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        if (!Files.exists(root)) {
            return sources;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String normalized = path.toString().replace('\\', '/');
                if (normalized.contains("/target/") || normalized.contains("/node_modules/")
                        || normalized.contains("/dist/") || normalized.contains("/.git/")
                        || normalized.contains("/.codegraph/")) {
                    continue;
                }
                if (extensions.stream().noneMatch(normalized::endsWith)) {
                    continue;
                }
                try {
                    sources.put(normalized, Files.readString(path, StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 二进制/编码异常文件跳过
                }
            }
        }
        return sources;
    }
}
