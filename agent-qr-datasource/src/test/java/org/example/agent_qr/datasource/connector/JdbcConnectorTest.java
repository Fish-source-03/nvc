package org.example.agent_qr.datasource.connector;

import org.example.agent_qr.datasource.dto.SyncContext;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcConnector} 单元测试（批次 05 · 任务 5.1 / 5.2.1）。
 * <p>
 * 拦截的核心缺陷：
 * <ol>
 *   <li><b>问题 23</b>：连接器吞异常 → 返回"空/部分结果" → 调用方记为 SUCCESS；
 *       本测试断言失败时 {@code success == false} 且 {@code errorMessage} 非空。</li>
 *   <li><b>问题 24</b>：增量只认单表 {@code tableName}；本测试断言表清单解析与
 *       多表游标的编解码（每表各自维护游标）。</li>
 *   <li>日志脱敏：JDBC URL 可能内嵌凭据，断言脱敏结果不含 user/password/查询参数。</li>
 *   <li><b>问题 21 ①</b>：全仓库此前 {@code fetchSize} 零命中，
 *       断言 MySQL 走流式、其余数据库走默认批大小、且配置可覆盖。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
class JdbcConnectorTest {

    private final JdbcConnector connector = new JdbcConnector();

    // ==================== 5.1.1 失败语义 ====================

    @Test
    @DisplayName("★ 连接不上数据库时返回 success=false 且 errorMessage 非空（失败不再被记为成功）")
    void fullSync_shouldReportFailure_whenDatabaseUnreachable() {
        Map<String, Object> config = new LinkedHashMap<>();
        // 端口 1 必然拒绝连接，connectTimeout 保证快速失败
        config.put("url", "jdbc:mysql://127.0.0.1:1/zz_b05_nonexistent?connectTimeout=500");
        config.put("username", "nobody");
        config.put("password", "nobody");
        config.put("tableNames", List.of("zz_b05_t1"));

        SyncResult result = connector.fullSync(new SyncContext(1L, config));

        assertThat(result.isSuccess()).as("异常被吞导致 success 恒为 true 时本断言失败").isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
        assertThat(result.getRawData()).isEmpty();
    }

    @Test
    @DisplayName("★ 增量同步缺游标字段/表名时返回 success=false，而不是静默的空结果")
    void incrementalSync_shouldReportFailure_whenCursorFieldMissing() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", "jdbc:mysql://127.0.0.1:1/zz_b05_nonexistent?connectTimeout=500");
        config.put("tableNames", List.of("zz_b05_t1"));

