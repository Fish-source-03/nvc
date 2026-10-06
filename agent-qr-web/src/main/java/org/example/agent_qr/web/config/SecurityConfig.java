package org.example.agent_qr.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.filter.JwtAuthenticationFilter;
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
 * P3 新增：WebSocket 端点放行（/ws/**）以支持前端 STOMP over WebSocket 通信。
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Slf4j
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtFilter;

    /** ★ 问题 41：过滤器链层面的 403 复用 ABAC 处理器的审计与响应结构。 */
    @Autowired
    private AbacAccessDeniedHandler abacAccessDeniedHandler;

    /** 统一使用应用内 ObjectMapper 序列化 Result，避免手写 JSON。 */
    @Autowired
    private ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.accessDeniedHandler(this::handleAccessDenied))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh").permitAll()
                        .requestMatchers("/ws/**", "/ws/info").permitAll()
                        // ★ 问题 06：设计 §3.2.12 要求 /api/admin/** 为 hasRole("ADMIN")，
                        //   原实现放宽为 authenticated()，任何登录用户都能拉取全站用户数据。
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/knowledge/**").authenticated()
                        .requestMatchers("/api/chat/**").authenticated()
                        .requestMatchers("/api/catalog/**").authenticated()
                        .requestMatchers("/api/statistics/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
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
