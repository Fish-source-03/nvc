package org.example.agent_qr.rag.retriever;

import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChromaRetriever} 删除方法的<b>计数语义</b>测试（批次 08 · 任务 8.3.3，问题 29）。
 * <p>
 * 拦截的缺陷：{@code deleteByDocumentId} / {@code deleteByMetadata} 内部 {@code catch}
 * 后不重抛、store 为 null 时直接 {@code return}，而 {@code OrphanVectorScanner}
 * 无条件 {@code cleaned++} → 日志显示"清理了 N 条"，实际可能一条都没删。
 * 现在删除方法返回<b>实际删除条数</b>，调用方据实计数。
 * </p>
 * <p>
 * 计数为"实际发现并删除"的条数：先按元数据枚举出 ChromaDB 中<b>真实存在</b>的向量 id，
 * 再按 id 删除——因此"没有匹配向量"与"删掉了 N 条"可以严格区分。
 * </p>
 *
 * @author agent-qr
 */
class ChromaRetrieverDeleteCountTest {

    private final List<String> requestUrls = new ArrayList<>();
    private final Deque<String> getResponses = new ArrayDeque<>();
    private boolean offline;

    private ChromaRetriever retriever;
    private ChromaEmbeddingStore store;

    @BeforeEach
    void setUp() {
        retriever = new ChromaRetriever() {
            @Override
            WebClient webClient() {
                return WebClient.builder().exchangeFunction(exchangeFunction()).build();
            }
        };
        ReflectionTestUtils.setField(retriever, "collectionName", "enterprise_knowledge");
        ReflectionTestUtils.setField(retriever, "tenant", "default");
        ReflectionTestUtils.setField(retriever, "database", "default");
        ReflectionTestUtils.setField(retriever, "baseUrl", "http://localhost:8000");
        store = mock(ChromaEmbeddingStore.class);
    }

    // ==================== deleteByIds ====================

    @Test
    @DisplayName("★ deleteByIds 返回实际删除条数；store 未初始化时必须抛（不得静默当成功）")
    void deleteByIds_shouldReturnCount_andFailLoudlyWhenStoreMissing() {
        assertThatThrownBy(() -> retriever.deleteByIds(List.of("v1")))
                .as("store 为 null 时原实现直接 return，调用方会把'一条没删'计成成功")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未初始化");

        ReflectionTestUtils.setField(retriever, "chromaEmbeddingStore", store);

        assertThat(retriever.deleteByIds(List.of("v1", "v2"))).isEqualTo(2);
        verify(store).removeAll(List.of("v1", "v2"));
        assertThat(retriever.deleteByIds(List.of())).as("空入参是无操作，计 0").isZero();
    }

    @Test
    @DisplayName("★ deleteByIds 删除失败时抛异常（调用方据实计数，不虚增）")
    void deleteByIds_shouldThrow_whenStoreDeleteFails() {
        ReflectionTestUtils.setField(retriever, "chromaEmbeddingStore", store);
        doThrow(new RuntimeException("ChromaDB 不可达"))
                .when(store).removeAll(any(java.util.Collection.class));

        assertThatThrownBy(() -> retriever.deleteByIds(List.of("v1")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("批量删除失败");
    }

    // ==================== deleteByMetadata / deleteByDocumentId ====================

    @Test
    @DisplayName("★ deleteByMetadata 返回实际删除条数（按元数据枚举后再删，不再虚报）")
    void deleteByMetadata_shouldReturnActualDeletedCount() {
        ReflectionTestUtils.setField(retriever, "chromaEmbeddingStore", store);
        getResponses.add(records(
                "6e6a6f05-c3c6-41ae-b268-7dec27e51fef|11771|9|null|简历.pdf",
                "470d7404-3bf1-4c4e-9599-93b777e25a78|11772|9|null|简历.pdf"));

        int deleted = retriever.deleteByMetadata("document_id", "9");

        assertThat(deleted).isEqualTo(2);
        verify(store).removeAll(List.of(
                "6e6a6f05-c3c6-41ae-b268-7dec27e51fef",
                "470d7404-3bf1-4c4e-9599-93b777e25a78"));
        assertThat(requestUrls.get(1)).endsWith("/get");
    }

    @Test
    @DisplayName("★ 无匹配向量时返回 0（'无需删除'与'删了 N 条'可区分，避免清理计数虚高）")
    void deleteByMetadata_shouldReturnZero_whenNothingMatches() {
        ReflectionTestUtils.setField(retriever, "chromaEmbeddingStore", store);
        getResponses.add(records());

        assertThat(retriever.deleteByMetadata("document_id", "404404")).isZero();
        verify(store, never()).removeAll(any(java.util.Collection.class));
    }

    @Test
    @DisplayName("★ deleteByDocumentId 委托同一计数口径；ChromaDB 不可达时返回 0 而不抛")
    void deleteByDocumentId_shouldReturnZero_whenChromaUnreachable() {
        ReflectionTestUtils.setField(retriever, "chromaEmbeddingStore", store);
        offline = true;

        assertThat(retriever.deleteByDocumentId(9L))
                .as("兜底清理不应因单点不可达而抛出，但必须如实返回 0")
                .isZero();
        verify(store, never()).removeAll(any(java.util.Collection.class));
        assertThat(retriever.deleteByDocumentId(null)).isZero();
    }

    // ==================== 假 ChromaDB ====================

    private ExchangeFunction exchangeFunction() {
        return request -> {
            requestUrls.add(request.url().getPath());
            if (offline) {
                return Mono.error(new RuntimeException("Connection refused"));
            }
            String path = request.url().getPath();
            String body = path.endsWith("/get")
                    ? (getResponses.isEmpty() ? records() : getResponses.poll())
                    : "{\"id\":\"7fbaddfc-4cd8-4651-b987-827e81e31257\",\"name\":\"enterprise_knowledge\"}";
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build());
        };
    }

    /** 构造 {@code get} 假响应；行格式 {@code vectorId|chunkId|documentId|datasourceId|title} */
    private static String records(String... rows) {
        StringBuilder ids = new StringBuilder("[");
        StringBuilder metadatas = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            String[] p = rows[i].split("\\|", -1);
            if (i > 0) {
                ids.append(',');
                metadatas.append(',');
            }
            ids.append('"').append(p[0]).append('"');
            metadatas.append('{')
                    .append("\"chunk_id\":").append(json(p[1])).append(',')
                    .append("\"document_id\":").append(json(p[2])).append(',')
                    .append("\"datasource_id\":").append(json(p[3])).append(',')
                    .append("\"document_title\":").append(json(p[4]))
                    .append('}');
        }
        return "{\"ids\":" + ids.append(']') + ",\"metadatas\":" + metadatas.append(']') + "}";
    }

    private static String json(String raw) {
        return raw == null || "null".equals(raw) ? "null" : "\"" + raw + "\"";
    }
}
