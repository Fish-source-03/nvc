package org.example.agent_qr.rag.perf;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.rag.provider.ollama.OllamaEmbeddingProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 性能冒烟（批次 11 · 任务 11.2.5，复盘报告 §4 经验 3）。
 * <p>
 * <b>定位</b>：这不是回归测试，而是"每个阶段发布前跑一组性能冒烟"的落地——
 * 因此全部指标<b>只打印不断言耗时</b>（断言仅覆盖"结果正确/服务可用"的健全性），
 * 且默认<b>不执行</b>（需 {@code -Dagent-qr.perf=true}），不阻塞任何 PR。
 * </p>
 * <p><b>覆盖（均为只读，不写库/不写向量）</b>：
 * <ol>
 *   <li>Embedding：真实 Ollama 批量嵌入吞吐（知识库入库链路的瓶颈点）；</li>
 *   <li>检索：真实 ChromaDB 上的相似度检索延迟 P50/P95；</li>
 *   <li>并发检索：多线程并发下的 P95（并发问答链路的可观测替代指标）。</li>
 * </ol>
 * </p>
 * <p><b>未覆盖</b>：端到端"并发问答 P95"需要可用的 chat LLM（本机 DEEPSEEK_API_KEY 为占位值、
 * Ollama 仅装 embedding 模型），在无 LLM 的环境下测得的只是错误分支耗时，无参考价值——
 * 故此处显式不测，待具备 LLM 后再补。数据源侧 10000 行 JDBC 全链路冒烟
 * 见 {@code JdbcConnectorLiveDbTest#fullSync_shouldStreamLargeTable_withoutLosingRows}（打印 {@code [perf]} 行）。
 * </p>
 *
 * @author agent-qr
 */
@Tag("perf")
class PerfSmokeLiveTest {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String CHROMA_BASE_URL = "http://localhost:8000";
    private static final String COLLECTION_NAME = "enterprise_knowledge";

    /** 检索迭代次数（取 P50/P95） */
    private static final int RETRIEVAL_ITERATIONS = 20;
    /** 并发检索线程数与每线程请求数 */
    private static final int CONCURRENCY = 8;
    private static final int REQUESTS_PER_THREAD = 5;

    private static OllamaEmbeddingProvider embeddingProvider;
    private static ChromaEmbeddingStore chromaStore;

    @BeforeAll
    static void gate() {
        Assumptions.assumeTrue(Boolean.getBoolean("agent-qr.perf"),
                "性能冒烟：需显式 -Dagent-qr.perf=true 开启（不阻塞 PR）");
    }

    @Test
    @DisplayName("[perf] Embedding 批量吞吐（真实 Ollama）")
    void embedding_shouldReportBatchThroughput() {
        Assumptions.assumeTrue(reachable(OLLAMA_BASE_URL + "/api/tags"), "Ollama 不可达，跳过性能冒烟");

        embeddingProvider = new OllamaEmbeddingProvider();
        ReflectionTestUtils.setField(embeddingProvider, "baseUrl", OLLAMA_BASE_URL);
        ReflectionTestUtils.setField(embeddingProvider, "model", "qwen3-embedding:4b");

        List<String> batch = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            batch.add("性能冒烟样本 " + i + "：这是一段用于测量嵌入吞吐的中文文本。");
        }

        // 预热（首次请求含模型冷加载，不计入统计）
        embeddingProvider.embed(batch.get(0));

        long start = System.currentTimeMillis();
        List<float[]> vectors = embeddingProvider.embedBatch(batch);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(vectors).hasSize(batch.size());
        assertThat(vectors.get(0).length).as("向量维度应与 qwen3-embedding:4b 一致").isEqualTo(2560);
        System.out.printf("[perf] Embedding 批量 %d 条: %d ms（每条均摊 %d ms）%n",
                batch.size(), elapsed, elapsed / batch.size());
    }

    @Test
    @DisplayName("[perf] ChromaDB 相似度检索延迟 P50/P95（只读）")
    void retrieval_shouldReportLatencyPercentiles() {
        Assumptions.assumeTrue(reachable(CHROMA_BASE_URL + "/api/v2/heartbeat"), "ChromaDB 不可达，跳过性能冒烟");
        Assumptions.assumeTrue(reachable(OLLAMA_BASE_URL + "/api/tags"), "Ollama 不可达（需生成查询向量），跳过");

        prepareStore();
        float[] queryVector = queryVector("知识库中有哪些文档？");

        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < RETRIEVAL_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> matches = search(queryVector);
            latencies.add((System.nanoTime() - start) / 1_000_000);
            assertThat(matches).isNotNull();
        }

        Collections.sort(latencies);
        System.out.printf("[perf] ChromaDB 检索 %d 次: P50=%d ms, P95=%d ms, max=%d ms, 命中=%d%n",
                latencies.size(), percentile(latencies, 50), percentile(latencies, 95),
                latencies.get(latencies.size() - 1), search(queryVector).size());
    }

    @Test
    @DisplayName("[perf] 并发检索 P95（8 线程 × 5 请求，只读）")
    void concurrentRetrieval_shouldReportP95() throws Exception {
        Assumptions.assumeTrue(reachable(CHROMA_BASE_URL + "/api/v2/heartbeat"), "ChromaDB 不可达，跳过性能冒烟");
        Assumptions.assumeTrue(reachable(OLLAMA_BASE_URL + "/api/tags"), "Ollama 不可达（需生成查询向量），跳过");

        prepareStore();
        float[] queryVector = queryVector("并发检索延迟测量");

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY * REQUESTS_PER_THREAD; i++) {
                tasks.add(() -> {
                    long start = System.nanoTime();
                    search(queryVector);
                    return (System.nanoTime() - start) / 1_000_000;
                });
            }
            List<Long> latencies = new ArrayList<>();
            for (Future<Long> future : pool.invokeAll(tasks)) {
                latencies.add(future.get());
            }

            Collections.sort(latencies);
            System.out.printf("[perf] 并发检索 %d 次 @%d 线程: P50=%d ms, P95=%d ms, max=%d ms%n",
                    latencies.size(), CONCURRENCY, percentile(latencies, 50),
                    percentile(latencies, 95), latencies.get(latencies.size() - 1));
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 构造只读的 Chroma 客户端（不写任何向量；不触碰集合内容）。
     */
    private static void prepareStore() {
        if (chromaStore == null) {
            chromaStore = ChromaEmbeddingStore.builder()
                    .apiVersion(ChromaApiVersion.V2)
                    .baseUrl(CHROMA_BASE_URL)
                    .collectionName(COLLECTION_NAME)
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        }
        if (embeddingProvider == null) {
            embeddingProvider = new OllamaEmbeddingProvider();
            ReflectionTestUtils.setField(embeddingProvider, "baseUrl", OLLAMA_BASE_URL);
            ReflectionTestUtils.setField(embeddingProvider, "model", "qwen3-embedding:4b");
        }
    }

    private static float[] queryVector(String query) {
        return embeddingProvider.embed(query);
    }

    private static List<EmbeddingMatch<dev.langchain4j.data.segment.TextSegment>> search(float[] vector) {
        return chromaStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(vector))
                .maxResults(5)
                .build()).matches();
    }

    /**
     * 取百分位（最近秩法；列表已升序）。
     */
    static long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /**
     * 服务可达性探测（不可达即跳过，保持"无服务环境下测试仍绿"的既有约定）。
     */
    static boolean reachable(String url) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
