package org.example.agent_qr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动类注解契约测试（批次 01 · 任务 1.3，问题 01）。
 * <p>
 * 拦截的核心缺陷：全仓库无 {@code @EnableScheduling}，导致 5 处 {@code @Scheduled}
 * 定时任务在运行期完全不执行（DLQ 死信永不重试、孤儿向量永不扫描等）。
 * </p>
 * <p>
 * 本测试不启动 Spring 上下文：仅做注解反射断言，防止注解与调度点被误删。
 * </p>
 *
 * @author agent-qr
 */
class AgentQrApplicationTest {

    @Test
    @DisplayName("启动类必须标注 @EnableScheduling，否则全部定时任务失活")
    void applicationClass_shouldDeclareEnableScheduling() {
        assertThat(AgentQrApplication.class.getAnnotation(EnableScheduling.class))
                .as("@EnableScheduling 缺失会导致 5 处 @Scheduled 全部不注册")
                .isNotNull();
    }

    @Test
    @DisplayName("启动类应保留 @SpringBootApplication")
    void applicationClass_shouldDeclareSpringBootApplication() {
        assertThat(AgentQrApplication.class.getAnnotation(SpringBootApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("设计文档要求的 5 处 @Scheduled 调度点应全部存在且带注解")
    void fiveScheduledJobs_shouldAllExistAndBeAnnotated() throws Exception {
        assertScheduledMethod("org.example.agent_qr.web.scheduler.DlqRetryScheduler", "retryDeadLetters");
        assertScheduledMethod("org.example.agent_qr.compensation.scanner.OrphanVectorScanner", "scanAndCleanOrphanVectors");
        assertScheduledMethod("org.example.agent_qr.compensation.scanner.DuplicateCleanupScanner", "cleanDuplicateChunks");
        assertScheduledMethod("org.example.agent_qr.catalog.router.DomainRouterV2", "refresh");
        assertScheduledMethod("org.example.agent_qr.rag.router.DomainRouterV2", "refreshDomainEmbeddings");
    }

    private static void assertScheduledMethod(String className, String methodName) throws Exception {
        Class<?> clazz = Class.forName(className);
        Method method = clazz.getDeclaredMethod(methodName);
        assertThat(method.getAnnotation(Scheduled.class))
                .as("%s#%s 应保留 @Scheduled 注解", className, methodName)
                .isNotNull();
    }
}
