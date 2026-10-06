package org.example.agent_qr.datasource.connector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.agent_qr.datasource.dto.SyncContext;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RestApiConnector} 测试（批次 05 · 任务 5.1.1 / 5.1.5 / 5.1.6 / 5.1.7）。
 * <p>
 * 用 JDK 内置 {@link HttpServer} 伪造上游 REST 服务，从而对<b>真实 HTTP 往返</b>验证：
 * <ol>
 *   <li>增量同步沿用 {@code X-Next-Cursor} 翻页（旧实现只发一次 GET，后续页被丢弃）；</li>
 *   <li>全量同步返回真实游标（旧实现固定返回 null，导致增量路径不可达）；</li>
 *   <li>命中 {@code maxPages} 上限时标记截断（旧实现静默截断且显示成功）；</li>
 *   <li>上游报错时 {@code success == false}（旧实现吞异常）。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
class RestApiConnectorTest {

    private HttpServer server;
    private String baseUrl;

    private final RestApiConnector connector = new RestApiConnector();

    /** 记录服务端收到的请求 URI，用于断言翻页参数 */
    private final List<String> receivedUris = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================== 5.1.5 增量翻页 ====================

    @Test
    @DisplayName("★ REST 增量按 X-Next-Cursor 持续翻页，而不是只发一次 GET")
    void incrementalSync_shouldFollowNextCursorPages() {
        serve((uri, round) -> switch (round) {
            case 0 -> response(200, "[{\"id\":1}]", "n1");
            case 1 -> response(200, "[{\"id\":2}]", "n2");
            case 2 -> response(200, "[{\"id\":3}]", "n3");
            default -> response(200, "[]", null);
        });

        SyncResult result = connector.incrementalSync(
                new SyncContext(1L, restConfig()), "start");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalRows()).as("旧实现只返回第一页的 1 行").isEqualTo(3);
        assertThat(result.getNextCursor()).isEqualTo("n3");
        assertThat(receivedUris).hasSize(4);
        assertThat(receivedUris.get(0)).contains("since=start");
        assertThat(receivedUris.get(1)).contains("since=n1");
        assertThat(receivedUris.get(2)).contains("since=n2");
    }

    // ==================== 5.1.6 全量返回真实游标 ====================

    @Test
    @DisplayName("★ REST 全量同步返回上游提供的真实游标（下次才能进入增量模式）")
    void fullSync_shouldReturnRealNextCursor() {
        serve((uri, round) -> switch (round) {
            case 0 -> response(200, "[{\"id\":1}]", "c1");
            case 1 -> response(200, "[{\"id\":2}]", "c2");
            default -> response(200, "[]", "");
        });

        SyncResult result = connector.fullSync(new SyncContext(1L, restConfig()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getNextCursor())
                .as("旧实现固定返回 null，导致增量路径实际不可达")
                .isEqualTo("c2");
    }

    @Test
    @DisplayName("★ 已知语义边界（返工评估项）：全量→增量会重放最后 1 页，而不是推进到末端游标")
    void fullSyncThenIncremental_shouldReplayFinalPage_knownLimitation() {
        // 上游约定：X-Next-Cursor = 「取下一页所需的游标」；用空游标头表示流结束。
        // 因此「最后一页请求所用的游标」= c2，连接器拿不到「末端游标」。
        serve((uri, round) -> switch (round) {
            case 0 -> response(200, "[{\"id\":1}]", "c1");   // ?page=0
            case 1 -> response(200, "[{\"id\":2}]", "c2");   // ?cursor=c1
            case 2 -> response(200, "[{\"id\":3}]", "");     // ?cursor=c2（最后一页，声明无下一页）
            default -> response(200, "[]", null);
        });

        SyncResult full = connector.fullSync(new SyncContext(1L, restConfig()));

        assertThat(full.getTotalRows()).isEqualTo(3);
        assertThat(full.getNextCursor())
                .as("它是「请求最后一页所用的游标」，不是末端游标")
                .isEqualTo("c2");
        assertThat(receivedUris.get(2)).as("最后一次全量请求用的正是 c2").contains("cursor=c2");

        receivedUris.clear();
        connector.incrementalSync(new SyncContext(1L, restConfig()), full.getNextCursor());

        assertThat(receivedUris.get(0))
                .as("增量从 c2 起拉 → 会重复拉取最后一页（至多 1 页）；"
                        + "备选方案「返回 null」会退化为永远全量，严格更差")
                .contains("since=c2");
    }

    @Test
    @DisplayName("上游完全不提供游标头时，全量结果的 nextCursor 为 null（不伪造游标）")
    void fullSync_shouldReturnNullCursor_whenUpstreamHasNoCursor() {
        // 无游标头时终止条件是"响应体为空"，因此第二页必须返回空数组
        serve((uri, round) -> round == 0
                ? response(200, "[{\"id\":1}]", null)
                : response(200, "[]", null));

        SyncResult result = connector.fullSync(new SyncContext(1L, restConfig()));

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getNextCursor()).isNull();
    }

    // ==================== 5.1.7 maxPages 上限可配置 + 告警标记 ====================

    @Test
    @DisplayName("★ 命中 maxPages 上限时标记 truncated（不再静默截断）")
    void fullSync_shouldMarkTruncated_whenMaxPagesReached() {
        serve((uri, round) -> response(200, "[{\"id\":" + round + "}]", "cursor-" + round));

        Map<String, Object> config = restConfig();
        config.put("maxPages", 3);

        SyncResult result = connector.fullSync(new SyncContext(1L, config));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isTruncated()).as("命中上限必须可被调用方感知").isTrue();
        assertThat(result.getErrorMessage()).contains("maxPages");
        assertThat(result.getTotalRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("正常结束（上游给出空游标）时不标记截断")
    void fullSync_shouldNotMarkTruncated_whenFinishedCleanly() {
        serve((uri, round) -> round == 0
                ? response(200, "[{\"id\":1}]", "")
                : response(200, "[]", null));

        SyncResult result = connector.fullSync(new SyncContext(1L, restConfig()));

        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getTotalRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("maxPages 可配置：非法值回退默认 100")
    void resolveMaxPages_shouldHonorConfigWithFallback() {
        assertThat(RestApiConnector.resolveMaxPages(Map.of("maxPages", 7))).isEqualTo(7);
        assertThat(RestApiConnector.resolveMaxPages(Map.of("maxPages", 0)))
                .isEqualTo(RestApiConnector.DEFAULT_MAX_PAGES);
        assertThat(RestApiConnector.resolveMaxPages(Map.of()))
                .isEqualTo(RestApiConnector.DEFAULT_MAX_PAGES);
    }

    // ==================== 5.1.1 失败语义 ====================

    @Test
    @DisplayName("★ 上游返回 5xx 时 success=false 且 errorMessage 非空（失败不再被记为成功）")
    void fullSync_shouldReportFailure_whenUpstreamErrors() {
        serve((uri, round) -> response(500, "{\"error\":\"boom\"}", null));

        SyncResult result = connector.fullSync(new SyncContext(1L, restConfig()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("REST API 全量同步失败");
    }

    @Test
    @DisplayName("★ 增量同步上游报错时同样返回失败态，且不丢失既有游标")
    void incrementalSync_shouldReportFailure_andKeepCursor_whenUpstreamErrors() {
        serve((uri, round) -> response(503, "{\"error\":\"unavailable\"}", null));

        SyncResult result = connector.incrementalSync(new SyncContext(1L, restConfig()), "keep-me");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getNextCursor()).isEqualTo("keep-me");
    }

    // ==================== 辅助 ====================

    private Map<String, Object> restConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("baseUrl", baseUrl);
        config.put("endpoint", "/data");
        return config;
    }

    /** 脚本化响应体：{状态码, JSON 数组, X-Next-Cursor} */
    private record Stub(String body, int status, String nextCursor) {
    }

    /**
     * 注册一个按"第几次请求"返回脚本化响应的处理器。
     *
     * @param script (请求 URI, 第几次请求) → 响应
     */
    private void serve(BiFunction<String, Integer, Stub> script) {
        server.createContext("/data", exchange -> {
            receivedUris.add(exchange.getRequestURI().toString());
            int round = receivedUris.size() - 1;
            Stub stub = script.apply(exchange.getRequestURI().toString(), round);
            write(exchange, stub);
        });
        server.start();
    }

    private static Stub response(int status, String body, String nextCursor) {
        return new Stub(body, status, nextCursor);
    }

    private static void write(HttpExchange exchange, Stub stub) throws IOException {
        byte[] payload = stub.body().getBytes(StandardCharsets.UTF_8);
        if (stub.nextCursor() != null) {
            exchange.getResponseHeaders().add("X-Next-Cursor", stub.nextCursor());
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(stub.status(), payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}
