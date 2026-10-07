package org.example.agent_qr.rag.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * bge-reranker-v2-m3 交叉编码器提供者（批次 10 · 任务 10.3，问题 14）。
 * <p>
 * 通过 HTTP 调用本地 <b>TEI（text-embeddings-inference）</b>服务完成真实精排：
 * </p>
 * <pre>
 *   POST {base-url}/rerank
 *   {"query":"…","texts":["…"],"model":"bge-reranker-v2-m3"}
 *   → [{"index":0,"score":0.9989},…]     // 已按 score 降序，index 为入参下标
 * </pre>
 * <p>
 * <b>部署事实与限制（progress.md §4.8 实测）</b>：
 * </p>
 * <ul>
 *   <li>服务端 {@code top_n} 被忽略（恒返回全部）→ Java 侧自行截断；</li>
 *   <li>单请求最多 {@code 32} 条 texts → 超出部分由本类<b>分批发送</b>并合并结果；</li>
 *   <li>单对 2048 token（超长静默截断）→ 发送前按 {@code max-doc-chars} 预截断；</li>
 *   <li>CPU 版时延非线性：1 条 0.1s / 8 条 ~1.8s / 19 条 ~10s（本机实测），
 *       故读取超时默认 30s（{@code read-timeout-ms}）；</li>
 *   <li>连接超时 5s（{@code connect-timeout-ms}）——服务不可用时快速失败，
 *       由 {@link RerankerService} 立即降级，不拖慢问答。</li>
 * </ul>
 * <p>
 * <b>失败语义</b>：一切异常（不可达、超时、非 200、响应条目数与入参不一致）统一包装为
 * {@link RerankerException} 抛出，<b>不</b>在提供者内部静默降级——降级决策集中在
 * {@link RerankerService}，且必须留 WARN 日志（问题 14 的教训：原实现是"静默"降级）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class BgeRerankerProvider implements RerankerProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 精排服务地址（TEI / Xinference 等兼容 /rerank 的服务） */
    @Value("${agent-qr.reranker.base-url:http://localhost:8080}")
    private String baseUrl;

    /** 精排模型名（随请求体发送；单模型部署下 TEI 会忽略该字段，多模型部署时用于选择） */
    @Value("${agent-qr.reranker.model:bge-reranker-v2-m3}")
    private String model;

    /** 连接超时（毫秒）——progress.md §4.8 建议 5s */
    @Value("${agent-qr.reranker.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    /** 读取超时（毫秒）——progress.md §4.8 建议 30s */
    @Value("${agent-qr.reranker.read-timeout-ms:30000}")
    private int readTimeoutMs;

    /**
     * 发送前单条文档的预截断字符数（默认 1024）。
     * <p>progress.md §4.8：接近 2048 token 的长文本单对约 11.5s，
     * 建议预截断到 512–1024 字符；超长文本会被服务端静默截断，主动截断可显著降时延。</p>
     */
    @Value("${agent-qr.reranker.max-doc-chars:1024}")
    private int maxDocChars;

    /** 惰性构建的 HTTP 客户端（需等 {@code @Value} 注入完成后再读取连接超时） */
    private volatile HttpClient httpClient;

    /**
     * 启动日志：让运维确认配置（尤其是 model / base-url）已被真实读取。
     */
    @PostConstruct
    public void logConfig() {
        log.info("精排服务配置生效: baseUrl={}, model={}, connectTimeout={}ms, readTimeout={}ms, maxDocChars={}",
                baseUrl, model, connectTimeoutMs, readTimeoutMs, maxDocChars);
    }

    @Override
    public String name() {
        return "BgeRerankerProvider(" + model + "@" + baseUrl + ")";
    }

    @Override
    public List<Score> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int batchSize = maxTextsPerRequest();
        List<Score> scores = new ArrayList<>(documents.size());
        for (int offset = 0; offset < documents.size(); offset += batchSize) {
            int end = Math.min(offset + batchSize, documents.size());
            scores.addAll(scoreBatch(query, documents.subList(offset, end), offset));
        }
        return scores;
    }

    /**
     * 对单个批次（≤ {@link #maxTextsPerRequest()} 条）发起一次 HTTP 请求并解析结果。
     *
     * @param query   用户查询
     * @param batch   本批文档（已截断）
     * @param offset  本批在整体入参中的起始下标（用于还原 {@link Score#index()}）
     * @return 本批打分结果
     * @throws RerankerException 调用或解析失败
     */
    private List<Score> scoreBatch(String query, List<String> batch, int offset) {
        String requestBody = buildRequestBody(query, batch);
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(baseUrl) + "/rerank"))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new RerankerException("精排服务调用超时（read-timeout=" + readTimeoutMs + "ms）: " + baseUrl, e);
        } catch (IOException e) {
            throw new RerankerException("精排服务不可达: " + baseUrl + "（" + e.getMessage() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RerankerException("精排服务调用被中断: " + baseUrl, e);
        }

        if (response.statusCode() != 200) {
            throw new RerankerException("精排服务返回非 200: HTTP " + response.statusCode()
                    + "（model=" + model + ", baseUrl=" + baseUrl + "）");
        }
        return parseScores(response.body(), batch.size(), offset);
    }

    /**
     * 构造请求体 JSON：{@code {"query":…,"texts":[…],"model":…}}。
     * <p>文档按 {@code max-doc-chars} 截断（超长文本服务端也会静默截断，主动截断更省时延）。</p>
     *
     * @param query 用户查询
     * @param batch 本批文档
     * @return JSON 字符串
     */
    String buildRequestBody(String query, List<String> batch) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("query", query == null ? "" : query);
        ArrayNode texts = root.putArray("texts");
        for (String text : batch) {
            texts.add(truncate(text));
        }
        if (model != null && !model.isBlank()) {
            // 单模型 TEI 部署会忽略该字段（实测），多模型部署时用于选择模型；
            // 无论如何都发送，保证 agent-qr.reranker.model 是"真实读取点"而非死配置。
            root.put("model", model);
        }
        return root.toString();
    }

    /**
     * 预截断单条文档。
     *
     * @param text 原始文本
     * @return 截断后的文本（超长时截断到 {@code max-doc-chars} 字符）
     */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int limit = Math.max(1, maxDocChars);
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /**
     * 解析响应并还原全局下标。
     *
     * @param body        响应体
     * @param batchSize   本批条数
     * @param offset      本批起始偏移
     * @return 打分结果
     * @throws RerankerException 响应不是数组、下标越界或条目数与入参不一致
     */
    private List<Score> parseScores(String body, int batchSize, int offset) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new RerankerException("精排服务响应不是合法 JSON: " + preview(body), e);
        }
        if (root == null || !root.isArray()) {
            throw new RerankerException("精排服务响应格式异常（期望 JSON 数组）: " + preview(body));
        }
        List<Score> scores = new ArrayList<>(((ArrayNode) root).size());
        boolean[] seen = new boolean[batchSize];
        for (JsonNode node : root) {
            JsonNode indexNode = node.get("index");
            JsonNode scoreNode = node.get("score");
            if (indexNode == null || scoreNode == null) {
                throw new RerankerException("精排服务响应缺少 index/score 字段: " + node);
            }
            int index = indexNode.asInt(-1);
            if (index < 0 || index >= batchSize) {
                throw new RerankerException("精排服务返回的下标越界: index=" + index
                        + ", batchSize=" + batchSize);
            }
            scores.add(new Score(offset + index, scoreNode.asDouble()));
            seen[index] = true;
        }
        if (scores.size() != batchSize) {
            // 部分返回会让"未返回的文档"分数缺失、排序被静默扭曲，宁可整体失败走降级
            throw new RerankerException("精排服务返回条目数与入参不一致: input=" + batchSize
                    + ", output=" + scores.size());
        }
        if (log.isDebugEnabled()) {
            log.debug("精排批次完成: batchSize={}, offset={}, top={}", batchSize, offset, topOf(scores));
        }
        return scores;
    }

    /**
     * 分数最高的前 3 条（仅 DEBUG 日志用）。
     *
     * @param scores 打分结果
     * @return 形如 {@code [3=0.93, 7=0.11]} 的摘要
     */
    private static String topOf(List<Score> scores) {
        return Arrays.toString(scores.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(3)
                .map(s -> s.index() + "=" + String.format("%.3f", s.score()))
                .toArray());
    }

    /**
     * 响应体摘要（最多 200 字符，避免日志爆炸）。
     *
     * @param body 响应体
     * @return 摘要
     */
    private static String preview(String body) {
        if (body == null) {
            return "null";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }

    /**
     * 去掉 URL 尾部的斜杠。
     *
     * @param url 原始地址
     * @return 规范化地址
     */
    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 惰性构建 HTTP 客户端。
     *
     * @return HttpClient 实例
     */
    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                            .build();
                }
                client = httpClient;
            }
        }
        return client;
    }
}
