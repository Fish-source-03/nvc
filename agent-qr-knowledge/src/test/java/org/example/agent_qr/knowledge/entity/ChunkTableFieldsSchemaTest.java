package org.example.agent_qr.knowledge.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表格结构化切片元数据测试（批次 06 · 任务 6.1.4，问题 10）。
 * <p>
 * <b>拦截的缺陷</b>：{@code Chunk} 实体与 {@code kb_chunk} 表都没有表达
 * "这条切片是表格 / 表格片段"的字段，检索侧无法还原表格上下文。
 * </p>
 *
 * @author agent-qr
 */
class ChunkTableFieldsSchemaTest {

    @Test
    @DisplayName("★ Chunk 实体新增 contentType / tableCaption 字段（且未破坏批次 07 的 status 字段）")
    void chunk_shouldHaveTableMetadataFields() throws Exception {
        Field contentType = Chunk.class.getDeclaredField("contentType");
        Field tableCaption = Chunk.class.getDeclaredField("tableCaption");
        assertThat(contentType.getType()).isEqualTo(String.class);
        assertThat(tableCaption.getType()).isEqualTo(String.class);

        Chunk chunk = new Chunk();
        chunk.setContentType("TABLE_FRAGMENT");
        chunk.setTableCaption("员工明细表");
        assertThat(chunk.getContentType()).isEqualTo("TABLE_FRAGMENT");
        assertThat(chunk.getTableCaption()).isEqualTo("员工明细表");

        // 批次 07 的 status 映射必须保持存在（回归护栏）
        assertThat(Chunk.class.getDeclaredField("status")).isNotNull();
    }

    @Test
    @DisplayName("★ kb_chunk 表结构同步新增 content_type / table_caption（幂等 ALTER）")
    void p2Schema_shouldAddTableMetadataColumns() throws Exception {
        String schema = Files.readString(repositoryFile(Path.of("agent-qr-web", "src", "main",
                "resources", "db", "p2-schema.sql")), StandardCharsets.UTF_8);

        assertThat(schema)
                .as("content_type 列必须通过幂等过程 p2_add_column 添加（可直接重复执行）")
                .contains("CALL p2_add_column('kb_chunk', 'content_type',")
                .contains("VARCHAR(16) DEFAULT 'TEXT'");
        assertThat(schema)
                .as("table_caption 列必须通过幂等过程 p2_add_column 添加")
                .contains("CALL p2_add_column('kb_chunk', 'table_caption',")
                .contains("VARCHAR(512)");
        assertThat(schema)
                .as("不得使用裸 ALTER TABLE ... ADD COLUMN（重复执行会报错）")
                .doesNotContain("ALTER TABLE kb_chunk ADD COLUMN content_type")
                .doesNotContain("ALTER TABLE kb_chunk ADD COLUMN table_caption");
    }

    @Test
    @DisplayName("字段名与列名的驼峰映射前提仍成立（map-underscore-to-camel-case=true）")
    void mybatisMapping_shouldStillBeEnabled() throws Exception {
        String yml = Files.readString(repositoryFile(Path.of("agent-qr-web", "src", "main",
                "resources", "application.yml")), StandardCharsets.UTF_8);
        assertThat(yml)
                .as("contentType -> content_type、tableCaption -> table_caption 依赖该开关")
                .contains("map-underscore-to-camel-case: true");
    }

    /** 从模块目录（mvn 运行目录）向上寻找仓库文件，兼容从仓库根目录运行。 */
    private Path repositoryFile(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到文件 " + relative + "（当前目录: " + Path.of("").toAbsolutePath() + "）");
    }
}
