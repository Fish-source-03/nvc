package org.example.agent_qr.web.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link CqrsDataSourceConfig} 测试（批次 02 · 任务 2.1，问题 04 / 05）。
 * <p>
 * 拦截的核心缺陷：
 * <ol>
 *   <li>{@code application-p3.yml} 中读写库地址硬编码 {@code localhost}，
 *       容器内被 {@code SPRING_DATASOURCE_*_URL} 环境变量注入也覆盖不了，
 *       且 {@code docker-compose.yml} 注入的是失效键 {@code SPRING_DATASOURCE_URL}；</li>
 *   <li>{@code read-replica-fallback-to-primary} 只被打印进日志，不参与任何装配决策。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CqrsDataSourceConfigTest {

    /** 生产路径上读库配置的绑定前缀（与 {@code @ConfigurationProperties} 一致） */
    private static final String WRITE_PREFIX = "spring.datasource.write";

    private static final String READ_PREFIX = "spring.datasource.read";

    private static final String CONTAINER_WRITE_URL =
            "jdbc:mysql://mysql:3306/agent_qr?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai";

    private static final String CONTAINER_READ_URL =
            "jdbc:mysql://mysql-replica:3306/agent_qr?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai";

    // ==================== 2.1.1 配置键外部化 ====================

    @Test
    @DisplayName("写库地址应可被 SPRING_DATASOURCE_WRITE_URL 覆盖（修复前硬编码 localhost，容器内必然连库失败）")
    void writeJdbcUrl_shouldBeOverridableByEnvironmentVariable() throws IOException {
        ConfigurableEnvironment environment =
                p3Environment(Map.of("SPRING_DATASOURCE_WRITE_URL", CONTAINER_WRITE_URL));

        HikariDataSource bound = bindDatasource(environment, WRITE_PREFIX);

        assertThat(bound.getJdbcUrl()).isEqualTo(CONTAINER_WRITE_URL);
    }

    @Test
    @DisplayName("读库地址应可被 SPRING_DATASOURCE_READ_URL 覆盖")
    void readJdbcUrl_shouldBeOverridableByEnvironmentVariable() throws IOException {
        ConfigurableEnvironment environment =
                p3Environment(Map.of("SPRING_DATASOURCE_READ_URL", CONTAINER_READ_URL));

        HikariDataSource bound = bindDatasource(environment, READ_PREFIX);

        assertThat(bound.getJdbcUrl()).isEqualTo(CONTAINER_READ_URL);
    }

    @Test
    @DisplayName("未注入环境变量时，读写库地址应保持原 localhost 默认值（不改变本地开发行为）")
    void jdbcUrls_shouldKeepLocalhostDefaults_whenEnvironmentVariablesAbsent() throws IOException {
        ConfigurableEnvironment environment = p3Environment(Map.of());

        assertThat(bindDatasource(environment, WRITE_PREFIX).getJdbcUrl())
                .startsWith("jdbc:mysql://localhost:3308/");
        assertThat(bindDatasource(environment, READ_PREFIX).getJdbcUrl())
                .startsWith("jdbc:mysql://localhost:3309/");
    }

    // ==================== R10 连接池参数绑定（批次 11） ====================

    @Test
    @DisplayName("★ R10：写库连接池参数必须与 jdbc-url 同级绑定生效（修复前 hikari: 嵌套层被静默忽略，声明 20 实为 10）")
    void writeDataSourcePoolSize_shouldBindAtSameLevelAsJdbcUrl() throws IOException {
        ConfigurableEnvironment environment = p3Environment(Map.of());

        HikariDataSource bound = bindDatasource(environment, WRITE_PREFIX);

        assertThat(bound.getMaximumPoolSize())
                .as("hikari: 嵌套层是死配置（HikariDataSource 无嵌套 hikari 属性）——"
                        + "绑定未生效时回落 Hikari 默认值 10，声明值与实际生效值不符")
                .isEqualTo(20);
        assertThat(bound.getMinimumIdle()).isEqualTo(5);
        assertThat(bound.getConnectionTimeout()).isEqualTo(30_000L);
        assertThat(bound.getMaxLifetime()).isEqualTo(1_800_000L);
    }

    @Test
    @DisplayName("★ R10：读库连接池参数同样与 jdbc-url 同级绑定生效（minimum-idle 2 是判别点，Hikari 默认 -1）")
    void readDataSourcePoolSize_shouldBindAtSameLevelAsJdbcUrl() throws IOException {
        ConfigurableEnvironment environment = p3Environment(Map.of());

        HikariDataSource bound = bindDatasource(environment, READ_PREFIX);

        assertThat(bound.getMaximumPoolSize()).isEqualTo(10);
        assertThat(bound.getMinimumIdle())
                .as("maxPoolSize 默认值恰为 10，不能作为判别依据；minimum-idle 默认 -1，2 只能来自配置")
                .isEqualTo(2);
        assertThat(bound.getConnectionTimeout()).isEqualTo(30_000L);
        assertThat(bound.getMaxLifetime()).isEqualTo(1_800_000L);
    }

    @Test
    @DisplayName("★ R10：配置文件中不得再出现 hikari: 嵌套层（防止死配置回归）")
    void p3Yaml_shouldNotDeclareNestedHikariBlock() throws IOException {
        String p3Content = Files.readString(p3YamlPath());

        assertThat(p3Content)
                .as("嵌套 hikari 层会被 HikariDataSource 静默忽略；连接池键必须与 jdbc-url 同级")
                .doesNotContain("hikari:");
    }

    // ==================== 2.1.2 docker-compose 环境变量与配置键对齐 ====================

    @Test
    @DisplayName("docker-compose.yml 注入的数据源环境变量名必须与 application-p3.yml 占位符一致，且不再注入已失效的 SPRING_DATASOURCE_URL")
    void composeDatasourceEnvVars_shouldMatchP3Placeholders() throws IOException {
        Optional<Path> compose = findUpwards("docker-compose.yml");
        Assumptions.assumeTrue(compose.isPresent(), "未找到 docker-compose.yml，跳过跨文件一致性校验");

        String composeContent = Files.readString(compose.get());
        String p3Content = Files.readString(p3YamlPath());

        for (String variable : List.of("SPRING_DATASOURCE_WRITE_URL", "SPRING_DATASOURCE_READ_URL")) {
            assertThat(composeContent)
                    .as("docker-compose.yml 应注入 %s", variable)
                    .contains(variable + ":");
            assertThat(p3Content)
                    .as("application-p3.yml 应通过 ${%s:...} 占位符读取该环境变量", variable)
                    .contains("${" + variable + ":");
        }

        assertThat(composeContent)
                .as("SPRING_DATASOURCE_URL 在 CQRS 常开时不会被绑定（改了没反应），不应再出现在 compose 中")
                .doesNotContain("SPRING_DATASOURCE_URL:");
    }

    // ==================== 2.1.3 fallback 参与装配决策 ====================

    @Test
    @DisplayName("读库可达时：readDataSource 应装配为读库自身")
    void readDataSource_shouldUseReadReplica_whenReachable() {
        CqrsDataSourceConfig config = new StubProbeConfig(true, true);
        DataSource rawRead = mock(DataSource.class);
        DataSource write = mock(DataSource.class);

        DataSource effective = config.readDataSource(rawRead, write);

        assertThat(effective).isSameAs(rawRead);
    }

    @Test
    @DisplayName("读库不可达且 fallback=true 时：readDataSource 应回退为写库（修复前该开关只打日志，读路由仍指向不可达读库）")
    void readDataSource_shouldFallBackToWriteDataSource_whenReadReplicaUnreachableAndFallbackEnabled() {
        CqrsDataSourceConfig config = new StubProbeConfig(true, false);
        DataSource rawRead = mock(DataSource.class);
        DataSource write = mock(DataSource.class);

        DataSource effective = config.readDataSource(rawRead, write);

        assertThat(effective).isSameAs(write);
    }

    @Test
    @DisplayName("读库不可达且 fallback=false 时：应直接拒绝启动而不是静默使用不可达读库")
    void readDataSource_shouldThrow_whenReadReplicaUnreachableAndFallbackDisabled() {
        CqrsDataSourceConfig config = new StubProbeConfig(false, false);
        DataSource rawRead = mock(DataSource.class);
        DataSource write = mock(DataSource.class);

        assertThatThrownBy(() -> config.readDataSource(rawRead, write))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-replica-fallback-to-primary=false");
    }

    @Test
    @DisplayName("连通性探测：指向不可达端口的真实 HikariDataSource 应被判为不可用")
    void isDataSourceReachable_shouldReturnFalse_whenConnectionRefused() {
        CqrsDataSourceConfig config = new CqrsDataSourceConfig(true);

        HikariDataSource unreachable = new HikariDataSource();
        unreachable.setJdbcUrl("jdbc:mysql://127.0.0.1:65123/agent_qr?connectTimeout=1000");
        unreachable.setUsername("probe");
        unreachable.setPassword("probe");
        unreachable.setMaximumPoolSize(1);
        unreachable.setConnectionTimeout(1500);

        try {
            assertThat(config.isDataSourceReachable(unreachable)).isFalse();
        } finally {
            unreachable.close();
        }
    }

    // ==================== 2.1 真实 Spring 容器装配 ====================

    @Test
    @DisplayName("真实 Spring 容器中：rawReadDataSource 的 @ConfigurationProperties 绑定必须生效（参数注入不得绕过绑定，否则读库会静默永远回退写库）")
    void rawReadDataSource_shouldBeBound_whenAssembledBySpringContext() throws IOException {
        ConfigurableEnvironment environment = p3Environment(Map.of());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(PropertySourcesPlaceholderConfigurer.class,
                    ConfigurationPropertiesAutoConfiguration.class,
                    CqrsDataSourceConfig.class);
            context.refresh();

            HikariDataSource rawRead = context.getBean("rawReadDataSource", HikariDataSource.class);
            assertThat(rawRead.getJdbcUrl())
                    .as("绑定未生效时 jdbcUrl 为 null，连通性探测会静默失败并永远回退写库")
                    .startsWith("jdbc:mysql://localhost:3309/");
            assertThat(rawRead.getUsername()).isEqualTo("root");

            DataSource write = context.getBean("writeDataSource", DataSource.class);
            DataSource effectiveRead = context.getBean("readDataSource", DataSource.class);
            assertThat(effectiveRead)
                    .as("读库装配结果只能是读库自身或写库回退实例")
                    .isIn(rawRead, write);
        }
    }

    // ==================== 测试夹具 ====================

    /**
     * 构造与 Spring Boot 等价的运行环境：环境变量优先级高于 application-p3.yml。
     * <p>移除宿主机真实的 systemProperties / systemEnvironment，保证用例不受本机环境影响。</p>
     */
    private static ConfigurableEnvironment p3Environment(Map<String, Object> environmentVariables) throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        sources.addLast(new SystemEnvironmentPropertySource("testSystemEnvironment", environmentVariables));
        for (PropertySource<?> source :
                new YamlPropertySourceLoader().load("application-p3", new ClassPathResource("application-p3.yml"))) {
            sources.addLast(source);
        }
        return environment;
    }

    /** 按生产代码相同的前缀绑定 DataSource，验证的不只是"配置里有没有写"，而是"真的绑得上"。 */
    private static HikariDataSource bindDatasource(ConfigurableEnvironment environment, String prefix) {
        return Binder.get(environment)
                .bind(prefix, Bindable.of(HikariDataSource.class))
                .orElseThrow(() -> new IllegalStateException("DataSource 配置绑定失败: " + prefix));
    }

    private static Path p3YamlPath() {
        return Paths.get("src", "main", "resources", "application-p3.yml").toAbsolutePath();
    }

    /** 自当前工作目录逐级向上查找文件（surefire 的工作目录是模块目录）。 */
    private static Optional<Path> findUpwards(String fileName) {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Path candidate = current.resolve(fileName);
            if (Files.exists(candidate)) {
                return Optional.of(candidate);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /** 用固定探测结果替换真实连通性探测，使装配决策可在无数据库环境下断言。 */
    private static final class StubProbeConfig extends CqrsDataSourceConfig {

        private final boolean probeResult;

        private StubProbeConfig(boolean fallbackToPrimary, boolean probeResult) {
            super(fallbackToPrimary);
            this.probeResult = probeResult;
        }

        @Override
        protected boolean isDataSourceReachable(DataSource dataSource) {
            return probeResult;
        }
    }
}
