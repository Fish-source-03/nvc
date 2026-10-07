package org.example.agent_qr.statistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块依赖显式化测试（批次 09 · 任务 9.5.1，问题 40）。
 * <p>
 * <b>拦截的缺陷</b>：{@code StatisticsQueryService} / {@code FeedbackService} 直接
 * {@code import org.example.agent_qr.rag.*}，但本模块的 pom <b>未声明</b> {@code agent-qr-rag}——
 * 依赖是经 {@code knowledge → rag} 的<b>传递依赖</b>隐式获得的。
 * 传递依赖会随上游模块的依赖调整而无声消失，届时编译失败点与本模块毫无关联，极难定位。
 * </p>
 * <p>
 * 本测试以"fitness function"的形式固化：pom 必须显式声明被直接 import 的模块。
 * 同时做反向检查（源码确实还在用 rag），避免把声明留成死条目。
 * </p>
 *
 * @author agent-qr
 */
class ModuleDependencyDeclarationTest {

    @Test
    @DisplayName("★ statistics 显式声明 agent-qr-rag（不再依赖 knowledge 的传递依赖）")
    void pom_shouldDeclareRagDependencyExplicitly() throws Exception {
        String pom = readModuleFile("pom.xml");

        assertThat(pom)
                .as("StatisticsQueryService/FeedbackService 直接 import org.example.agent_qr.rag.*，"
                        + "依赖必须在 pom 中显式声明")
                .contains("<artifactId>agent-qr-rag</artifactId>");
    }

    @Test
    @DisplayName("★ statistics 显式声明 agent-qr-auth（UserPrincipal / AbacEvaluator 的直接 import）")
    void pom_shouldDeclareAuthDependencyExplicitly() throws Exception {
        String pom = readModuleFile("pom.xml");

        assertThat(pom)
                .as("dependency:analyze 报出的 org.example 未声明项：agent-qr-auth")
                .contains("<artifactId>agent-qr-auth</artifactId>");
    }

    @Test
    @DisplayName("★ 反向检查：源码确实直接引用 rag（该声明不是死条目）")
    void sources_shouldStillImportRag() throws Exception {
        Path serviceDir = Path.of("src", "main", "java", "org", "example", "agent_qr", "statistics", "service");
        assertThat(serviceDir).as("模块目录结构变化时请同步更新本测试").exists();

        try (Stream<Path> files = Files.walk(serviceDir)) {
            List<Path> importing = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("import org.example.agent_qr.rag."))
                    .toList();
            assertThat(importing)
                    .as("若不再直接 import rag，应同时删除 pom 中的显式声明")
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("回归：agent-qr-rag 是 <dependencies> 内的真实声明，且不出现自依赖（环）")
    void ragDependency_shouldBeRealElementWithoutSelfDependency() throws Exception {
        String dependencies = dependenciesBlock(readModuleFile("pom.xml"));

        assertThat(dependencies)
                .as("声明必须落在 <dependencies> 块内（注释里提到不算）")
                .contains("<artifactId>agent-qr-rag</artifactId>");
        assertThat(dependencies)
                .as("模块自依赖会构成循环，Maven 会直接构建失败")
                .doesNotContain("<artifactId>agent-qr-statistics</artifactId>");
    }

    // ==================== 辅助 ====================

    /** 读取模块内文件（Maven surefire 的工作目录即模块根目录） */
    private static String readModuleFile(String relative) throws Exception {
        File file = new File(relative);
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    /** 截取 pom 的 {@code <dependencies>} 块（<parent> 之后的第一处） */
    private static String dependenciesBlock(String pom) {
        int start = pom.indexOf("<dependencies>", pom.indexOf("</parent>"));
        int end = pom.indexOf("</dependencies>", start);
        assertThat(start).as("module pom 应声明 <dependencies> 块").isGreaterThan(0);
        return pom.substring(start, end);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }
}
