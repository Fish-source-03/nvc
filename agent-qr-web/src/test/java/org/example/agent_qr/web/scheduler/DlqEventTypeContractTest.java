package org.example.agent_qr.web.scheduler;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ 事件类型契约检查（批次 11 · 任务 11.2.6，问题 02 的契约测试固化）。
 * <p>
 * 批次 01 建立了 {@code DlqMessage.EVENT_*} 常量，把"入队类型"与"重试分支"从
 * 散落的字符串字面量收敛为一个契约。本用例把该契约固化为可执行检查：
 * </p>
 * <ul>
 *   <li><b>入队点不得使用字符串字面量</b>——只能传 {@code DlqMessage.EVENT_*} 常量
 *       （问题 02 的根因正是字面量拼错/漂移）；</li>
 *   <li><b>常量 ⇄ 重试分支一一对应</b>——新增事件类型若忘了在
 *       {@link DlqRetryScheduler} 的 switch 中加分支，会落进
 *       {@code handleUnknownEventType}：记录永久保留、每轮扫描刷 ERROR 且无任何重试动作；</li>
 *   <li><b>没有"只注册不入队"的死类型</b>——每个常量至少有一个真实入队点；</li>
 *   <li><b>字面量值互不相同</b>——避免两个常量取同值导致重试走错分支。</li>
 * </ul>
 * <p>
 * 与 {@code DlqRetrySchedulerTest}（行为覆盖："未知类型不删除"等）互补：
 * 那条用例验证运行时行为，本用例验证"新增类型时不会漏接线"。
 * </p>
 *
 * @author agent-qr
 */
class DlqEventTypeContractTest {

    /** {@code public static final String EVENT_X = "X";} */
    private static final Pattern EVENT_CONSTANT_DECL =
            Pattern.compile("public\\s+static\\s+final\\s+String\\s+(EVENT_[A-Z0-9_]+)\\s*=\\s*\"([^\"]*)\"");

    /** 入队调用（捕获实参列表的开头，用于判定第一个实参形态） */
    private static final Pattern ENQUEUE_CALL =
            Pattern.compile("\\.enqueue\\s*\\(\\s*([^,)\\s]*)\\s*,");

    /** 重试分支 {@code case DlqMessage.EVENT_X ->} */
    private static final Pattern RETRY_CASE =
            Pattern.compile("case\\s+DlqMessage\\.(EVENT_[A-Z0-9_]+)");

    // ==================== 契约用例 ====================

    @Test
    @DisplayName("★ 所有 DLQ 入队点都传 DlqMessage.EVENT_* 常量（无字符串字面量）")
    void enqueueCallSites_shouldPassEventTypeConstants() throws IOException {
        Map<String, String> sources = readMainSources();

        List<String> literalSites = new ArrayList<>();
        int callSites = 0;
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            for (String firstArg : matchAll(ENQUEUE_CALL, entry.getValue())) {
                callSites++;
                if (firstArg.startsWith("\"") || !firstArg.startsWith("DlqMessage.EVENT_")) {
                    literalSites.add(entry.getKey() + " → enqueue(" + firstArg + ", ...)");
                }
            }
        }

