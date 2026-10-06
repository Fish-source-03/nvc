package org.example.agent_qr.web.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * CQRS 读写分离数据源 Bean 装配（P3）。
 * <p>
 * 仅在 {@code agent-qr.cqrs.enabled=true} 时激活（即 P3 profile 加载时）。
 * P1/P2 模式下不会加载此类，保持向后兼容。
 * </p>
 *
 * <p><b>Bean 装配：</b></p>
 * <ul>
 *   <li>{@code writeDataSource} — 写库（主库），由 {@code ReadWriteRoutingDataSource} 注入包装</li>
 *   <li>{@code rawReadDataSource} — 读库（从库）原始连接池，仅做配置绑定</li>
 *   <li>{@code readDataSource} — 读库装配结果：装配期探测通过则为 {@code rawReadDataSource}，
 *       不可达且开启回退时为 {@code writeDataSource}，不可达且关闭回退时直接拒绝启动</li>
 * </ul>
 *
 * <p>{@link org.example.agent_qr.common.datasource.ReadWriteRoutingDataSource} 由
 * common 模块的组件扫描自动发现，无需在此额外注册。</p>
 *
 * <p><b>关于 {@code read-replica-fallback-to-primary}：</b>该开关在装配期真正参与决策——
 * 读库连通性探测失败时决定"回退写库"还是"拒绝启动"，而非仅打印日志。</p>
 *
 * @see org.example.agent_qr.common.datasource.ReadWriteRoutingDataSource
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "agent-qr.cqrs.enabled", havingValue = "true")
public class CqrsDataSourceConfig {

    /** 读库探测时连接有效性的校验超时（秒） */
    private static final int PROBE_VALIDATION_TIMEOUT_SECONDS = 2;

    /** 读库不可用时是否回退到写库，来自 {@code agent-qr.cqrs.read-replica-fallback-to-primary} */
    private final boolean readReplicaFallbackToPrimary;

    public CqrsDataSourceConfig(
            @Value("${agent-qr.cqrs.read-replica-fallback-to-primary:true}") boolean readReplicaFallbackToPrimary) {
        this.readReplicaFallbackToPrimary = readReplicaFallbackToPrimary;
    }

    /**
     * 写库（主库）DataSource。
     * <p>由 {@code ReadWriteRoutingDataSource} 通过 {@code @Qualifier("writeDataSource")} 注入包装。</p>
     *
     * @return HikariDataSource 写库实例
     */
    @Bean("writeDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.write")
    public DataSource writeDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        log.info("CQRS 写库已配置: HikariDataSource");
        return dataSource;
    }

    /**
     * 读库（从库）原始 DataSource，仅按 {@code spring.datasource.read.*} 绑定配置。
     * <p>
     * 该 Bean 不直接被路由使用：{@link #readDataSource(DataSource, DataSource)} 会先对它做
     * 一次连通性探测，再决定装配结果为"读库"还是"写库回退"。
     * </p>
     *
     * @return HikariDataSource 读库原始实例
     */
    @Bean("rawReadDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.read")
    public DataSource rawReadDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        log.info("CQRS 读库配置已绑定: HikariDataSource");
        return dataSource;
    }

    /**
     * 读库（从库）DataSource —— 装配期连通性探测 + 回退决策。
     * <p>
     * 探测通过：直接使用读库连接池；<br>
     * 探测失败且 {@code read-replica-fallback-to-primary=true}：回退使用写库实例；<br>
     * 探测失败且 {@code read-replica-fallback-to-primary=false}：抛出异常，拒绝以不可用的读库启动。
     * </p>
     *
     * @param rawReadDataSource 读库原始连接池（{@code rawReadDataSource}）
     * @param writeDataSource   写库连接池（{@code writeDataSource}），回退时复用
     * @return 读库可用时为读库实例，否则为写库实例
     * @throws IllegalStateException 读库不可达且未开启回退
     */
    @Bean("readDataSource")
    public DataSource readDataSource(@Qualifier("rawReadDataSource") DataSource rawReadDataSource,
                                     @Qualifier("writeDataSource") DataSource writeDataSource) {
        if (isDataSourceReachable(rawReadDataSource)) {
            log.info("CQRS 读库已装配: 连通性探测通过，读路由指向读库");
            return rawReadDataSource;
        }

        if (readReplicaFallbackToPrimary) {
            log.warn("CQRS 读库不可达，read-replica-fallback-to-primary=true → 读路由回退到写库数据源");
            return writeDataSource;
        }

        throw new IllegalStateException(
                "CQRS 读库不可达，且 agent-qr.cqrs.read-replica-fallback-to-primary=false，拒绝启动。"
                        + "请检查 spring.datasource.read.jdbc-url（可用环境变量 SPRING_DATASOURCE_READ_URL 覆盖）");
    }

    /**
     * 读库连通性探测：向数据源申请一条连接并做有效性校验。
     * <p>
     * 探测失败不抛出异常，仅返回 {@code false} 并记录 WARN，
     * 由 {@link #readDataSource(DataSource, DataSource)} 依据回退开关决定后续行为。
     * </p>
     *
     * @param dataSource 待探测的数据源
     * @return {@code true} 表示可用
     */
    protected boolean isDataSourceReachable(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection != null && connection.isValid(PROBE_VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("CQRS 读库连通性探测失败: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    @PostConstruct
    public void logConfig() {
        log.info("CQRS 读写分离配置已加载: cqrs.enabled=true, read-replica-fallback={}",
                readReplicaFallbackToPrimary);
    }
}
