package org.example.agent_qr.web.security;

import org.example.agent_qr.auth.handler.AbacAccessDeniedHandler;
import org.example.agent_qr.web.config.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code AccessDeniedException} 处理链路契约测试（批次 03 · 任务 3.1，问题 41）。
 * <p>
 * 拦截的核心缺陷：{@link GlobalExceptionHandler} 与 {@link AbacAccessDeniedHandler}
 * 都声明了 {@code @ExceptionHandler(AccessDeniedException.class)} 且均无 {@code @Order}，
 * 生效者取决于 Bean 注册顺序——ABAC 拒绝可能被兜底 {@code Exception} 处理器抢走，
 * 返回 500 / 裸 Map，前端把它显示为"网络连接失败"。
 * </p>
 * <p>
 * 本测试用真实的 Spring MVC 消息转换与 HandlerExceptionResolver 链路验证：
 * 控制器抛出的 {@link AccessDeniedException} 一律得到 403 + 统一 {@code Result}。
 * 两个通知类同时注册，证明"唯一处理者 + 优先级"在真实解析器上成立。
 * </p>
 * <p>
 * 注：不使用应用自身的 {@code AgentQrApplication} 作为上下文（其
 * {@code @ComponentScan} 会拉起全量组件，需要数据库等基础设施），
 * 而是用最小切片上下文，只注册两个通知类与探针控制器。
 * </p>
 *
 * @author agent-qr
 */
@WebMvcTest
@ContextConfiguration(classes = AccessDeniedResponseTest.TestApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class AccessDeniedResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("AccessDeniedException 返回 403 + 统一 Result，不被兜底 Exception 处理器改为 500")
    void accessDenied_shouldReturn403WithUnifiedResult() throws Exception {
        mockMvc.perform(get("/test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message", startsWith("权限不足")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("全仓库仅有一个 @ExceptionHandler(AccessDeniedException.class)，杜绝争抢")
    void repository_shouldDeclareExactlyOneAccessDeniedHandler() throws Exception {
        List<String> handlers = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestControllerAdvice.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(ControllerAdvice.class));
        Set<BeanDefinition> advices = scanner.findCandidateComponents("org.example.agent_qr");

        for (BeanDefinition advice : advices) {
            Class<?> adviceClass = Class.forName(advice.getBeanClassName());
            for (Method method : adviceClass.getDeclaredMethods()) {
                ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
                if (annotation != null && Arrays.asList(annotation.value()).contains(AccessDeniedException.class)) {
                    handlers.add(adviceClass.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(advices).as("控制器通知类应被扫描到").isNotEmpty();
        assertThat(handlers)
                .as("AccessDeniedException 必须只有一个处理者（避免两个 @RestControllerAdvice 争抢）")
                .containsExactly("AbacAccessDeniedHandler#handleAccessDenied");
    }

    /**
     * 最小切片上下文：只装配两个通知类与探针控制器。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        /**
         * ★ 故意先注册带兜底 {@code Exception} 处理器的 {@link GlobalExceptionHandler}：
         * 若优先级只取决于注册顺序，{@code AccessDeniedException} 会被兜底分支解析为 500；
         * 只有 {@code @Order(HIGHEST_PRECEDENCE)} 真正生效时，本测试才会得到 403。
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        AbacAccessDeniedHandler abacAccessDeniedHandler() {
            return new AbacAccessDeniedHandler();
        }

        @Bean
        DeniedProbeController deniedProbeController() {
            return new DeniedProbeController();
        }
    }

    /**
     * 探针控制器：直接抛出 {@link AccessDeniedException} 以触发通知链。
     */
    @RestController
    static class DeniedProbeController {

        @GetMapping("/test/denied")
        public String denied() {
            throw new AccessDeniedException("Access Denied");
        }
    }
}
