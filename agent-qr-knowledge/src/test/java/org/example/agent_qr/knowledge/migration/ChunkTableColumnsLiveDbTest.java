package org.example.agent_qr.knowledge.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code kb_chunk} 表格元数据列的实库存在性校验（批次 06 · 任务 6.1.4，问题 10）。
 * <p>
 * 为什么必须对真实 MySQL 执行：{@code p2-schema.sql} 的幂等 ALTER 只有在<b>运行库</b>
 * 上执行过，{@code Chunk.contentType} 才真正可写；"资源文件里有、库里没有"是
 * 典型的静默漂移（schema 与实体脱节）。
 * </p>
 * <p>
 * 本测试<b>只读</b>：仅查询 INFORMATION_SCHEMA，不写入任何业务数据，
 * 不触碰 ChromaDB，因此不会破坏 19 条向量 / 11788 行切片的数据基线。
 * </p>
 *
 * @author agent-qr
 */
class ChunkTableColumnsLiveDbTest {

    private static Connection connection;

    @BeforeAll
    static void setUp() {
        String url = dataSourceProperty("url");
        Assumptions.assumeTrue(url != null, "application.yml 中未找到数据源配置，跳过实库测试");
        try {
            connection = DriverManager.getConnection(url,
                    dataSourceProperty("username"), dataSourceProperty("password"));
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达（" + e.getClass().getSimpleName() + "），跳过实库测试");
        }
    }

    @Test
    @DisplayName("★ 运行库 kb_chunk 必须已应用 content_type / table_caption 幂等 ALTER")
    void kbChunk_shouldHaveTableMetadataColumns() throws Exception {
        assertThat(columnDefinition("content_type"))
                .as("kb_chunk.content_type 缺失 → Chunk.contentType 写入会报 Unknown column")
                .isNotNull();
        assertThat(columnDefinition("table_caption"))
                .as("kb_chunk.table_caption 缺失 → Chunk.tableCaption 写入会报 Unknown column")
                .isNotNull();

        assertThat(columnDefinition("content_type").toLowerCase())
                .contains("varchar(16)")
                .contains("text");
        assertThat(columnDefinition("table_caption").toLowerCase())
                .contains("varchar(512)");
    }

    private String columnDefinition(String column) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COLUMN_TYPE, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = ?")) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1) + " default=" + rs.getString(2);
            }
        }
    }

    private static String dataSourceProperty(String key) {
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

    private static String resolvePlaceholder(String value) {
        Matcher matcher = Pattern.compile("\\$\\{([^:}]+):([^}]+)}").matcher(value);
        if (matcher.matches()) {
            String env = System.getenv(matcher.group(1));
            return env != null ? env : matcher.group(2);
        }
        return value;
    }
}
