package org.example.agent_qr.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置。
 * <p>
 * 提供应用级本地缓存 Bean，用于 LLM 响应缓存等场景。
 * 上限与过期时间由配置键驱动（批次 10 · 任务 10.5.1，问题 38）：
 * </p>
 * <ul>
 *   <li>{@code agent-qr.cache.max-size} —— 最大缓存条数（默认 10000）；</li>
 *   <li>{@code agent-qr.cache.ttl-hours} —— 写入后过期小时数（默认 1）。</li>
 * </ul>
 * <p>
 * 修复前这两个键在 {@code application-p2.yml} 中已声明但<b>无任何读取点</b>，
 * 此处硬编码 10000 / 1h —— 改配置不会有任何效果（误导性死配置）。
 * </p>
 *
 * @author agent-qr
 */
@Configuration
public class CaffeineConfig {

    /** 最大缓存条数（配置键 agent-qr.cache.max-size） */
    @Value("${agent-qr.cache.max-size:10000}")
    private long maxSize = 10000;

    /** 缓存过期时间（小时，配置键 agent-qr.cache.ttl-hours） */
    @Value("${agent-qr.cache.ttl-hours:1}")
    private long ttlHours = 1;

    /**
     * LLM 响应缓存 Bean。
     * <p>
     * Key 为查询文本的 hash，Value 为 LLM 生成的回答文本。
     * </p>
     *
     * @return Caffeine Cache 实例
     */
    @Bean
    public Cache<String, String> llmResponseCache() {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlHours, TimeUnit.HOURS)
                .build();
    }
}
