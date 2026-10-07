package org.example.agent_qr.web.config;

import lombok.Getter;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * STOMP 会话的用户主体（批次 10 · 任务 10.2，问题 34）。
 * <p>
 * <b>为什么 {@link #getName()} 返回 userId 而不是用户名</b>：
 * </p>
 * <ol>
 *   <li>服务端推送使用 {@code SimpMessagingTemplate.convertAndSendToUser(user, …)}，
 *       其中的 {@code user} 必须与 CONNECT 时 {@code accessor.setUser(principal)}
 *       的 {@code principal.getName()} <b>逐字符一致</b>；
 *       业务事件（{@code DocumentUploadedEvent}/{@code EmbeddingCompletedEvent} 及
 *       {@code kb_document.upload_user_id}）携带的都是 <b>userId</b>，
 *       用它做会话标识无需为每次推送反查用户名；</li>
 *   <li>若直接把 {@code UserPrincipal}（Lombok {@code @Data}）当 principal，
 *       {@code AbstractAuthenticationToken.getName()} 会退化为 {@code toString()}，
 *       得到一长串字段拼接值——推送与订阅两侧无法对齐。</li>
 * </ol>
 * <p>
 * 实现 {@link Principal} 后，{@code AbstractAuthenticationToken} 会取
 * {@code principal.getName()} 作为用户标识（Spring Security 的优先级：UserDetails →
 * AuthenticatedPrincipal → Principal），因此两端口径天然一致。
 * </p>
 *
 * @author agent-qr
 */
@Getter
public class WebSocketUserPrincipal implements Principal {

    /** 用户 ID（会话标识） */
    private final Long userId;

    /** 用户名（仅审计日志用） */
    private final String username;

    /** 角色：admin / user */
    private final String role;

    /**
     * 构造 STOMP 会话主体。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param role     角色
     */
    public WebSocketUserPrincipal(Long userId, String username, String role) {
        this.userId = userId;
        this.username = username;
        this.role = role;
    }

    /**
     * 会话标识 = userId 字符串（见类注释）。
     *
     * @return userId 的字符串形式
     */
    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    /**
     * 是否为管理员。
     *
     * @return true 表示角色为 admin
     */
    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }

    /**
     * 由 JWT 解析出的 {@link UserPrincipal} 构建 Spring Security 认证对象。
     * <p>
     * 权限口径与 {@code JwtAuthenticationFilter} 保持一致：角色 + 域（{@code DOMAIN_xxx}）。
     * 握手过滤器与 STOMP CONNECT 拦截器共用本方法，避免两处口径漂移。
     * </p>
     *
     * @param principal JWT 解析结果
     * @return 认证对象（principal 为 {@link WebSocketUserPrincipal}）
     */
    public static UsernamePasswordAuthenticationToken toAuthentication(UserPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (principal.getRole() != null && !principal.getRole().isBlank()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + principal.getRole().toUpperCase()));
        }
        if (principal.getAllowedDomains() != null) {
            for (String domain : principal.getAllowedDomains()) {
                if (domain != null && !domain.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("DOMAIN_" + domain));
                }
            }
        }
        WebSocketUserPrincipal stompPrincipal =
                new WebSocketUserPrincipal(principal.getUserId(), principal.getUsername(), principal.getRole());
        return new UsernamePasswordAuthenticationToken(stompPrincipal, null, authorities);
    }
}
