package org.example.agent_qr.web.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.auth.util.JwtUtil;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 鉴权拦截器（批次 10 · 任务 10.2.2，问题 34）。
 * <p>
 * 这是 WebSocket 通道上的<b>核心安全边界</b>，在 CONNECT 阶段校验 JWT：
 * </p>
 * <ol>
 *   <li><b>CONNECT</b>：从 STOMP 原生头 {@code Authorization: Bearer <jwt>} 取令牌，
 *       经 {@link JwtUtil} 验签/验期后构建 {@link WebSocketUserPrincipal}
 *       （会话标识 = userId，见该类注释）并 {@code accessor.setUser(...)}；
 *       缺失/无效/过期一律抛 {@link MessagingException} → 连接被拒绝（客户端收到 ERROR 帧后断开）；</li>
 *   <li><b>SUBSCRIBE</b>：
 *       <ul>
 *         <li>运维频道（{@value #OPS_TOPIC} 及 {@code /topic/ops*} 前缀）<b>仅管理员可订阅</b>；</li>
 *         <li>显式指定他人用户目的地（{@code /user/{id}/…} 且 {@code id} 不是自己）→ 拒绝，防串号。</li>
 *       </ul>
 *   </li>
 * </ol>
 * <p>
 * 修复前：{@code SecurityConfig} 的 {@code /ws/**} → {@code permitAll()} 是一条"悬空放行"
 * （端点本身都不存在），既无端点也无鉴权。现在 HTTP 握手层（
 * {@link WebSocketHandshakeAuthFilter} + {@code /ws/** → authenticated()}）与 STOMP 通道层
 * 双重校验，任一层失败都拒绝连接。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    /** STOMP 原生头名（与前端 connectHeaders 一致） */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer 前缀 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 运维告警频道（仅管理员可订阅） */
    public static final String OPS_TOPIC = "/topic/ops.alerts";

    /** 运维频道前缀（含 OPS_TOPIC 本身） */
    public static final String OPS_TOPIC_PREFIX = "/topic/ops";

    /** 会话属性键：角色（供后续消息使用） */
    public static final String ATTR_ROLE = "wsRole";

    /** 会话属性键：用户名（仅审计） */
    public static final String ATTR_USERNAME = "wsUsername";

    /** 显式指定用户的订阅目的地：/user/{id}/queue/... */
    private static final Pattern EXPLICIT_USER_DESTINATION =
            Pattern.compile("^/user/([^/]+)/.*$");

    private final JwtUtil jwtUtil;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            return bindUser(message, accessor, authenticate(accessor));
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    /**
     * 把认证结果绑定到会话。
     * <p>
     * 常规路径直接 {@code accessor.setUser(...)}（Spring 的官方用法）；若上游消息头已被冻结
     * （{@code isMutable() == false}），则在<b>可变副本</b>上绑定并重建消息返回——
     * 拦截器允许返回新消息，且会话用户必须出现在发往下游的消息上，否则 {@code /user/**}
     * 投递找不到目标。
     * </p>
     *
     * @param message        原始消息
     * @param accessor       STOMP 头访问器（可能不可变）
     * @param authentication 认证对象
     * @return 绑定用户后的消息
     */
    private Message<?> bindUser(Message<?> message, StompHeaderAccessor accessor,
                                Authentication authentication) {
        if (accessor.isMutable()) {
            accessor.setUser(authentication);
            return message;
        }

        StompHeaderAccessor mutable = StompHeaderAccessor.create(StompCommand.CONNECT);
        mutable.setSessionId(accessor.getSessionId());
        Map<String, Object> attributes = accessor.getSessionAttributes();
        mutable.setSessionAttributes(attributes == null ? new HashMap<>() : new HashMap<>(attributes));
        mutable.setUser(authentication);
        return MessageBuilder.createMessage(message.getPayload(), mutable.getMessageHeaders());
    }

    /**
     * CONNECT 阶段鉴权：校验 JWT 并构建认证对象（绑定动作见 {@link #bindUser}）。
     *
     * @param accessor STOMP 头访问器
     * @return 认证对象
     * @throws MessagingException 缺少令牌 / 令牌无效或过期 / 令牌信息不完整
     */
    private Authentication authenticate(StompHeaderAccessor accessor) {
        String rawHeader = resolveAuthorizationHeader(accessor);
        if (rawHeader == null || rawHeader.isBlank()) {
            throw new MessagingException("WebSocket 连接被拒绝：缺少 Authorization（Bearer）头");
        }
        if (!rawHeader.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("WebSocket 连接被拒绝：Authorization 头不是 Bearer 格式");
        }
        String token = rawHeader.substring(BEARER_PREFIX.length()).trim();
        if (!jwtUtil.validateToken(token)) {
            throw new MessagingException("WebSocket 连接被拒绝：JWT 无效或已过期");
        }

        UserPrincipal principal = jwtUtil.parseUserPrincipal(token);
        if (principal == null || principal.getUserId() == null) {
            throw new MessagingException("WebSocket 连接被拒绝：JWT 缺少用户标识");
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put(ATTR_ROLE, principal.getRole());
            sessionAttributes.put(ATTR_USERNAME, principal.getUsername());
        }
        // 只记 userId/角色，不回显令牌
        log.info("WebSocket 连接已鉴权: userId={}, username={}, role={}",
                principal.getUserId(), principal.getUsername(), principal.getRole());
        return WebSocketUserPrincipal.toAuthentication(principal);
    }

    /**
     * SUBSCRIBE 阶段授权：运维频道仅管理员；禁止订阅他人的用户目的地。
     *
     * @param accessor STOMP 头访问器
     * @throws MessagingException 无权订阅
     */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            return;
        }

        if (OPS_TOPIC.equals(destination) || destination.startsWith(OPS_TOPIC_PREFIX)) {
            if (!isAdmin(accessor)) {
                log.warn("拒绝非管理员订阅运维频道: destination={}, user={}", destination, userName(accessor));
                throw new MessagingException("订阅运维频道需要管理员权限: " + destination);
            }
            return;
        }

        Matcher matcher = EXPLICIT_USER_DESTINATION.matcher(destination);
        if (matcher.matches()) {
            String targetUser = matcher.group(1);
            // /user/queue/... 与 /user/topic/... 是 Spring 的简写形式（解析到当前会话用户），
            // 只有 /user/{显式用户标识}/... 才需要比对
            if ("queue".equals(targetUser) || "topic".equals(targetUser)) {
                return;
            }
            String self = userName(accessor);
            if (self == null || !self.equals(targetUser)) {
                log.warn("拒绝跨用户的订阅请求: destination={}, user={}", destination, self);
                throw new MessagingException("禁止订阅其他用户的目的地: " + destination);
            }
        }
    }

    /**
     * 从 STOMP 原生头解析 Authorization（大小写不敏感）。
     *
     * @param accessor STOMP 头访问器
     * @return 头值；不存在返回 null
     */
    private String resolveAuthorizationHeader(StompHeaderAccessor accessor) {
        List<String> values = accessor.getNativeHeader(AUTHORIZATION_HEADER);
        if (values == null || values.isEmpty()) {
            values = accessor.getNativeHeader("authorization");
        }
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * 当前会话是否管理员。
     *
     * @param accessor STOMP 头访问器
     * @return true 表示具有 ROLE_ADMIN
     */
    private boolean isAdmin(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof org.springframework.security.core.Authentication authentication) {
            return authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        }
        Map<String, Object> attributes = accessor.getSessionAttributes();
        Object role = attributes == null ? null : attributes.get(ATTR_ROLE);
        return role != null && "admin".equalsIgnoreCase(role.toString());
    }

    /**
     * 当前会话用户标识（= userId 字符串）。
     *
     * @param accessor STOMP 头访问器
     * @return 用户标识；未认证返回 null
     */
    private String userName(StompHeaderAccessor accessor) {
        return accessor.getUser() == null ? null : accessor.getUser().getName();
    }
}
