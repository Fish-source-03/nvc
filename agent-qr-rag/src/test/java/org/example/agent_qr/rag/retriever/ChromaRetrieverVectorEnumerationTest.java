package org.example.agent_qr.rag.retriever;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChromaRetriever} 向量 id 枚举能力测试（批次 07 · 任务 7.0.11 / 7.0.12）。
 * <p>
 * 拦截的关键实现细节（设计文档 §10.2 未写、实测才发现）：<b>向量 id 是 UUID，
 * chunkId 存在元数据 {@code chunk_id} 里</b>，二者不能直接比对；且 ChromaDB v2 的
 * {@code /get} <b>只接受 collection ID</b>，传名称会报
 * {@code Collection ID is not a valid UUIDv4}——两点都会让存量核对静默跑空。
 * </p>
 * <p>
 * 该能力同时供批次 08 的孤儿向量扫描复用，因此断言覆盖 API 形态
 * （记录结构 + 分页 + 元数据过滤）。
 * </p>
 *
 * @author agent-qr
 */
class ChromaRetrieverVectorEnumerationTest {

    /** 每次请求的 URL 路径（验证"先按名称解析 collection ID"与分页行为） */
    private final List<String> requestUrls = new ArrayList<>();

    /** 依次弹出的 {@code /get} 假响应 */
    private final Deque<String> getResponses = new ArrayDeque<>();

    /** 模拟网络故障 */
    private boolean offline;

