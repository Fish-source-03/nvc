package org.example.agent_qr.web.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket 服务端配置（批次 10 · 任务 10.2.1，问题 34）。
 * <p>
 * 修复前：前端 {@code useWebSocket.ts} 有完整的 STOMP 客户端（连接/订阅/发送/降级），
 * 后端<b>什么都没有</b>——无 {@code @EnableWebSocketMessageBroker}、无端点、无依赖，
 * {@code SecurityConfig} 里只有一条 {@code /ws/** → permitAll()} 的"悬空放行"。
 * 前端连接必然失败，实际靠 SSE 降级掩盖。
 * </p>
 * <h3>端点与目的地约定</h3>
 * <ul>
 *   <li>端点：{@code /ws}（SockJS + 原生 WebSocket 双支持）。
 *       前端 {@code new SockJS(`${VITE_WS_URL}/ws?token=<jwt>`)}；</li>
 *   <li>用户目的地：{@code /user/queue/...}（服务端用
 *       {@code SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/…", payload)} 推送，
 *       <b>按会话隔离</b>，避免用广播目的地推送含用户数据的消息造成串号）；</li>
 *   <li>运维频道：{@code /topic/ops.alerts}（仅管理员可订阅，见
 *       {@link StompAuthChannelInterceptor}）；</li>
 *   <li>应用目的地前缀：{@code /app}（客户端 {@code @MessageMapping} 用，当前无服务端处理方法）。</li>
 * </ul>
 *
 * @author agent-qr
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    /** 端点路径（与前端约定一致：{@code ${VITE_WS_URL}/ws}） */
    public static final String ENDPOINT = "/ws";

    /** 允许的来源（与 SecurityConfig 的 CORS 口径一致；握手由 JWT 把关，来源不再是安全边界） */
    @Value("${agent-qr.websocket.allowed-origin-patterns:*}")
    private String allowedOriginPatterns;

    /**
     * 消息代理配置：内置简单代理 + 用户目的地前缀。
     *
     * @param registry 消息代理注册表
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic：广播（运维告警等非用户数据）；/queue：点对点
        registry.enableSimpleBroker("/topic", "/queue");
        // 客户端发往服务端的 @MessageMapping 前缀（保留能力，当前无服务端处理方法）
        registry.setApplicationDestinationPrefixes("/app");
        // 用户目的地前缀：客户端订阅 /user/queue/…，服务端按会话推送
        registry.setUserDestinationPrefix("/user");
        log.info("STOMP 消息代理已启用: broker=/topic,/queue, appPrefix=/app, userPrefix=/user");
    }

    /**
     * 注册 STOMP 端点。
     * <p>
     * 同时注册<b>原生 WebSocket</b>（Java/非浏览器客户端与联调用）与 <b>SockJS</b>
     * （前端使用，支持 XHR 降级传输）。
     * </p>
     *
     * @param registry 端点注册表
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = parseOrigins();
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns(origins);
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns(origins).withSockJS();
        log.info("STOMP 端点已注册: {}（原生 WebSocket + SockJS）", ENDPOINT);
    }

    /**
     * 客户端入站通道挂上鉴权拦截器（CONNECT 校验 JWT、SUBSCRIBE 校验运维频道权限）。
     *
     * @param registration 通道注册
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }

    /**
     * 解析允许的来源模式（逗号分隔）。
     *
     * @return 来源模式数组
     */
    private String[] parseOrigins() {
        if (allowedOriginPatterns == null || allowedOriginPatterns.isBlank()) {
            return new String[]{"*"};
        }
        return allowedOriginPatterns.split(",");
    }
}
