package org.example.agent_qr.datasource.support;

import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实库测试支撑工具（批次 05）。
 * <p>
 * 从仓库的 {@code application.yml} 读取数据源参数，<b>不在测试源码中出现任何凭据原文</b>；
 * 数据库不可达时以 {@link Assumptions#abort} 跳过，保证无库环境下测试仍然"绿"。
 * </p>
 *
 * @author agent-qr
 */
public final class LiveDbSupport {

    private LiveDbSupport() {
    }

    /**
     * 读取 application.yml 中 spring.datasource 的 url/username/password。
     *
     * @param key url / username / password
     * @return 参数值；找不到返回 null
     */
    public static String dataSourceProperty(String key) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", "application.yml"));
            if (Files.exists(candidate)) {
                try {
                    String content = Files.readString(candidate, StandardCharsets.UTF_8);
                    Matcher matcher = Pattern.compile("(?m)^\\s*" + key + ":\\s*(\\S+)\\s*$").matcher(content);
                    if (!matcher.find()) {
                        return null;
                    }
                    return resolvePlaceholder(matcher.group(1));
                } catch (Exception e) {
                    return null;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * 获取一个真实 MySQL 连接；不可用时中止当前测试（Assumption 失败而非 Error）。
     *
     * @return 已建立的连接
     * @throws Exception 获取失败
     */
    public static Connection openConnectionOrSkip() throws Exception {
        String url = dataSourceProperty("url");
        String username = dataSourceProperty("username");
        String password = dataSourceProperty("password");
        Assumptions.assumeTrue(url != null, "application.yml 中未找到数据源配置，跳过实库测试");

        Connection conn;
        try {
            conn = DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达（" + e.getClass().getSimpleName() + "），跳过实库测试");
            return null;
        }
        return conn;
    }

    /**
     * 组装连接配置（供连接器使用），不在测试中硬编码任何凭据。
     *
     * @return 含 url/username/password 的 Map
     */
    public static java.util.Map<String, Object> connectionConfig() {
        java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("url", dataSourceProperty("url"));
        config.put("username", dataSourceProperty("username"));
        config.put("password", dataSourceProperty("password"));
        return config;
    }

    private static String resolvePlaceholder(String raw) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+):([^}]*)}").matcher(raw);
        if (matcher.matches()) {
            String fromEnv = System.getenv(matcher.group(1));
            return fromEnv != null ? fromEnv : matcher.group(2);
        }
        return raw;
    }
}
