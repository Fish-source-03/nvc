package org.example.agent_qr.rag.provider;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.circuitbreaker.LLMCircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider 切换自动决策引擎（P3）。
 * <p>
 * 根据熔断器状态自动选择 LLM / Embedding Provider，
 * 替代 P2 手动切换配置的方式。
 * </p>
 *
 * <p><b>决策逻辑：</b></p>
 * <table>
 *   <tr><th>熔断器状态</th><th>决策</th><th>说明</th></tr>
 *   <tr><td>CLOSED</td><td>使用首选 Provider</td><td>正常状态</td></tr>
 *   <tr><td>OPEN</td><td>切换到备用 Provider</td><td>首选 Provider 连续失败</td></tr>
 *   <tr><td>HALF_OPEN</td><td>尝试首选 Provider</td><td>探测首选是否恢复</td></tr>
 * </table>
 *
 * <p>{@code auto-failover=false} 时直接使用首选 Provider，不做切换（用于调试）。</p>
 *
 * @see LLMCircuitBreaker
 * @see ProviderFactory
 */
@Slf4j
@Component
public class ProviderDecisionEngine {

    @Autowired
    private LLMCircuitBreaker circuitBreaker;

    /** 首选 LLM Provider */
    @Value("${agent-qr.provider.preferred-llm:deepseek}")
    private String preferredLLM;

    /** 是否启用自动故障切换 */
    @Value("${agent-qr.provider.auto-failover:true}")
    private boolean autoFailover;

    /**
     * 根据熔断器状态决策当前应使用的 LLM Provider。
     *
     * @return Provider 类型标识（如 "deepseek"、"ollama"）
     */
    public String decideLLMProvider() {
        return decideProvider(preferredLLM, "LLM");
    }

    /**
     * 决策当前应使用的 Embedding Provider。
     * <p>
     * 仅 Ollama Embedding 可用，直接返回固定值——这是有意设计（本地方案 + DeepSeek 不提供
     * Embedding API），不是待修的缺陷。
     * </p>
     * <p>
     * 批次 07 · 任务 7.2.8：原先与此语义重叠的 {@code agent-qr.provider.preferred-embedding}
     * 配置键已删除——它不参与本方法（改了没反应）。Embedding Provider 的<b>生效开关</b>只有
     * {@code embedding.provider}（被 {@code ProviderFactory} 读取）。
     * </p>
     *
     * @return 固定返回 "ollama"
     */
    public String decideEmbeddingProvider() {
        return "ollama";
    }

    /**
     * 通用 Provider 决策逻辑。
     *
     * @param preferred    首选 Provider 类型
     * @param providerType 日志标签（"LLM" 或 "Embedding"）
     * @return 决策后的 Provider 类型
     */
    private String decideProvider(String preferred, String providerType) {
        if (!autoFailover) {
            log.debug("auto-failover=false，直接使用首选 {} Provider: {}", providerType, preferred);
            return preferred;
        }

        try {
            LLMCircuitBreaker.State state = circuitBreaker.getState();
            return switch (state) {
                case CLOSED -> {
                    log.debug("熔断器关闭，使用首选 {} Provider: {}", providerType, preferred);
                    yield preferred;
                }
                case OPEN -> {
                    String fallback = "deepseek".equals(preferred) ? "ollama" : "deepseek";
                    // 避免回退到与首选相同的 Provider
                    if (fallback.equals(preferred)) {
                        log.warn("熔断器打开但备选 Provider 与首选相同 ({}={})，强制使用备选",
                                preferred, fallback);
                    }
                    log.warn("熔断器打开，切换 {} Provider: {} → {}", providerType, preferred, fallback);
                    yield fallback;
                }
                case HALF_OPEN -> {
                    log.info("熔断器半开，尝试首选 {} Provider: {}", providerType, preferred);
                    yield preferred;
                }
            };
        } catch (Exception e) {
            log.warn("Provider 决策异常，降级使用首选 {} Provider: {}", providerType, preferred, e);
            return preferred;
        }
    }

    /**
     * 获取首选 LLM Provider 配置值。
     *
     * @return 首选 LLM Provider 类型
     */
    public String getPreferredLLM() {
        return preferredLLM;
    }

    /**
     * 是否启用了自动故障切换。
     *
     * @return {@code true} 表示启用
     */
    public boolean isAutoFailover() {
        return autoFailover;
    }
}