        SyncResult result = connector.incrementalSync(new SyncContext(1L, config), "10");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("缺少游标字段或表名");
        assertThat(result.getNextCursor()).as("失败时不应丢失已有游标").isEqualTo("10");
    }

    @Test
    @DisplayName("未配置任何表名时返回 success=false 并给出明确原因")
    void fullSync_shouldReportFailure_whenNoTableConfigured() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", "jdbc:mysql://127.0.0.1:1/zz_b05_nonexistent?connectTimeout=500");

        SyncResult result = connector.fullSync(new SyncContext(1L, config));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("未指定表名");
    }

    // ==================== 5.1.3 日志脱敏 ====================

    @Test
    @DisplayName("★ JDBC URL 脱敏：丢弃查询参数（可能内嵌密码），仅保留 host/port/库名")
    void maskJdbcUrl_shouldDropQueryParams() {
        String masked = JdbcConnector.maskJdbcUrl(
                "jdbc:mysql://localhost:3308/agent_qr?user=root&password=super-secret&useSSL=false");

        assertThat(masked).isEqualTo("jdbc:mysql://localhost:3308/agent_qr");
        assertThat(masked).doesNotContain("super-secret").doesNotContain("password").doesNotContain("root");
    }

    @Test
    @DisplayName("★ JDBC URL 脱敏：丢弃 userinfo（user:password@host）")
    void maskJdbcUrl_shouldDropUserInfo() {
        String masked = JdbcConnector.maskJdbcUrl("jdbc:mysql://admin:pa55w0rd@db.internal:3306/kb");

        assertThat(masked).isEqualTo("jdbc:mysql://db.internal:3306/kb");
        assertThat(masked).doesNotContain("pa55w0rd").doesNotContain("admin@");
    }

    @Test
    @DisplayName("非标准形态的 JDBC URL 也必须脱敏（保守失败），不得原样输出")
    void maskJdbcUrl_shouldFailClosed_onNonStandardUrl() {
        String masked = JdbcConnector.maskJdbcUrl("jdbc:h2:mem:testdb;USER=sa;PASSWORD=secret");

        assertThat(masked).doesNotContain("secret").doesNotContain("sa");
    }

    // ==================== 5.1.4 多表 ====================

    @Test
    @DisplayName("★ 表清单解析：tableNames 优先，缺失时回退单表 tableName（保证多表增量覆盖全部表）")
    void resolveTables_shouldPreferTableNames_thenFallbackToTableName() {
        Map<String, Object> multi = Map.of("tableNames", List.of("t1", "t2", "t1"));
        assertThat(JdbcConnector.resolveTables(multi)).containsExactly("t1", "t2");

        Map<String, Object> single = Map.of("tableName", "legacy_table");
        assertThat(JdbcConnector.resolveTables(single)).containsExactly("legacy_table");

        assertThat(JdbcConnector.resolveTables(Map.of())).isEmpty();

        Map<String, Object> both = new LinkedHashMap<>();
        both.put("tableNames", List.of("a"));
        both.put("tableName", "b");
        assertThat(JdbcConnector.resolveTables(both)).containsExactly("a");
    }

    @Test
    @DisplayName("★ 多表游标往返：JSON 映射编码可被解码还原（每表各自维护游标）")
    void encodeAndDecodeCursor_shouldRoundTripMultiTable() {
        Map<String, String> cursors = new LinkedHashMap<>();
        cursors.put("t1", "100");
        cursors.put("t2", "7");

        String encoded = JdbcConnector.encodeCursor(cursors, List.of("t1", "t2"));

        assertThat(encoded).contains("t1").contains("100").contains("t2");
        assertThat(JdbcConnector.decodeCursor(encoded, List.of("t1", "t2")))
                .containsEntry("t1", "100").containsEntry("t2", "7");
    }

    @Test
    @DisplayName("单表游标沿用标量形式（向后兼容历史 last_cursor）")
    void encodeCursor_shouldKeepScalarForm_forSingleTable() {
        String encoded = JdbcConnector.encodeCursor(Map.of("only", "42"), List.of("only"));

        assertThat(encoded).isEqualTo("42");
    }

    @Test
    @DisplayName("★ 历史标量游标可被多表解码（升级后不丢游标）")
    void decodeCursor_shouldSpreadLegacyScalar_toAllTables() {
        assertThat(JdbcConnector.decodeCursor("99", List.of("t1", "t2")))
                .containsEntry("t1", "99").containsEntry("t2", "99");
        assertThat(JdbcConnector.decodeCursor(null, List.of("t1"))).isEmpty();
    }

    // ==================== 5.2.1 流式读取 ====================

    @Test
    @DisplayName("★ MySQL 缺省启用逐行流式读取（setFetchSize(Integer.MIN_VALUE)）")
    void resolveFetchSize_shouldStreamForMysqlByDefault() {
        assertThat(JdbcConnector.resolveFetchSize(Map.of(), "jdbc:mysql://localhost:3306/db"))
                .isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("非 MySQL 数据库使用默认批大小，避免 Integer.MIN_VALUE 的方言风险")
    void resolveFetchSize_shouldUseDefault_forNonMysql() {
        assertThat(JdbcConnector.resolveFetchSize(Map.of(), "jdbc:postgresql://localhost:5432/db"))
                .isEqualTo(JdbcConnector.DEFAULT_FETCH_SIZE);
    }

    @Test
    @DisplayName("配置的 fetchSize 优先生效（可在 MySQL 上改用正数批大小）")
    void resolveFetchSize_shouldHonorConfiguredValue() {
        assertThat(JdbcConnector.resolveFetchSize(Map.of("fetchSize", 500), "jdbc:mysql://h/db"))
                .isEqualTo(500);
    }

    // ==================== 连接器元信息 ====================

    @Test
    @DisplayName("连接器类型标识保持 JDBC（策略路由依赖）")
    void getType_shouldStayJdbc() {
        assertThat(connector.getType()).isEqualTo("JDBC");
    }
}