        assertThat(callSites)
                .as("未扫描到任何 enqueue 调用点，检查扫描逻辑（路径/正则）")
                .isGreaterThanOrEqualTo(6);
        assertThat(literalSites)
                .as("以下入队点未使用 DlqMessage.EVENT_* 常量（事件类型契约漂移）：%s", literalSites)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 每个事件类型常量都有重试分支，且都有真实入队点")
    void everyDeclaredEventType_shouldHaveRetryBranchAndEnqueueSite() throws IOException {
        Map<String, String> sources = readMainSources();

        String dlqMessage = sources.entrySet().stream()
                .filter(e -> e.getKey().endsWith("DlqMessage.java"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 DlqMessage.java"));
        String scheduler = sources.entrySet().stream()
                .filter(e -> e.getKey().endsWith("DlqRetryScheduler.java"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 DlqRetryScheduler.java"));

        Map<String, String> declared = new LinkedHashMap<>();
        Matcher decl = EVENT_CONSTANT_DECL.matcher(dlqMessage);
        while (decl.find()) {
            declared.put(decl.group(1), decl.group(2));
        }
        assertThat(declared).as("未解析到任何 EVENT_* 常量，检查扫描逻辑").hasSizeGreaterThanOrEqualTo(6);

        Set<String> handled = new LinkedHashSet<>(matchAll(RETRY_CASE, scheduler));
        Set<String> enqueued = new LinkedHashSet<>();
        for (String content : sources.values()) {
            for (String firstArg : matchAll(ENQUEUE_CALL, content)) {
                if (firstArg.startsWith("DlqMessage.EVENT_")) {
                    enqueued.add(firstArg.substring("DlqMessage.".length()));
                }
            }
        }

        // 1) 每个常量都必须有重试分支（缺分支 → 落进 handleUnknownEventType，永不重试）
        assertThat(handled)
                .as("DlqRetryScheduler 缺少以下事件类型的重试分支（会落进 handleUnknownEventType 分支）")
                .containsExactlyInAnyOrderElementsOf(declared.keySet());
        // 2) 每个常量都必须至少被一个入队点使用（否则是死类型）
        assertThat(enqueued)
                .as("以下事件类型常量没有任何入队点（死类型）")
                .containsExactlyInAnyOrderElementsOf(declared.keySet());
        // 3) 字面量值必须互不相同（同值会让重试走错分支）
        assertThat(declared.values())
                .as("事件类型字面量重复")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("检查器自测：人为构造的违规必须被识别出来（用例有效性）")
    void scanner_shouldDetectSyntheticViolations() {
        String synthetic = "public static final String EVENT_FAKE = \"FAKE\";\n"
                + "    void x() { dlq.enqueue(\"FAKE\", 1L, \"{}\", e); }";

        assertThat(matchAll(EVENT_CONSTANT_DECL, synthetic)).containsExactly("EVENT_FAKE");
        List<String> args = matchAll(ENQUEUE_CALL, synthetic);
        assertThat(args).containsExactly("\"FAKE\"");
        assertThat(args.get(0).startsWith("DlqMessage.EVENT_"))
                .as("字面量入队点必须被判定为违规")
                .isFalse();
    }

    // ==================== 扫描实现 ====================

    /**
     * 读取全部模块 {@code src/main/java} 下的源码（文件名 → 内容），并剥离注释。
     *
     * @return 源码映射
     * @throws IOException 遍历失败
     */
    static Map<String, String> readMainSources() throws IOException {
        Path repoRoot = locateRepoRoot();
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> modules = Files.list(repoRoot)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path mainJava = module.resolve(Path.of("src", "main", "java"));
                if (!Files.isDirectory(mainJava)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(mainJava)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        sources.put(repoRoot.relativize(file).toString(),
                                stripComments(Files.readString(file, StandardCharsets.UTF_8)));
                    }
                }
            }
        }
        return sources;
    }

    /**
     * 剥离块注释与行注释，避免 javadoc 中的示例（如 {@code enqueue("DELETE", ...)}）
     * 被误判为真实调用点。
     *
     * @param source 源码
     * @return 去注释后的源码（保留换行以维持行结构）
     */
    static String stripComments(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int k = i; k < end; k++) {
                    if (source.charAt(k) == '\n') {
                        sb.append('\n');
                    }
                }
                i = end;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (c == '"') {
                int end = skipStringLiteral(source, i);
                sb.append(source, i, end);
                i = end;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 跳过一个字符串字面量（含转义），返回结束引号之后的下标。
     *
     * @param source 源码
     * @param start  起始引号下标
     * @return 字面量结束后的下标
     */
    static int skipStringLiteral(String source, int start) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return source.length();
    }

    /**
     * 返回正则全部匹配的第 1 捕获组。
     *
     * @param pattern 正则
     * @param content 文本
     * @return 捕获组列表
     */
    static List<String> matchAll(Pattern pattern, String content) {
        List<String> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
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
}
