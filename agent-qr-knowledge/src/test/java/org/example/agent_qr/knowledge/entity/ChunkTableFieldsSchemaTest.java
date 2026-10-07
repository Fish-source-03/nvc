package org.example.agent_qr.knowledge.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表格结构化切片元数据测试（批次 06 · 任务 6.1.4，问题 10；批次 11 收尾清单 R39）。
 * <p>
 * <b>拦截的缺陷</b>：{@code Chunk} 实体与 {@code kb_chunk} 表都没有表达
 * "这条切片是表格 / 表格片段"的字段，检索侧无法还原表格上下文。
 * </p>
 * <p>
 * <b>R39（批次 11 定案）</b>：两列补上后<b>既无写入方也无读取方</b>——即问题 10 的
 * "结构化元数据"实际未闭环。经评估二选一，本次取 <b>B：明确标注为"预留给后续批次"</b>
 * （而非半接线：ETL 路径的 {@code ChunkMapper#insertBatch} 手写 INSERT 列清单不含该列，
 * 只 set 不补列等于不落库，属"看起来接线、实际不落库"）。本类的后两条用例
 * 分别把"标注已写明"与"标注未过期（确实还没有写入方）"钉住。
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

    /**
     * ★ R39 定案（批次 11 收尾清单）：两列是"死列"，二选一后取
     * <b>B —— 明确标注为"预留给后续批次"</b>（不留含糊状态）。
     * <p>
     * 本用例把该标注**钉住**：实体注释、schema、设计文档三处都必须写明
     * "预留字段 / 无写入方、无读取方"——否则后来者极易把"字段存在"误读成"能力已闭环"
     * （这正是 R39 被发现时的问题）。
     * </p>
     */
    @Test
    @DisplayName("★ R39：三处文档必须明确标注 content_type / table_caption 为'预留字段（无写入方、无读取方）'")
    void tableMetadataColumns_shouldBeExplicitlyMarkedAsReserved() throws Exception {
        String entity = Files.readString(repositoryFile(Path.of("agent-qr-knowledge", "src", "main",
                "java", "org", "example", "agent_qr", "knowledge", "entity", "Chunk.java")),
                StandardCharsets.UTF_8);
        assertThat(entity)
                .as("实体注释必须点明这是死列（无写入方、无读取方），并列出闭环所需的文件")
                .contains("当前为\"预留字段\"：无写入方、无读取方")
                .contains("ChunkEmbeddingBatchListener#processDocumentParsed")
                .contains("ChunkMapper#insertBatch");
        assertThat(entity)
                .as("tableCaption 还必须说明'暂无数据来源'（表标题/前导文本仍在正文文本段里）")
                .contains("暂无数据来源");

        String schema = Files.readString(repositoryFile(Path.of("agent-qr-web", "src", "main",
                "resources", "db", "p2-schema.sql")), StandardCharsets.UTF_8);
        assertThat(schema)
                .as("schema 同样必须标注（否则只改代码注释、DDL 仍像'已闭环'）")
                .contains("当前为\"预留字段\"")
                .contains("无写入方、无读取方");

        String designDoc = Files.readString(repositoryFile(Path.of("doc", "系统详细设计说明书.md")),
                StandardCharsets.UTF_8);
        assertThat(designDoc)
                .as("§8.15.2 的'已知缺口'行升级为明确的'预留给后续批次'标注（含复现事实）")
                .contains("预留字段（无写入方、无读取方）")
                .contains("ChunkMapper#insertBatch")
                .contains("检索链路不受影响");
    }

    /**
     * ★ R39 的反向护栏：标注为"预留"的<b>当前事实</b>必须仍然成立——
     * 两处落库点不得偷偷开始 set 这两个字段（否则标注过期，且 ETL 路径还会因
     * {@code insertBatch} 列清单缺失而"看起来接线、实际不落库"）。
     * <p>
     * 将来若真正接线（并补上 {@code ChunkMapper#insertBatch} 的列），
     * 应同时更新标注并改写/删除本用例——这是设计上的"提醒"。
     * </p>
     */
    @Test
    @DisplayName("★ R39：落库点当前确实没有写入方（标注未过期）——两处 new Chunk() 均未 set 表格字段")
    void noWriter_shouldStillHold_forTableMetadataColumns() throws Exception {
        String listener = Files.readString(repositoryFile(Path.of("agent-qr-knowledge", "src", "main",
                "java", "org", "example", "agent_qr", "knowledge", "listener",
                "ChunkEmbeddingBatchListener.java")), StandardCharsets.UTF_8);
        String etlListener = Files.readString(repositoryFile(Path.of("agent-qr-knowledge", "src", "main",
                "java", "org", "example", "agent_qr", "knowledge", "listener",
                "DataSyncEtlListener.java")), StandardCharsets.UTF_8);

        assertThat(listener).doesNotContain("setContentType(").doesNotContain("setTableCaption(");
        assertThat(etlListener).doesNotContain("setContentType(").doesNotContain("setTableCaption(");
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
