package org.example.agent_qr.datasource.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.datasource.dto.ConnectionTestResult;
import org.example.agent_qr.datasource.dto.SyncContext;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC 数据源连接器。
 * <p>
 * 通过 JDBC 协议连接关系型数据库，支持全量同步和基于游标字段的增量同步。
 * </p>
 *
 * <h3>connectionConfig key 约定（批次 05 · 任务 5.1.8）</h3>
 * <table border="1">
 *   <caption>连接配置键</caption>
 *   <tr><th>键</th><th>必填</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code url}</td><td>是</td><td>String</td><td>JDBC URL（日志输出时脱敏）</td></tr>
 *   <tr><td>{@code username}</td><td>否</td><td>String</td><td>数据库用户名</td></tr>
 *   <tr><td>{@code password}</td><td>否</td><td>String</td><td>数据库口令</td></tr>
 *   <tr><td>{@code tableNames}</td><td>是*</td><td>List&lt;String&gt;</td><td>全部待同步表（全量与增量都遍历）</td></tr>
 *   <tr><td>{@code tableName}</td><td>是*</td><td>String</td><td>单表写法；{@code tableNames} 为空时的回退</td></tr>
 *   <tr><td>{@code tableFields}</td><td>否</td><td>Map&lt;String,List&lt;String&gt;&gt;</td><td>按表裁剪 SELECT 字段</td></tr>
 *   <tr><td>{@code cursorField}</td><td>增量必填</td><td>String</td><td>增量游标字段（通常为自增 ID 或时间戳）</td></tr>
 *   <tr><td>{@code fetchSize}</td><td>否</td><td>Number</td><td>流式读取批大小；MySQL 缺省使用逐行流式</td></tr>
 * </table>
 * <p>
 * * {@code tableNames} 与 {@code tableName} 至少提供一个；缺失时同步会以
 * {@link SyncResult#isSuccess()} == false 返回并给出明确原因（而不是静默返回空结果）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class JdbcConnector implements DataSourceConnector {

    /** 非 MySQL 数据库缺省的流式批大小 */
    static final int DEFAULT_FETCH_SIZE = 2000;

    /** JDBC URL 中 {@code jdbc:<subprotocol>://<authority>/<database>?<params>} 的解析正则 */
    private static final Pattern JDBC_URL_PATTERN =
            Pattern.compile("^(jdbc:[A-Za-z0-9]+://)([^/?]*)(/[^?]*)?.*$");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getType() {
        return "JDBC";
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> config) {
        String url = (String) config.get("url");
        String username = (String) config.get("username");
        String password = (String) config.get("password");

        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            DatabaseMetaData meta = conn.getMetaData();
            long latency = System.currentTimeMillis() - start;
            return ConnectionTestResult.ok(latency,
                    meta.getDatabaseProductName(),
                    meta.getDatabaseProductVersion());
        } catch (Exception e) {
            // 批次 05 · 任务 5.1.3：日志脱敏，不输出含凭据的完整 JDBC URL
            log.error("JDBC 连接测试失败: url={}, error={}", maskJdbcUrl(url), e.getMessage());
            return ConnectionTestResult.fail(e.getMessage());
        }
    }

    @Override
    public List<String> detectColumns(Map<String, Object> config, String tableName) {
        String url = (String) config.get("url");
        String username = (String) config.get("username");
        String password = (String) config.get("password");

        List<String> columns = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
            log.info("检测表字段成功: table={}, columns={}", tableName, columns);
        } catch (Exception e) {
            log.error("检测表字段失败: table={}, url={}, error={}",
                    tableName, maskJdbcUrl(url), e.getMessage());
            throw new RuntimeException("无法获取表字段: " + e.getMessage());
        }
        return columns;
    }

    /**
     * 根据 tableFields 配置构建 SELECT SQL。
     * 若该表未指定字段或字段列表为空，返回 SELECT *。
     */
    @SuppressWarnings("unchecked")
    private String buildSelectSql(String table, Map<String, Object> config) {
        Map<String, List<String>> tableFields =
                (Map<String, List<String>>) config.get("tableFields");
        if (tableFields == null || !tableFields.containsKey(table)) {
            return "SELECT * FROM " + table;
        }
        List<String> fields = tableFields.get(table);
        if (fields == null || fields.isEmpty()) {
            return "SELECT * FROM " + table;
        }
        return "SELECT " + String.join(", ", fields) + " FROM " + table;
    }

    @Override
    public SyncResult fullSync(SyncContext context) {
        Map<String, Object> config = context.getConfig();
        String url = (String) config.get("url");
        String username = (String) config.get("username");
        String password = (String) config.get("password");

        List<String> tables = resolveTables(config);
        if (tables.isEmpty()) {
            String reason = "JDBC 全量同步：未指定表名（tableNames / tableName 均为空）";
            log.warn("{}", reason);
            return SyncResult.failure(reason, List.of(), null);
        }

        String cursorField = (String) config.get("cursorField");
        int fetchSize = resolveFetchSize(config, url);

        List<Map<String, Object>> allRows = new ArrayList<>();
        // 每张表各自维护游标（批次 05 · 任务 5.1.4）
        Map<String, String> cursorsByTable = new LinkedHashMap<>();

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            for (String table : tables) {
                String sql = buildSelectSql(table, config);
                int rowCount = streamQuery(conn, sql, null, fetchSize, allRows, cursorField,
                        value -> mergeMaxCursor(cursorsByTable, table, value));
                log.info("JDBC 全量同步: 表 {} 读取 {} 行", table, rowCount);
            }
        } catch (Exception e) {
            log.error("JDBC 全量同步失败: url={}, error={}", maskJdbcUrl(url), e.getMessage(), e);
            return SyncResult.failure("JDBC 全量同步失败: " + e.getMessage(), allRows, null);
        }

        String nextCursor = encodeCursor(cursorsByTable, tables);
        if (cursorField != null) {
            log.info("JDBC 全量同步: 游标字段={}, 各表游标={}", cursorField, cursorsByTable);
        }

        return new SyncResult(allRows.size(), allRows, nextCursor);
    }

    @Override
    public SyncResult incrementalSync(SyncContext context, String lastCursor) {
        Map<String, Object> config = context.getConfig();
        String url = (String) config.get("url");
        String username = (String) config.get("username");
        String password = (String) config.get("password");
        String cursorField = (String) config.get("cursorField");

        List<String> tables = resolveTables(config);
        if (cursorField == null || tables.isEmpty()) {
            String reason = "JDBC 增量同步：缺少游标字段或表名";
            log.warn("{}", reason);
            return SyncResult.failure(reason, List.of(), lastCursor);
        }

        // 兼容两种游标形态：多表 JSON 映射 与 历史的单表标量（批次 05 · 任务 5.1.4）
        Map<String, String> previousCursors = decodeCursor(lastCursor, tables);
        Map<String, String> cursorsByTable = new LinkedHashMap<>(previousCursors);

        int fetchSize = resolveFetchSize(config, url);
        List<Map<String, Object>> allRows = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            for (String table : tables) {
                String previous = previousCursors.get(table);
                String baseSelect = buildSelectSql(table, config);
                String sql = baseSelect + " WHERE " + cursorField
                        + " > ? ORDER BY " + cursorField + " ASC";
                int rowCount = streamQuery(conn, sql,
                        previous != null ? previous : "", fetchSize, allRows, cursorField,
                        value -> mergeMaxCursor(cursorsByTable, table, value));
                log.info("JDBC 增量同步: 表 {} 读取 {} 行, 游标 {} -> {}",
                        table, rowCount, previous, cursorsByTable.get(table));
            }
        } catch (Exception e) {
            log.error("JDBC 增量同步失败: url={}, error={}", maskJdbcUrl(url), e.getMessage(), e);
            return SyncResult.failure("JDBC 增量同步失败: " + e.getMessage(), allRows, lastCursor);
        }

        return new SyncResult(allRows.size(), allRows, encodeCursor(cursorsByTable, tables));
    }

    // ==================== 流式读取（批次 05 · 任务 5.2.1） ====================

    /**
     * 以流式方式执行查询并把结果追加到 {@code sink}。
     * <p>
     * 改造点（批次 05 · 任务 5.2.1）：
     * <ol>
     *   <li>{@code ResultSet.TYPE_FORWARD_ONLY + CONCUR_READ_ONLY}（服务端游标的前提）；</li>
     *   <li>{@code setFetchSize(fetchSize)} —— MySQL 下取 {@link Integer#MIN_VALUE}
     *       逐行流式，其余数据库取配置值/2000，避免驱动一次性把整个结果集拉进客户端内存。</li>
     * </ol>
     * 注意：连接器的对外契约仍要返回 {@code List}（下游 ETL 依赖），
     * 因此"不在客户端积压 ResultSet"是本次改造的真实收益边界。
     * </p>
     *
     * @param conn       数据库连接
     * @param sql        SQL（含 {@code ?} 占位符时由 {@code cursorBind} 绑定）
     * @param cursorBind 游标绑定值（null 表示无占位符）
     * @param fetchSize  流式批大小
     * @param sink       结果收集器
     * @param cursorField 游标字段名（null 表示不计算游标）
     * @param cursorSink 每读到一条更大的游标值时回调
     * @return 本次读取的行数
     */
    private int streamQuery(Connection conn, String sql, String cursorBind, int fetchSize,
                            List<Map<String, Object>> sink, String cursorField,
                            Consumer<String> cursorSink) throws SQLException {
        int rowCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(fetchSize);
            if (cursorBind != null) {
                ps.setString(1, cursorBind);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    }
                    if (cursorField != null) {
                        Object cursorVal = row.get(cursorField);
                        if (cursorVal != null) {
                            cursorSink.accept(cursorVal.toString());
                        }
                    }
                    sink.add(row);
                    rowCount++;
                }
            }
        }
        return rowCount;
    }

    /**
     * 以"取较大值"的语义合并游标，保证每张表的游标始终推进到当前最大值。
     *
     * @param cursorsByTable 表名 → 游标值
     * @param table          表名
     * @param value          新读到的游标值
     */
    private static void mergeMaxCursor(Map<String, String> cursorsByTable, String table, String value) {
        if (value == null) {
            return;
        }
        String current = cursorsByTable.get(table);
        if (current == null || compareCursor(value, current) > 0) {
            cursorsByTable.put(table, value);
        }
    }

    /**
     * 游标比较：两侧都是数值时按数值比较，否则退化为字典序。
     * <p>
     * 定点：纯字典序下 {@code "9999" > "10000"}，会让自增主键的游标停在 9999，
     * 导致 10000 之后的行在下一轮增量中被重复拉取。时间戳等非数值游标仍走字典序。
     * </p>
     *
     * @param left  左值
     * @param right 右值
     * @return 比较结果
     */
    private static int compareCursor(String left, String right) {
        try {
            return new java.math.BigDecimal(left).compareTo(new java.math.BigDecimal(right));
        } catch (NumberFormatException ignored) {
            return left.compareTo(right);
        }
    }

    /**
     * 解析流式读取批大小。
     * <p>
     * 优先级：显式配置的 {@code fetchSize} &gt; MySQL 逐行流式（{@link Integer#MIN_VALUE}）
     * &gt; {@link #DEFAULT_FETCH_SIZE}。
     * </p>
     *
     * @param config 连接配置
     * @param url    JDBC URL（用于判断数据库类型）
     * @return 批大小
     */
    static int resolveFetchSize(Map<String, Object> config, String url) {
        Object configured = config == null ? null : config.get("fetchSize");
        if (configured instanceof Number number) {
            return number.intValue();
        }
        if (url != null && url.startsWith("jdbc:mysql")) {
            // MySQL Connector/J 的逐行流式读取标志：不在客户端缓冲整个 ResultSet
            return Integer.MIN_VALUE;
        }
        return DEFAULT_FETCH_SIZE;
    }

    // ==================== 表与游标工具 ====================

    /**
     * 解析待同步的表清单：{@code tableNames} 优先，回退到单表 {@code tableName}。
     *
     * @param config 连接配置
     * @return 表名列表（顺序按配置，去重）
     */
    @SuppressWarnings("unchecked")
    static List<String> resolveTables(Map<String, Object> config) {
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        Object tableNames = config.get("tableNames");
        if (tableNames instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    tables.add(item.toString());
                }
            }
        }
        if (tables.isEmpty()) {
            Object tableName = config.get("tableName");
            if (tableName != null && !tableName.toString().isBlank()) {
                tables.add(tableName.toString());
            }
        }
        return new ArrayList<>(tables);
    }

    /**
     * 把各表游标编码为持久化字符串。
     * <p>
     * 单表时沿用历史的标量形式（保持向后兼容），多表时使用 JSON 对象
     * {@code {"t1":"v1","t2":"v2"}}，从而让每张表各自维护游标。
     * </p>
     *
     * @param cursorsByTable 表名 → 游标值
     * @param tables         本次同步涉及的表
     * @return 游标字符串；无任何游标时返回 null
     */
    static String encodeCursor(Map<String, String> cursorsByTable, List<String> tables) {
        if (cursorsByTable.isEmpty()) {
            return null;
        }
        if (tables.size() <= 1) {
            String single = cursorsByTable.values().iterator().next();
            if (single == null) {
                return null;
            }
            return single;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(cursorsByTable);
        } catch (Exception e) {
            log.error("JDBC 游标序列化失败，回退单值: {}", e.getMessage());
            return cursorsByTable.values().iterator().next();
        }
    }

    /**
     * 解析上次同步遗留的游标。
     * <p>
     * 兼容两种形态：多表 JSON 映射；以及历史的单表标量（此时对所有表生效）。
     * </p>
     *
     * @param lastCursor 上次游标
     * @param tables     本次同步涉及的表
     * @return 表名 → 游标值（不含 null 值）
     */
    static Map<String, String> decodeCursor(String lastCursor, List<String> tables) {
        Map<String, String> result = new LinkedHashMap<>();
        if (lastCursor == null || lastCursor.isBlank()) {
            return result;
        }
        String trimmed = lastCursor.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, String> parsed = OBJECT_MAPPER.readValue(
                        trimmed, new TypeReference<Map<String, String>>() { });
                parsed.forEach((table, cursor) -> {
                    if (cursor != null) {
                        result.put(table, cursor);
                    }
                });
                return result;
            } catch (Exception e) {
                log.warn("JDBC 游标 JSON 解析失败，按单表标量处理: {}", e.getMessage());
            }
        }
        for (String table : tables) {
            result.put(table, trimmed);
        }
        return result;
    }

    /**
     * JDBC URL 脱敏：仅保留协议、host:port 与库名，丢弃 userinfo 与全部查询参数。
     * <p>
     * URL 可能内嵌凭据（{@code user:password@host}、{@code ?password=...}），
     * 因此日志中一律使用本方法的结果（批次 05 · 任务 5.1.3）。解析失败时按"全部脱敏"处理。
     * </p>
     *
     * @param url 原始 JDBC URL
     * @return 脱敏后的 URL；入参为 null 时返回 null
     */
    static String maskJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = JDBC_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            // 非标准形态（如 jdbc:h2:mem:...）：保守起见不输出任何内容
            int colon = url.indexOf(':');
            return colon > 0 ? url.substring(0, colon + 1) + "***" : "jdbc:***";
        }
        String authority = matcher.group(2) == null ? "" : matcher.group(2);
        int at = authority.indexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        String database = matcher.group(3) == null ? "" : matcher.group(3);
        return matcher.group(1) + authority + database;
    }
}
