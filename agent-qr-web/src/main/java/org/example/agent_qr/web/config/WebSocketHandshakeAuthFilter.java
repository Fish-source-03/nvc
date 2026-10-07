package org.example.agent_qr.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.auth.util.JwtUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * WebSocket 握手阶段 JWT 鉴权过滤器（批次 10 · 任务 10.2.2，问题 34）。
 * <p>
 * <b>为什么还需要它</b>：浏览器 WebSocket / SockJS <b>无法自定义请求头</b>，
 * 而 {@code SecurityConfig} 已把 {@code /ws/**} 从 {@code permitAll()} 收敛为
 * {@code authenticated()}——握手请求必须能证明身份。因此前端把 JWT 作为查询参数
 * {@code ?token=<jwt>} 附在握手 URL 上（SockJS 会把它透传到 /info 与各传输请求），
 * 本过滤器校验后写入 SecurityContext，供授权规则判定。
 * </p>
 * <p>
 * 这是<b>第一道</b>校验；STOMP 通道层的 {@link StompAuthChannelInterceptor} 在 CONNECT
 * 阶段用 {@code Authorization} 原生头做<b>第二道</b>校验（令牌不出现在 URL 的场景，
 * 如 Java/原生 WebSocket 客户端）。任一道失败都拒绝连接。
 * </p>
 * <p>
 * 注意：过滤器不做"放行"兜底——没有有效令牌就<b>不设置</b>认证，
 * 由 Spring Security 的授权规则（{@code /ws/** → authenticated()}）拒绝该握手。
 * </p>
 * <p>
 * <b>为什么由 {@code SecurityConfig} 以 {@code @Bean} 方式创建、而不是 {@code @Component} 扫描</b>：
 * 它的唯一依赖是 {@code JwtUtil}，把它交给 SecurityConfig 装配可以让所有导入 SecurityConfig
 * 的 WebMvc 切片测试（问题 03/06/41 的既有用例）自动获得该 Bean，无需逐处补声明。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@RequiredArgsConstructor
public class WebSocketHandshakeAuthFilter extends OncePerRequestFilter {

    /** 握手令牌查询参数名（前端 useWebSocket.ts 使用） */
    public static final String TOKEN_PARAM = "token";

    /** 兼容的备用查询参数名 */
    public static final String TOKEN_PARAM_ALT = "access_token";

    /** WebSocket 端点路径前缀 */
    private static final String WS_PATH = "/ws";

    private final JwtUtil jwtUtil;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        return !(WS_PATH.equals(path) || path.startsWith(WS_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveToken(request);
            if (token != null && !token.isBlank()) {
                if (jwtUtil.validateToken(token)) {
                    UserPrincipal principal = jwtUtil.parseUserPrincipal(token);
                    if (principal != null && principal.getUserId() != null) {
                        Authentication authentication = WebSocketUserPrincipal.toAuthentication(principal);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        log.debug("WebSocket 握手令牌校验通过: userId={}, path={}",
                                principal.getUserId(), pathWithinApplication(request));
                    }
                } else {
                    // 不打印令牌本身；仅记录"校验失败"这一事实
                    log.warn("WebSocket 握手令牌无效或已过期，握手将被拒绝: path={}",
                            pathWithinApplication(request));
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 解析握手令牌：查询参数优先，其次 {@code Authorization} 头（非浏览器客户端）。
     *
     * @param request 请求
     * @return 令牌字符串；不存在返回 null
     */
    private String resolveToken(HttpServletRequest request) {
        String token = request.getParameter(TOKEN_PARAM);
        if (token == null || token.isBlank()) {
            token = request.getParameter(TOKEN_PARAM_ALT);
        }
        if (token == null || token.isBlank()) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith(StompAuthChannelInterceptor.BEARER_PREFIX)) {
                token = header.substring(StompAuthChannelInterceptor.BEARER_PREFIX.length()).trim();
            }
        }
        return token;
    }

    /**
     * 取"去除 contextPath 后的请求路径"。
     *
     * @param request 请求
     * @return 应用内路径
     */
    private String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri != null && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri == null ? "" : uri;
    }
}
