package org.example.agent_qr.rag.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.rag.embedding.EmbeddingDimensionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChromaConfig} 与 Collection 隔离的接线测试（批次 07 · 任务 7.1.1）。
 * <p>
 * 拦截的核心缺陷：隔离命名（{@code kb_{provider}_{model}}）没有任何消费方，
 * 实际写入的 Collection 恒为固定配置值——模型隔离形同虚设。
 * 本测试断言"写入侧（{@code ChromaEmbeddingStore} Bean）"确实使用生效名称，
 * 且 cosine 保障作用于同一个名称。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChromaConfigEffectiveCollectionTest {

    private static final String EFFECTIVE = "kb_ollama_qwen3-embedding-4b";

    @Mock
    private EmbeddingDimensionManager dimensionManager;

    private ChromaConfig config;

    /** 伪造 ChromaDB（ChromaEmbeddingStore 构造时会连服务创建 tenant/db/collection） */
    private HttpServer server;

    /** 伪造服务收到的请求路径（用于断言"创建的是哪个 collection"） */
    private final List<String> requestedPaths = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        config = new ChromaConfig();
        ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(config, "collectionName", "enterprise_knowledge");
        ReflectionTestUtils.setField(config, "timeoutSeconds", 30L);
        ReflectionTestUtils.setField(config, "tenant", "default");
        ReflectionTestUtils.setField(config, "database", "default");
        ReflectionTestUtils.setField(config, "dimensionManager", dimensionManager);
        when(dimensionManager.getEffectiveCollectionName()).thenReturn(EFFECTIVE);
        when(dimensionManager.getCollectionName()).thenReturn(EFFECTIVE);
    }

    @Test
    @DisplayName("★ ChromaEmbeddingStore 使用生效 Collection 名（写入侧接通隔离链）")
    void chromaEmbeddingStore_shouldUseEffectiveCollectionName() {
        ChromaEmbeddingStore store = config.chromaEmbeddingStore();

        assertThat(ReflectionTestUtils.getField(store, "collectionName"))
                .as("仍使用固定配置名说明隔离链未接通")
                .isEqualTo(EFFECTIVE);
    }

    @Test
    @DisplayName("★ cosine 保障作用于生效 Collection（避免与写入目标分裂）")
    void ensureCosineDistance_shouldTargetEffectiveCollection() {
        config.ensureCosineDistance();

        verify(dimensionManager).ensureCosineCollection(EFFECTIVE);
    }

    @Test
    @DisplayName("生效名称解析失败时回退配置值，不影响 Bean 装配")
    void effectiveName_shouldFallBackToConfiguredValue() {
        when(dimensionManager.getEffectiveCollectionName()).thenThrow(new IllegalStateException("boom"));

        ChromaEmbeddingStore store = config.chromaEmbeddingStore();

        assertThat(ReflectionTestUtils.getField(store, "collectionName"))
                .isEqualTo("enterprise_knowledge");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================== 假 ChromaDB ====================

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);
        String body;
        if (path.contains("/collections/")) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            body = "{\"id\":\"7fbaddfc-4cd8-4651-b987-827e81e31257\",\"name\":\"" + name
                    + "\",\"metadata\":{\"hnsw:space\":\"cosine\"}}";
        } else if (path.endsWith("/databases/default") || path.endsWith("/databases")) {
            body = "{\"id\":\"db-id\",\"name\":\"default\",\"tenant\":\"default\"}";
        } else {
            body = "{\"name\":\"default\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
