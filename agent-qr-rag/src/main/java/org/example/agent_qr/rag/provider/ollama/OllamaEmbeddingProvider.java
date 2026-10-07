package org.example.agent_qr.rag.provider.ollama;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.provider.EmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama Embedding 提供商实现（P2 优化版）。
 * <p>
 * 通过 Ollama 本地部署的接口提供文本向量化能力：
 * <ul>
 *   <li>单条：{@code POST /api/embeddings}（{@link #embed(String)}，兼容性最好的旧端点，<b>保留</b>）；</li>
 *   <li>批量：{@code POST /api/embed}（{@link #embedBatch(List)}，Ollama ≥ 0.2 提供的真批量端点）。</li>
 * </ul>
 * </p>
 * <p>
 * <b>批次 05 · 任务 5.2.5</b>：{@code embedBatch} 原先逐条循环调用 {@code embed}，
 * 一次 32 条的攒批会产生 32 次 HTTP 往返——与设计 §17.8 的
 * "调用 embedBatch(texts) 一次处理整批、复杂度 O(N/B) 次 API 调用" 相矛盾。
 * 实测（本机 Ollama 0.35.0，qwen3-embedding:4b，16 线程并发）向量化占单次同步总耗时
 * <b>90.3%</b>，超过 40% 的改造判据，故改用批量端点。
 * </p>
 * <p>
 * 失败语义（批次 07 · 任务 7.2.5 起）：批量端点失败或返回数量与输入不一致时，由调用方
 * （{@code BatchEmbeddingService.executeBatch}）统一按<b>整批失败</b>处理
 * （整批 future 以异常完成，不再降级逐条重试）；因此本方法只需如实返回解析结果并告警，
 * 不做静默补齐——按索引配对的前提是数量严格一致，"猜"出来的配对会把 A 的向量写给 B。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    @Value("${ollama.embedding.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.embedding.model:nomic-embed-text}")
    private String model;

    /**
     * 批量响应体的最大缓冲字节数（批次 05 · 任务 5.2.5）。
     * <p>
     * ⚠️ 实测坑：WebClient 默认缓冲区上限仅 256 KB，而 2560 维向量按 JSON 文本序列化后
     * 单条约 33 KB，32 条的批量响应约 1.1 MB —— 直接调用 {@code /api/embed} 会抛
     * {@code DataBufferLimitException}，进而被上层降级为逐条重试（比分改前更慢）。
     * 因此必须显式放大缓冲上限。
     * </p>
     */
    @Value("${ollama.embedding.max-response-bytes:16777216}")
    private int maxResponseBytes = 16 * 1024 * 1024;

    private volatile WebClient webClient;

    /**
     * 惰性构建 WebClient（需要 {@code @Value} 注入完成后再读取缓冲上限）。
     *
     * @return WebClient 实例
     */
    private WebClient webClient() {
        WebClient client = webClient;
        if (client == null) {
            synchronized (this) {
                if (webClient == null) {
                    webClient = WebClient.builder()
                            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes))
                            .build();
                }
                client = webClient;
            }
        }
        return client;
    }

    @Override
    public float[] embed(String text) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "prompt", text
            );

            Map response = webClient().post()
                    .uri(baseUrl + "/api/embeddings")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.get("embedding") instanceof List<?> embeddingList) {
                float[] vector = new float[embeddingList.size()];
                for (int i = 0; i < embeddingList.size(); i++) {
                    Object val = embeddingList.get(i);
                    vector[i] = val instanceof Number num ? num.floatValue() : 0f;
                }
                log.debug("Ollama Embedding 成功，维度: {}", vector.length);
                return vector;
            }
            throw new RuntimeException("Ollama Embedding 返回结果为空或格式异常: response=" + response);
        } catch (Exception e) {
            log.error("Ollama Embedding 调用失败", e);
            throw new RuntimeException("Ollama Embedding 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量向量化（批次 05 · 任务 5.2.5：改用 Ollama 的 {@code /api/embed} 批量端点）。
     * <p>
     * 一次 HTTP 请求处理整批（{@code input} 数组），返回顺序与入参一一对应。
     * 若返回数量与输入不一致（批量端点部分失败/截断），仅做 WARN 后原样返回，
     * 由 {@code BatchEmbeddingService} 的数量校验触发<b>整批失败</b>——
     * 此处不静默补齐，避免"向量与文本错位"这类更隐蔽的错误。
     * </p>
     *
     * @param texts 待向量化文本列表
     * @return 向量数组列表（顺序同入参）
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "input", texts
            );

            Map response = webClient().post()
                    .uri(baseUrl + "/api/embed")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !(response.get("embeddings") instanceof List<?> embeddings)) {
                // 端点不可用（老版本 Ollama）或返回格式异常 → 抛出，由上层降级逐条重试
                throw new RuntimeException("Ollama 批量 Embedding 返回结果为空或格式异常: response="
                        + (response == null ? "null" : response.keySet()));
            }

            List<float[]> results = new ArrayList<>(embeddings.size());
            for (Object item : embeddings) {
                if (item instanceof List<?> vector) {
                    results.add(toVector(vector));
                }
            }
            if (results.size() != texts.size()) {
                log.warn("Ollama 批量 Embedding 返回数量与输入不一致: input={}, output={}（将由调用方整批失败处理）",
                        texts.size(), results.size());
            } else {
                log.debug("Ollama 批量 Embedding 成功: batchSize={}, 维度={}",
                        results.size(), results.isEmpty() ? 0 : results.get(0).length);
            }
            return results;
        } catch (Exception e) {
            log.error("Ollama 批量 Embedding 调用失败: batchSize={}", texts.size(), e);
            throw new RuntimeException("Ollama 批量 Embedding 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 数组形态的向量转为 {@code float[]}。
     *
     * @param vector 原始列表
     * @return 向量数组
     */
    private float[] toVector(List<?> vector) {
        float[] result = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            Object val = vector.get(i);
            result[i] = val instanceof Number num ? num.floatValue() : 0f;
        }
        return result;
    }
}
