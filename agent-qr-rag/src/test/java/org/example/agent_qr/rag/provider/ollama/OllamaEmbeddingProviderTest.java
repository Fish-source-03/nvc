package org.example.agent_qr.rag.provider.ollama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OllamaEmbeddingProvider} 测试（批次 05 · 任务 5.2.5）。
 * <p>
 * 用 JDK 内置 {@link HttpServer} 伪造 Ollama，从而对<b>真实 HTTP 往返</b>验证：
 * <ol>
 *   <li>{@code embedBatch} 走真批量端点 {@code /api/embed}，一次请求处理整批
 *       （原实现逐条循环，N 条文本 = N 次请求）；</li>
 *   <li>返回顺序与入参一一对应，返回数量不一致时如实暴露（不静默补齐）；</li>
 *   <li>单条 {@code embed} 仍走旧端点 {@code /api/embeddings}（降级路径依赖，禁止移除）。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
class OllamaEmbeddingProviderTest {

    private HttpServer server;
    private String baseUrl;

    private final List<String> requestPaths = new ArrayList<>();

    private OllamaEmbeddingProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = new OllamaEmbeddingProvider();
        ReflectionTestUtils.setField(provider, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(provider, "model", "qwen3-embedding:4b");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("★ embedBatch 走批量端点 /api/embed：8 条文本只发 1 次 HTTP 请求")
    void embedBatch_shouldCallBulkEndpointOnce() {
        serve("{\"embeddings\":[[1,2],[3,4],[5,6],[7,8],[9,10],[11,12],[13,14],[15,16]]}");

        List<float[]> vectors = provider.embedBatch(List.of("a", "b", "c", "d", "e", "f", "g", "h"));

        assertThat(vectors).hasSize(8);
        assertThat(vectors.get(0)).containsExactly(1f, 2f);
        assertThat(vectors.get(7)).containsExactly(15f, 16f);
        assertThat(requestPaths)
                .as("逐条循环的旧实现会产生 8 次请求")
                .hasSize(1);
        assertThat(requestPaths.get(0)).endsWith("/api/embed");
    }

    @Test
    @DisplayName("★ 批量请求体携带 input 数组（真批量语义）")
    void embedBatch_shouldSendInputArray() {
        serve("{\"embeddings\":[[1],[2]]}");

        provider.embedBatch(List.of("第一段", "第二段"));

        assertThat(lastRequestBody).contains("\"input\":[").contains("\"model\":\"qwen3-embedding:4b\"");
    }

    @Test
    @DisplayName("★ 返回数量与输入不一致时如实返回较短结果，不静默补齐（调用方据此降级逐条重试）")
    void embedBatch_shouldExposeSizeMismatch() {
        serve("{\"embeddings\":[[1,2]]}");

        List<float[]> vectors = provider.embedBatch(List.of("a", "b", "c"));

        assertThat(vectors)
                .as("补齐会造成向量与文本错位；正确做法是把不一致暴露给调用方")
                .hasSize(1);
    }

    @Test
    @DisplayName("批量端点返回 5xx 时抛异常，由 BatchEmbeddingService 降级逐条重试")
    void embedBatch_shouldThrow_whenBulkEndpointFails() {
        serveWithStatus(500, "{\"error\":\"model not found\"}");

        assertThatThrownBy(() -> provider.embedBatch(List.of("a")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("批量 Embedding");
    }

    @Test
    @DisplayName("空输入不发起任何 HTTP 请求")
    void embedBatch_shouldSkipHttp_whenEmpty() {
        serve("{\"embeddings\":[]}");

        assertThat(provider.embedBatch(List.of())).isEmpty();
        assertThat(requestPaths).isEmpty();
    }

    @Test
    @DisplayName("★ 单条 embed 保留旧端点 /api/embeddings（降级路径与批次 07 失败语义依赖）")
    void embed_shouldStillUseLegacySingleEndpoint() {
        server.createContext("/api/embeddings", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            write(exchange, 200, "{\"embedding\":[0.5,0.25]}");
        });
        server.start();

        float[] vector = provider.embed("hello");

        assertThat(vector).containsExactly(0.5f, 0.25f);
        assertThat(requestPaths).containsExactly("/api/embeddings");
    }

    // ==================== 辅助 ====================

    private volatile String lastRequestBody;

    private void serve(String responseBody) {
        serveWithStatus(200, responseBody);
    }

    private void serveWithStatus(int status, String responseBody) {
        server.createContext("/api/embed", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            try (InputStream in = exchange.getRequestBody()) {
                lastRequestBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            write(exchange, status, responseBody);
        });
        server.start();
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}