    private ChromaRetriever retriever;

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
    }

    @Test
    @DisplayName("★ 向量 id（UUID）与 chunkId（元数据 chunk_id）分离，不得直接比对")
    void enumerateVectors_shouldParseVectorIdAndChunkIdSeparately() {
        getResponses.add(records(
                "6e6a6f05-c3c6-41ae-b268-7dec27e51fef|11771|9|null|简历.pdf",
                "470d7404-3bf1-4c4e-9599-93b777e25a78|11779|null|2|数据源"));

        List<ChromaRetriever.ChromaVectorRecord> records = retriever.enumerateVectors(10, 0);

        assertThat(records).hasSize(2);
        ChromaRetriever.ChromaVectorRecord first = records.get(0);
        assertThat(first.vectorId()).isEqualTo("6e6a6f05-c3c6-41ae-b268-7dec27e51fef");
        assertThat(first.chunkId()).isEqualTo(11771L);
        assertThat(first.documentId()).isEqualTo(9L);
        assertThat(first.datasourceId()).isNull();
        assertThat(first.documentTitle()).isEqualTo("简历.pdf");
        assertThat(first.vectorId()).as("向量 id 是 UUID，不是 chunkId").isNotEqualTo("11771");

        assertThat(records.get(1).datasourceId()).isEqualTo(2L);
        assertThat(records.get(1).chunkId()).isEqualTo(11779L);
    }

    @Test
    @DisplayName("★ 必须先按 collection 名称解析出 ID（/get 只接受 ID，传名称会报 InvalidArgumentError）")
    void enumerateVectors_shouldResolveCollectionIdByNameFirst() {
        getResponses.add(records());

        retriever.enumerateVectors(10, 0);

        assertThat(requestUrls.get(0))
                .as("第一次请求应是 collection 元信息（按名称解析 ID）")
                .isEqualTo("/api/v2/tenants/default/databases/default/collections/enterprise_knowledge");
        assertThat(requestUrls.get(1))
                .as("/get 必须使用解析出的 collection ID")
                .isEqualTo("/api/v2/tenants/default/databases/default/collections/"
                        + "7fbaddfc-4cd8-4651-b987-827e81e31257/get");
    }

    @Test
    @DisplayName("★ collection ID 解析结果被缓存，重复枚举不重复解析")
    void resolveCollectionId_shouldBeCached() {
        getResponses.add(records());
        getResponses.add(records());

        retriever.enumerateVectors(10, 0);
        int urlsAfterFirst = requestUrls.size();
        retriever.enumerateVectors(10, 0);

        assertThat(requestUrls.size() - urlsAfterFirst)
                .as("第二次枚举只应发一次 /get（collection ID 命中缓存）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 全量枚举自动分页，拉满一页继续拉下一页（不依赖'数据量小'的假设）")
    void enumerateAllVectors_shouldPage() {
        // 页大小 500：前两页均拉满 → 触发第三页；第三页为空 → 结束
        List<String> page1 = new ArrayList<>();
        List<String> page2 = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            page1.add("uuid-p1-" + i + "|" + (1000 + i) + "|9|null|t");
            page2.add("uuid-p2-" + i + "|" + (5000 + i) + "|9|null|t");
        }
        getResponses.add(records(page1.toArray(String[]::new)));
        getResponses.add(records(page2.toArray(String[]::new)));
        getResponses.add(records());

        List<ChromaRetriever.ChromaVectorRecord> all = retriever.enumerateAllVectors();

        assertThat(all).hasSize(1000);
        assertThat(requestUrls).as("collection 元信息 1 次 + /get 3 次").hasSize(4);
        assertThat(all.get(0).chunkId()).isEqualTo(1000L);
        assertThat(all.get(999).chunkId()).isEqualTo(5499L);
    }

    @Test
    @DisplayName("★ 按 chunkId 反查向量 id：走元数据 $in 过滤，供幂等删除使用")
    void findVectorIdsByChunkIds_shouldUseMetadataFilter() {
        getResponses.add(records(
                "6e6a6f05-c3c6-41ae-b268-7dec27e51fef|11771|9|null|简历.pdf",
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee|11772|9|null|简历.pdf"));

        Map<Long, String> result = retriever.findVectorIdsByChunkIds(List.of(11771L, 11772L));

        assertThat(result)
                .containsEntry(11771L, "6e6a6f05-c3c6-41ae-b268-7dec27e51fef")
                .containsEntry(11772L, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(requestUrls.get(1)).endsWith("/get");
    }

    @Test
    @DisplayName("空入参不发请求；ChromaDB 不可达时返回空结果而非抛出（不阻断主流程）")
    void enumeration_shouldDegradeGracefully() {
        assertThat(retriever.findVectorIdsByChunkIds(List.of())).isEmpty();
        assertThat(retriever.findVectorIdsByChunkIds(null)).isEmpty();
        assertThat(requestUrls).isEmpty();

        offline = true;
        assertThat(retriever.enumerateVectors(10, 0)).isEmpty();
        assertThat(retriever.findVectorIdsByChunkIds(List.of(11771L))).isEmpty();

        offline = false;
        getResponses.add(records("6e6a6f05-c3c6-41ae-b268-7dec27e51fef|11771|9|null|简历.pdf"));
        assertThat(retriever.findVectorIdsByChunkIds(List.of(11771L))).isNotEmpty();
    }

    @Test
    @DisplayName("★ vectorIdFor 由 chunkId 确定性派生（重跑得到同一 id，配合先删后写即幂等）")
    void vectorIdFor_shouldBeDeterministicUuid() {
        String id = ChromaRetriever.vectorIdFor(7387L);
        assertThat(id).isEqualTo(ChromaRetriever.vectorIdFor(7387L));
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(id).isNotEqualTo(ChromaRetriever.vectorIdFor(7388L));
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
                    : "{\"id\":\"7fbaddfc-4cd8-4651-b987-827e81e31257\","
                    + "\"name\":\"enterprise_knowledge\"}";
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build());
        };
    }

    /**
     * 构造 {@code get} 假响应。
     *
     * @param rows 形如 {@code vectorId|chunkId|documentId|datasourceId|title}（缺失用 null）
     * @return ChromaDB 响应 JSON
     */
    private static String records(String... rows) {
        StringBuilder ids = new StringBuilder();
        StringBuilder metadatas = new StringBuilder();
        for (int i = 0; i < rows.length; i++) {
            String[] parts = rows[i].split("\\|", -1);
            if (i > 0) {
                ids.append(',');
                metadatas.append(',');
            }
            ids.append('"').append(parts[0]).append('"');
            StringBuilder metadata = new StringBuilder("{");
            appendField(metadata, "chunk_id", parts[1]);
            appendField(metadata, "document_id", parts[2]);
            appendField(metadata, "datasource_id", parts[3]);
            appendField(metadata, "document_title", parts[4]);
            metadata.append('}');
            metadatas.append(metadata);
        }
        return "{\"ids\":[" + ids + "],\"metadatas\":[" + metadatas + "]}";
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        if (value == null || "null".equals(value)) {
            return;
        }
        if (sb.length() > 1) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":\"").append(value).append('"');
    }
}
