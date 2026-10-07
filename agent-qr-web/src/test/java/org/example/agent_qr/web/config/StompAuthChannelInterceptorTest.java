package org.example.agent_qr.web.config;

import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STOMP 鉴权拦截器测试（批次 10 · 任务 10.2.2 / 10.2.4，问题 34）。
 * <p>
 * 覆盖补充测试要求的四条安全用例：
 * </p>
 * <ol>
 *   <li>未携带有效 JWT 的握手被拒绝（缺失 / 非 Bearer / 无效 / 过期）；</li>
 *   <li>已认证用户被绑定为会话用户（会话标识 = userId，供 {@code /user/**} 投递使用）；</li>
 *   <li>非管理员无法订阅运维频道 {@code /topic/ops.alerts}；</li>
 *   <li>用户 A 不能订阅用户 B 的目的地（隔离性，防串号）。</li>
 * </ol>
 * <p>
 * 使用<b>真实 {@link JwtUtil}</b>（非 mock）——这样"令牌校验"这一环也是真跑的，
 * 只有签名密钥来自测试属性。
 * </p>
 *
 * @author agent-qr
 */
class StompAuthChannelInterceptorTest {

    private JwtUtil jwtUtil;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        org.springframework.test.util.ReflectionTestUtils.setField(jwtUtil, "secret",
                "agent-qr-test-secret-key-0123456789-abcdefghij");
        org.springframework.test.util.ReflectionTestUtils.setField(jwtUtil, "accessExpiration", 1800L);
        org.springframework.test.util.ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604800L);
        interceptor = new StompAuthChannelInterceptor(jwtUtil);
    }

    // ==================== CONNECT ====================

    @Test
    @DisplayName("★ 未携带 Authorization 头的 CONNECT 被拒绝（连接不建立）")
    void connect_shouldReject_whenTokenMissing() {
        Message<byte[]> message = connectMessage(null);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("缺少 Authorization");
    }

    @Test
    @DisplayName("★ 非 Bearer 格式 / 无效 JWT 的 CONNECT 被拒绝")
    void connect_shouldReject_whenTokenInvalid() {
        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Basic abc"), null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Bearer");

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer not-a-jwt"), null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("JWT 无效或已过期");
    }

    @Test
    @DisplayName("★ 有效 JWT 的 CONNECT 被放行，且会话用户标识 = userId（含角色与域权限）")
    void connect_shouldBindSessionUser_whenTokenValid() {
        StompHeaderAccessor accessor = connectAccessor("Bearer " + token(7L, "chenming", "user"));
        Map<String, Object> sessionAttributes = new HashMap<>();
        accessor.setSessionAttributes(sessionAttributes);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);

        StompHeaderAccessor resultAccessor =
                org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        Authentication authentication = (Authentication) resultAccessor.getUser();
        assertThat(authentication).as("CONNECT 成功后必须绑定用户，/user/** 投递才有目标").isNotNull();
        assertThat(authentication.getName()).as("会话标识 = userId（与 convertAndSendToUser 口径一致）").isEqualTo("7");
        assertThat(authentication.getAuthorities()).extracting("authority")
                .contains("ROLE_USER", "DOMAIN_HR");
        assertThat(sessionAttributes).containsEntry(StompAuthChannelInterceptor.ATTR_ROLE, "user");
    }

    @Test
    @DisplayName("有效 JWT 的 CONNECT（可变消息头，Spring 常规路径）→ 原消息上直接绑定用户")
    void connect_shouldBindUserOnSameMessage_whenHeadersMutable() {
        StompHeaderAccessor accessor = connectAccessor("Bearer " + token(7L, "chenming", "user"));
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        StompHeaderAccessor resultAccessor =
                org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor.getUser()).isNotNull();
        assertThat(resultAccessor.getUser().getName()).isEqualTo("7");
    }

    // ==================== SUBSCRIBE：运维频道 ====================

    @Test
    @DisplayName("★ 非管理员订阅运维频道被拒绝")
    void subscribe_shouldRejectOpsTopic_whenNotAdmin() {
        StompHeaderAccessor accessor = subscribeAccessor(StompAuthChannelInterceptor.OPS_TOPIC,
                token(7L, "chenming", "user"));

        assertThatThrownBy(() -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("需要管理员权限");
    }

    @Test
    @DisplayName("管理员可订阅运维频道")
    void subscribe_shouldAllowOpsTopic_whenAdmin() {
        StompHeaderAccessor accessor = subscribeAccessor(StompAuthChannelInterceptor.OPS_TOPIC,
                token(1L, "admin", "admin"));

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    // ==================== SUBSCRIBE：用户目的地隔离 ====================

    @Test
    @DisplayName("★ 用户 A 不能订阅用户 B 的目的地（隔离性）")
    void subscribe_shouldRejectOtherUserDestination() {
        StompHeaderAccessor accessor = subscribeAccessor("/user/9/queue/documents/progress",
                token(7L, "chenming", "user"));

        assertThatThrownBy(() -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("其他用户");
    }

    @Test
    @DisplayName("用户可订阅自己的用户目的地，也可订阅平台约定的 /user/queue/... 目的地")
    void subscribe_shouldAllowOwnAndGenericUserDestination() {
        Message<byte[]> own = MessageBuilder.createMessage(new byte[0],
                subscribeAccessor("/user/7/queue/documents/progress", token(7L, "chenming", "user"))
                        .getMessageHeaders());
        assertThat(interceptor.preSend(own, null)).isSameAs(own);

        Message<byte[]> generic = MessageBuilder.createMessage(new byte[0],
                subscribeAccessor("/user/queue/documents/progress", token(7L, "chenming", "user"))
                        .getMessageHeaders());
        assertThat(interceptor.preSend(generic, null)).isSameAs(generic);
    }

    // ==================== 辅助 ====================

    private Message<byte[]> connectMessage(String authorizationHeader) {
        return MessageBuilder.createMessage(new byte[0],
                connectAccessor(authorizationHeader).getMessageHeaders());
    }

    private StompHeaderAccessor connectAccessor(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.setNativeHeader(StompAuthChannelInterceptor.AUTHORIZATION_HEADER, authorizationHeader);
        }
        return accessor;
    }

    private StompHeaderAccessor subscribeAccessor(String destination, String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        // 模拟"已通过 CONNECT 鉴权的会话"：用户由 StompSubProtocolHandler 从会话携带到后续帧
        accessor.setUser(authenticationOf(token));
        return accessor;
    }

    /** 按 JWT 构造会话用户（等价于 CONNECT 时拦截器写入的用户）。 */
    private Authentication authenticationOf(String token) {
        return WebSocketUserPrincipal.toAuthentication(jwtUtil.parseUserPrincipal(token));
    }

    private String token(Long userId, String username, String role) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(1);
        user.setDepartment("HR");
        user.setClearanceLevel(2);
        user.setAllowedDomains("HR");
        user.setTitle("manager");
        return jwtUtil.generateAccessToken(user);
    }
}
