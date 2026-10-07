package org.example.agent_qr.web.config;

import org.example.agent_qr.catalog.service.KnowledgeCatalogService;
import org.example.agent_qr.common.event.DataETLedEvent;
import org.example.agent_qr.common.event.DataQualityPassedEvent;
import org.example.agent_qr.common.event.DataSyncCompletedEvent;
import org.example.agent_qr.dataquality.listener.DataSyncListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AsyncConfigV2} 的六池隔离护栏测试（批次 11 · R49）。
 * <p>
 * <b>拦截的缺陷</b>：{@code AsyncConfigV2 implements AsyncConfigurer} 但未覆写
 * {@code getAsyncExecutor()}，于是<b>未显式指定池名的 {@code @Async}</b> 会落到
 * Spring 默认执行器——不享受六池隔离，也丢失 MDC（TraceId）传递，
 * 隔离设计存在一个洞（实测有 3 处裸 {@code @Async}：质检监听 + catalog 两个 ETL 监听）。
 * </p>
 * <p>
 * 处置选择"逐个 {@code @Async} 指定池名"（理由见 {@link AsyncConfigV2} 类注释），
 * 本测试固化两件事：
 * </p>
 * <ol>
 *   <li>那 3 处监听的池归属（防回退为裸注解）；</li>
 *   <li><b>源码扫描护栏</b>：main 源码里不允许出现裸 {@code @Async}，
 *       也不允许出现六池之外的池名——新代码无法无声地重新开洞。</li>
 * </ol>
 *
 * @author agent-qr
 */
class AsyncConfigV2Test {

    /** 六池（设计 §7.4）：裸 @Async 或池名漂移都会被本测试拦下 */
    private static final Set<String> SIX_POOLS = Set.of(
            "parseExecutor", "chunkExecutor", "embedExecutor",
            "deleteExecutor", "indexBuilderExecutor", "statExecutor");

    @Test
    @DisplayName("★ R49：质检监听显式指定 statExecutor（修复前是裸 @Async → 落默认执行器，无隔离/无 MDC）")
    void dataSyncListener_shouldDeclareStatExecutor() throws NoSuchMethodException {
        Method method = DataSyncListener.class.getMethod(
                "onDataSyncCompleted", DataSyncCompletedEvent.class);

        Async async = method.getAnnotation(Async.class);
        assertThat(async).as("监听方法必须带 @Async").isNotNull();
        assertThat(async.value()).isEqualTo("statExecutor");
    }

    @Test
    @DisplayName("★ R49：catalog 的两个 ETL 监听显式指定 indexBuilderExecutor（目录索引构建语义）")
    void catalogListeners_shouldDeclareIndexBuilderExecutor() throws NoSuchMethodException {
        Method passed = KnowledgeCatalogService.class.getMethod(
                "onDataQualityPassed", DataQualityPassedEvent.class);
        Method etled = KnowledgeCatalogService.class.getMethod(
                "onDataETLed", DataETLedEvent.class);

        assertThat(passed.getAnnotation(Async.class).value()).isEqualTo("indexBuilderExecutor");
        assertThat(etled.getAnnotation(Async.class).value()).isEqualTo("indexBuilderExecutor");
    }

    @Test
    @DisplayName("★ R49 防回归：main 源码中不得出现裸 @Async（或六池之外的池名）")
    void mainSources_shouldNotContainBareAsyncAnnotation() throws IOException {
        Path repoRoot = repoRoot();
        List<Path> javaFiles = new ArrayList<>();

        // 只扫各模块的 src/main/java（不进入 target / node_modules 等）
        try (Stream<Path> modules = Files.list(repoRoot)) {
            List<Path> sourceRoots = modules
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("agent-qr-"))
                    .map(path -> path.resolve("src").resolve("main").resolve("java"))
                    .filter(Files::isDirectory)
                    .toList();

            assertThat(sourceRoots)
                    .as("必须扫到各模块的 src/main/java（工作目录假设失效时不能静默通过）")
                    .isNotEmpty();

            for (Path sourceRoot : sourceRoots) {
                try (Stream<Path> files = Files.walk(sourceRoot)) {
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .forEach(javaFiles::add);
                }
            }
        }

        List<String> offences = new ArrayList<>();
        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = ASYNC_ANNOTATION.matcher(lines.get(i));
                if (!matcher.find()) {
                    continue;
                }
                String pool = matcher.group(1);
                if (pool == null || pool.isBlank()) {
                    offences.add(file + ":" + (i + 1) + " 裸 @Async（会落默认执行器，脱离六池隔离）");
                } else if (!SIX_POOLS.contains(pool)) {
                    offences.add(file + ":" + (i + 1) + " 未知线程池 '" + pool + "'（应为六池之一）");
                }
            }
        }

        assertThat(javaFiles).as("源码扫描必须真的扫到文件").hasSizeGreaterThan(100);
        assertThat(offences)
                .as("六池隔离要求每个 @Async 都显式指定池名（设计 §7.4）")
                .isEmpty();
    }

    /** 行首的 {@code @Async} 注解（javadoc 里的 {@code @Async} 以 " * " 开头，不会被匹配） */
    private static final Pattern ASYNC_ANNOTATION =
            Pattern.compile("^\\s*@Async(?:\\(\\s*\"([^\"]*)\"\\s*\\))?");

    private static Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (Files.isDirectory(current.resolve("agent-qr-web"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到仓库根目录（应包含 agent-qr-web）");
    }
}
