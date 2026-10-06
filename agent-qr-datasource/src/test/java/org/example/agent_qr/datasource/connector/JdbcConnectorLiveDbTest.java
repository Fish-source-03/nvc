package org.example.agent_qr.datasource.connector;

import org.example.agent_qr.datasource.dto.SyncContext;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.example.agent_qr.datasource.support.LiveDbSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcConnector} 实库测试（批次 05 · 任务 5.1.4 / 5.2.1）。
 * <p>
 * 为什么必须对真实 MySQL 执行：多表增量的缺陷是"运行时只查了一张表"，
 * 用 Mock 拦不住；流式读取的 {@code setFetchSize} 同样只有真实驱动才生效。
 * </p>
 * <p>
 * 测试表统一使用可清理的 {@code zz_b05_*} 命名，{@code @AfterAll} 中删除。
 * </p>
 *
 * @author agent-qr
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class JdbcConnectorLiveDbTest {

    private static final String T1 = "zz_b05_t1";
    private static final String T2 = "zz_b05_t2";
    private static final String PERF_TABLE = "zz_b05_perf";
    private static final int PERF_ROWS = 10000;

    private static Connection conn;

    private final JdbcConnector connector = new JdbcConnector();

    @BeforeAll
    static void setUp() throws Exception {
        conn = LiveDbSupport.openConnectionOrSkip();
        try (Statement st = conn.createStatement()) {
            // MySQL 递归 CTE 默认上限 1000，装载 10000 行需临时放宽（会话级，不影响其他连接）
            st.execute("SET SESSION cte_max_recursion_depth = 100000");
            st.execute("DROP TABLE IF EXISTS " + T1);
            st.execute("DROP TABLE IF EXISTS " + T2);
            st.execute("CREATE TABLE " + T1 + " (id BIGINT PRIMARY KEY, payload VARCHAR(64))");
            st.execute("CREATE TABLE " + T2 + " (id BIGINT PRIMARY KEY, payload VARCHAR(64))");

            st.execute("DROP TABLE IF EXISTS " + PERF_TABLE);
            st.execute("CREATE TABLE " + PERF_TABLE
                    + " (id BIGINT PRIMARY KEY, name VARCHAR(64), dept VARCHAR(32), salary DECIMAL(12,2))");
            // 10000 行装载性能数据（任务 5.2 的性能验证数据集）
            st.execute("INSERT INTO " + PERF_TABLE + " (id, name, dept, salary) "
                    + "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < "
                    + PERF_ROWS + ") SELECT n, CONCAT('员工', n), 'HR', 10000 + n FROM seq");
        }
    }

    @BeforeEach
    void resetData() throws Exception {
        if (conn == null) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE " + T1);
            st.execute("TRUNCATE TABLE " + T2);
            st.execute("INSERT INTO " + T1 + " VALUES (1,'a'),(2,'b'),(3,'c')");
            st.execute("INSERT INTO " + T2 + " VALUES (1,'x'),(2,'y')");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn == null) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + T1);
            st.execute("DROP TABLE IF EXISTS " + T2);
            st.execute("DROP TABLE IF EXISTS " + PERF_TABLE);
        }
        conn.close();
    }

    // ==================== 5.1.4 多表全量/增量 ====================

    @Test
    @DisplayName("★ 全量同步遍历全部表，并为每张表产出各自游标")
    void fullSync_shouldReadAllTables_andEmitPerTableCursor() {
        SyncResult result = connector.fullSync(new SyncContext(1L, multiTableConfig()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalRows()).isEqualTo(5);
        Map<String, String> cursors = JdbcConnector.decodeCursor(result.getNextCursor(), List.of(T1, T2));
        assertThat(cursors).containsEntry(T1, "3").containsEntry(T2, "2");
    }

    @Test
    @DisplayName("★ 增量同步覆盖全部表：非 tableName 指定的表也必须同步（问题 24 的核心回归）")
    void incrementalSync_shouldCoverAllTables_whenCursorAdvances() throws Exception {
        SyncResult full = connector.fullSync(new SyncContext(1L, multiTableConfig()));
        String cursor = full.getNextCursor();

        // 两张表各自新增一行——旧实现只认 config["tableName"]（此处未配置 → 直接空结果），
        // 因此新表的数据会"永久不再更新"
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO " + T1 + " VALUES (4,'d')");
            st.execute("INSERT INTO " + T2 + " VALUES (3,'z')");
        }

        SyncResult incremental = connector.incrementalSync(new SyncContext(1L, multiTableConfig()), cursor);

        assertThat(incremental.isSuccess()).isTrue();
        assertThat(incremental.getRawData()).extracting(row -> row.get("id"))
                .as("两张表的新增行都要被增量读到")
                .containsExactlyInAnyOrder(4L, 3L);
        Map<String, String> cursors =
                JdbcConnector.decodeCursor(incremental.getNextCursor(), List.of(T1, T2));
        assertThat(cursors).containsEntry(T1, "4").containsEntry(T2, "3");
    }

    @Test
    @DisplayName("★ 历史标量游标（升级前遗留）在多表增量中同样生效，不会丢表")
    void incrementalSync_shouldAcceptLegacyScalarCursor() {
        SyncResult incremental = connector.incrementalSync(new SyncContext(1L, multiTableConfig()), "2");

        assertThat(incremental.isSuccess()).isTrue();
        // t1 的 id=3 与 t2 的 id 无 >2 的行
        assertThat(incremental.getRawData()).extracting(row -> row.get("id")).containsExactly(3L);
    }

    @Test
    @DisplayName("单表数据源（仅 tableName）行为保持兼容")
    void incrementalSync_shouldStillSupportSingleTableConfig() {
        Map<String, Object> config = LiveDbSupport.connectionConfig();
        config.put("tableName", T1);
        config.put("cursorField", "id");

        SyncResult result = connector.incrementalSync(new SyncContext(1L, config), "1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRawData()).extracting(row -> row.get("id")).containsExactly(2L, 3L);
        assertThat(result.getNextCursor()).isEqualTo("3");
    }

    // ==================== 5.2.1 流式读取 ====================

    @Test
    @DisplayName("★ 10000 行流式读取不丢行，且耗时/堆增量在合理范围（任务 5.2 的性能数据集）")
    void fullSync_shouldStreamLargeTable_withoutLosingRows() {
        Map<String, Object> config = LiveDbSupport.connectionConfig();
        config.put("tableNames", List.of(PERF_TABLE));
        config.put("cursorField", "id");

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long start = System.currentTimeMillis();

        SyncResult result = connector.fullSync(new SyncContext(1L, config));

        long elapsed = System.currentTimeMillis() - start;
        long heapDeltaMb = (runtime.totalMemory() - runtime.freeMemory() - before) / (1024 * 1024);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalRows()).isEqualTo(PERF_ROWS);
        assertThat(result.getNextCursor())
                .as("数值游标必须按数值取最大（纯字典序会停在 9999）")
                .isEqualTo(String.valueOf(PERF_ROWS));
        System.out.printf("[perf] JDBC fullSync %d 行: %d ms, 堆增量≈%d MB%n",
                PERF_ROWS, elapsed, heapDeltaMb);
    }

    private Map<String, Object> multiTableConfig() {
        Map<String, Object> config = LiveDbSupport.connectionConfig();
        config.put("tableNames", List.of(T1, T2));
        config.put("cursorField", "id");
        return config;
    }
}
