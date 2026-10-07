package org.example.agent_qr.rag.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BgeRerankerProvider} 测试（批次 10 · 任务 10.3，问题 14）。
 * <p>
 * 用 JDK 内置 {@link HttpServer} 冒充 TEI 的 {@code POST /rerank} 端点（不依赖真实模型服务）：
 * 验证<b>请求体形态</b>（query / texts / model / 预截断）、<b>响应解析</b>（乱序 index、分批下标偏移）、
 * 以及<b>失败语义</b>（超时 / 不可达 / 非 200 / 条目数不符一律抛
 * {@link RerankerProvider.RerankerException}，由上层统一降级）。
 * </p>
 *
 * @author agent-qr
 */
class BgeRerankerProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

    /** 最近一次请求体的解析结果（请求体只能读取一次，故在读完后缓存给处理器用） */
    private final AtomicReference<JsonNode> lastRequestBody = new AtomicReference<>();

    private BgeRerankerProvider provider;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        provider = new BgeRerankerProvider();
        ReflectionTestUtils.setField(provider, "model", "bge-reranker-v2-m3");
        ReflectionTestUtils.setField(provider, "connectTimeoutMs", 1000);
        ReflectionTestUtils.setField(provider, "readTimeoutMs", 3000);
        ReflectionTestUtils.setField(provider, "maxDocChars", 10);
        receivedBodies.clear();
        receivedPaths.clear();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("★ 请求体包含 query/texts/model，且文档按 max-doc-chars 预截断")
    void rerank_shouldSendExpectedRequestBody_withTruncatedDocuments() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "[{\"index\":0,\"score\":0.9},{\"index\":1,\"score\":0.1}]"));

        List<RerankerProvider.Score> scores = provider.rerank("如何离职",
                List.of("这是一个非常长的文档内容超过十个字符", "短文档"));

        assertThat(scores).containsExactly(
                new RerankerProvider.Score(0, 0.9),
                new RerankerProvider.Score(1, 0.1));

        JsonNode body = OBJECT_MAPPER.readTree(receivedBodies.get(0));
        assertThat(body.get("query").asText()).isEqualTo("如何离职");
        assertThat(body.get("model").asText())
                .as("agent-qr.reranker.model 必须被真实读取并随请求发送")
                .isEqualTo("bge-reranker-v2-m3");
        assertThat(body.get("texts")).hasSize(2);
        assertThat(body.get("texts").get(0).asText())
                .as("超长文档必须预截断（progress.md §4.8：长文本单对时延可达 11.5s）")
                .isEqualTo("这是一个非常长的文档");
        assertThat(body.get("texts").get(1).asText()).isEqualTo("短文档");
        assertThat(receivedPaths).containsExactly("/rerank");
    }

    @Test
    @DisplayName("响应 index 乱序也能正确还原下标与分数")
    void rerank_shouldParseShuffledIndices() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "[{\"index\":2,\"score\":0.3},{\"index\":0,\"score\":0.7},{\"index\":1,\"score\":0.5}]"));

        List<RerankerProvider.Score> scores = provider.rerank("q", List.of("a", "b", "c"));

        assertThat(scores).extracting(RerankerProvider.Score::index).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(scores).filteredOn(score -> score.index() == 0)
                .singleElement()
                .satisfies(score -> assertThat(score.score()).isEqualTo(0.7));
    }

    @Test
    @DisplayName("★ 超过 32 条自动分批：两批的 index 分别落在正确的全局下标上")
    void rerank_shouldSplitIntoBatches_whenDocumentsExceed32() throws Exception {
        AtomicInteger call = new AtomicInteger();
        startServer(exchange -> {
            // 请求体已在 startServer 的上下文包装里读取并缓存（同一次请求只能读一次）
            int count = lastRequestBody.get().get("texts").size();
            call.incrementAndGet();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"index\":").append(i).append(",\"score\":0.").append(i % 10).append('}');
            }
            respond(exchange, 200, json.append(']').toString());
        });

        List<String> documents = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            documents.add("doc-" + i);
        }
        List<RerankerProvider.Score> scores = provider.rerank("q", documents);

        assertThat(receivedBodies).hasSize(2);
        assertThat(OBJECT_MAPPER.readTree(receivedBodies.get(0)).get("texts")).hasSize(32);
        assertThat(OBJECT_MAPPER.readTree(receivedBodies.get(1)).get("texts")).hasSize(8);
        assertThat(scores).hasSize(40);
        assertThat(scores).extracting(RerankerProvider.Score::index).contains(0, 31, 32, 39);
    }

    @Test
    @DisplayName("★ 读取超时（服务端慢于 read-timeout-ms）→ 抛 RerankerException，由上层降级")
    void rerank_shouldThrow_whenReadTimeout() throws Exception {
        ReflectionTestUtils.setField(provider, "readTimeoutMs", 300);
        startServer(exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "[{\"index\":0,\"score\":0.5}]");
        });

        assertThatThrownBy(() -> provider.rerank("q", List.of("a")))
                .isInstanceOf(RerankerProvider.RerankerException.class)
                .hasMessageContaining("超时");
    }

    @Test
    @DisplayName("★ 服务不可达（端口无监听）→ 抛 RerankerException")
    void rerank_shouldThrow_whenServiceUnreachable() throws Exception {
        ReflectionTestUtils.setField(provider, "readTimeoutMs", 500);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }   // 立即关闭 → 该端口无监听
        ReflectionTestUtils.setField(provider, "baseUrl", "http://127.0.0.1:" + closedPort);

        assertThatThrownBy(() -> provider.rerank("q", List.of("a")))
                .isInstanceOf(RerankerProvider.RerankerException.class)
                .hasMessageContaining("不可达");
    }

    @Test
    @DisplayName("★ 非 200 响应 → 抛 RerankerException（不静默返回空分）")
    void rerank_shouldThrow_whenNon200() throws Exception {
        startServer(exchange -> respond(exchange, 500, "internal error"));

        assertThatThrownBy(() -> provider.rerank("q", List.of("a", "b")))
                .isInstanceOf(RerankerProvider.RerankerException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("响应条目数与入参不一致 → 抛 RerankerException（避免未返回文档被静默判低分）")
    void rerank_shouldThrow_whenResponseSizeMismatch() throws Exception {
        startServer(exchange -> respond(exchange, 200, "[{\"index\":0,\"score\":0.5}]"));

        assertThatThrownBy(() -> provider.rerank("q", List.of("a", "b")))
                .isInstanceOf(RerankerProvider.RerankerException.class)
                .hasMessageContaining("条目数");
    }

    @Test
    @DisplayName("响应下标越界 → 抛 RerankerException")
    void rerank_shouldThrow_whenIndexOutOfRange() throws Exception {
        startServer(exchange -> respond(exchange, 200, "[{\"index\":5,\"score\":0.5}]"));

        assertThatThrownBy(() -> provider.rerank("q", List.of("a")))
                .isInstanceOf(RerankerProvider.RerankerException.class)
                .hasMessageContaining("下标越界");
    }

    @Test
    @DisplayName("base-url 末尾斜杠容错；空文档列表不发请求")
    void rerank_shouldNormalizeBaseUrl_andShortCircuitOnEmptyInput() throws Exception {
        startServer(exchange -> respond(exchange, 200, "[{\"index\":0,\"score\":0.5}]"));
        ReflectionTestUtils.setField(provider, "baseUrl", "http://localhost:" + port + "/");

        assertThat(provider.rerank("q", List.of("a"))).hasSize(1);
        assertThat(provider.rerank("q", List.of())).isEmpty();
        assertThat(provider.rerank("q", null)).isEmpty();
        assertThat(receivedBodies).hasSize(1);
    }

    // ==================== 辅助 ====================

    private void startServer(Consumer<HttpExchange> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            receivedPaths.add(exchange.getRequestURI().getPath());
            String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(rawBody);
            lastRequestBody.set(OBJECT_MAPPER.readTree(rawBody));
            handler.accept(exchange);
        });
        // 默认执行器：请求串行处理，receivedBodies 的顺序与调用顺序一致
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
        ReflectionTestUtils.setField(provider, "baseUrl", "http://127.0.0.1:" + port);
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // 明确关闭连接：JDK HttpServer 的 mock 不保证 keep-alive 复用安全，
            // 而生产调用是"每请求一条连接或复用"都可能发生的，测试只需覆盖语义
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            exchange.close();
        }
    }
}
