package org.example.agent_qr.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.filter.JwtAuthenticationFilter;
import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.auth.handler.AbacAccessDeniedHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 安全配置（P3 扩展）。
 * <p>
 * P2 新增：@EnableMethodSecurity(prePostEnabled = true) 启用 ABAC 注解、
 * 更新路由表添加 /api/auth/refresh 和 /api/catalog/**。
 * </p>
 * <p>
 * P3 新增：WebSocket 端点（{@code /ws/**}）。
 * </p>
 * <p>
 * <b>批次 10 · 任务 10.2.2（问题 34）</b>：原实现是 {@code /ws/** → permitAll()} 的"悬空放行"
 * （无端点、无鉴权）；现收敛为 {@code authenticated()}，握手令牌经
 * {@link WebSocketHandshakeAuthFilter}（{@code ?token=} 查询参数）解析，
 * STOMP CONNECT 阶段再由 {@code StompAuthChannelInterceptor} 用 {@code Authorization} 头复核。
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Slf4j
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtFilter;

    /** 页面登录 JWT 工具（WebSocket 握手令牌校验复用同一套签名/有效期规则） */
    @Autowired
    private JwtUtil jwtUtil;

    /** ★ 问题 41：过滤器链层面的 403 复用 ABAC 处理器的审计与响应结构。 */
    @Autowired
    private AbacAccessDeniedHandler abacAccessDeniedHandler;

    /** 统一使用应用内 ObjectMapper 序列化 Result，避免手写 JSON。 */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * ★ 批次 10 · 任务 10.2.2（问题 34）：WebSocket 握手令牌解析过滤器。
     * <p>浏览器 WebSocket/SockJS 无法设置请求头，握手令牌只能走 {@code ?token=} 查询参数；
     * 该过滤器校验通过后写入 SecurityContext，供 {@code /ws/** → authenticated()} 判定。</p>
     *
     * @param jwtUtil JWT 工具
     * @return 过滤器
     */
    @Bean
    public WebSocketHandshakeAuthFilter webSocketHandshakeAuthFilter(JwtUtil jwtUtil) {
        return new WebSocketHandshakeAuthFilter(jwtUtil);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           WebSocketHandshakeAuthFilter webSocketHandshakeAuthFilter) throws Exception {
        http
                .cors(cors -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.accessDeniedHandler(this::handleAccessDenied))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh").permitAll()
                        // ★ 批次 10 · 任务 10.2.2（问题 34）：原先此处是
                        //   requestMatchers("/ws/**", "/ws/info").permitAll() —— 一条"悬空放行"
                        //   （端点当时根本不存在），若将来补上端点就会成为未鉴权入口。
                        //   现收敛为 authenticated()：握手请求须携带有效 JWT
                        //   （浏览器无法自定义头 → 令牌走 ?token= 查询参数，由下方
                        //    WebSocketHandshakeAuthFilter 校验；STOMP CONNECT 阶段再由
                        //    StompAuthChannelInterceptor 用 Authorization 头复核）。
                        .requestMatchers("/ws/**", "/ws").authenticated()
                        // ★ 问题 06：设计 §3.2.12 要求 /api/admin/** 为 hasRole("ADMIN")，
                        //   原实现放宽为 authenticated()，任何登录用户都能拉取全站用户数据。
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/knowledge/**").authenticated()
                        .requestMatchers("/api/chat/**").authenticated()
                        .requestMatchers("/api/catalog/**").authenticated()
                        .requestMatchers("/api/statistics/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // ★ 批次 10 · 任务 10.2.2：WebSocket 握手令牌（?token=）解析，必须位于
                //   SecurityContextHolderFilter 之后、AuthorizationFilter 之前，
                //   否则认证会被无状态上下文清除。
                .addFilterBefore(webSocketHandshakeAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * ★ 问题 41：过滤器链层面的访问拒绝处理（如 {@code hasRole("ADMIN")} 路由规则）。
     * <p>
     * 默认实现只返回空的 403（无响应体），前端无法区分"权限不足"与其他错误。
     * 这里复用 {@link AbacAccessDeniedHandler} 的审计字段与统一 {@code Result} 结构，
     * 使过滤器链拒绝与方法级 {@code @PreAuthorize} 拒绝的响应完全一致。
     * </p>
     */
    private void handleAccessDenied(HttpServletRequest request, HttpServletResponse response,
                                    AccessDeniedException accessDeniedException) throws IOException {
        log.warn("ABAC 访问拒绝(过滤器链): {}",
                abacAccessDeniedHandler.buildAuditLog(request, accessDeniedException));
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                abacAccessDeniedHandler.buildDeniedResult(accessDeniedException)));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
