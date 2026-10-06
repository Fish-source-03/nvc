package org.example.agent_qr.web.config;

import org.example.agent_qr.catalog.service.KnowledgeCatalogService;
import org.example.agent_qr.datasource.service.DataSourceService;
import org.example.agent_qr.knowledge.service.DocumentCommandService;
import org.example.agent_qr.knowledge.service.DocumentQueryService;
import org.example.agent_qr.statistics.service.StatisticsQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CQRS 读方法约定测试（批次 02 · 任务 2.3，问题 05 / 设计 §8.13.3）。
 * <p>
 * 拦截的核心缺陷：全仓库零处 {@code @Transactional(readOnly = true)}，
 * 读写分离基础设施空转（ThreadLocal 恒为 false → 恒路由写库）。
 * </p>
 * <p>
 * 之所以做"注解反射"而非启动 Spring 上下文：CQRS 路由只在方法带 {@code @Transactional} 时
 * 才被 {@code @annotation} 切点匹配，注解一旦被删/被挪到类上，运行时就会静默退回"全走写库"，
 * 这类回归只有约定测试能拦住。
 * </p>
 *
 * @author agent-qr
 */
class CqrsQueryServiceReadOnlyConventionTest {

    /** 查询侧 Service → 必须标注 {@code @Transactional(readOnly = true)} 的读方法 */
    private static final Map<Class<?>, List<String>> READ_ONLY_METHODS = new LinkedHashMap<>();

    /** 写侧 Service → 严禁标注 {@code readOnly = true} 的写方法 */
    private static final Map<Class<?>, List<String>> WRITE_METHODS = new LinkedHashMap<>();

    static {
        READ_ONLY_METHODS.put(DocumentQueryService.class,
                List.of("listDocuments", "getDocument", "getDocumentWithAbac", "getStatus", "getChunks"));
        READ_ONLY_METHODS.put(StatisticsQueryService.class,
                List.of("getDashboard"));
        READ_ONLY_METHODS.put(DataSourceService.class,
                List.of("getById", "listAll", "listActive", "listByPage", "getSyncHistory"));
        READ_ONLY_METHODS.put(KnowledgeCatalogService.class,
                List.of("getCatalogTree", "getStats"));

        WRITE_METHODS.put(DocumentCommandService.class,
                List.of("uploadDocument", "requestDeleteDocument", "deleteDocument"));
        WRITE_METHODS.put(DataSourceService.class,
                List.of("create", "update", "delete", "triggerSync"));
    }

    @Test
    @DisplayName("查询侧方法必须标注 @Transactional(readOnly = true)，且至少覆盖 3 个查询侧 Service")
    void queryMethods_shouldBeAnnotatedReadOnly() {
        assertThat(READ_ONLY_METHODS.keySet())
                .as("验收标准要求 @Transactional(readOnly = true) 至少出现在 3 个查询侧 Service 中")
                .hasSizeGreaterThanOrEqualTo(3);

        for (Map.Entry<Class<?>, List<String>> entry : READ_ONLY_METHODS.entrySet()) {
            Class<?> serviceType = entry.getKey();
            for (String methodName : entry.getValue()) {
                Transactional transactional = declaredMethod(serviceType, methodName)
                        .getAnnotation(Transactional.class);

                assertThat(transactional)
                        .as("%s#%s 应标注 @Transactional（切面只在方法级注解上生效）",
                                serviceType.getSimpleName(), methodName)
                        .isNotNull();
                assertThat(transactional.readOnly())
                        .as("%s#%s 是查询方法，readOnly 应为 true，否则读库永远不会被命中",
                                serviceType.getSimpleName(), methodName)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("写方法严禁标注 readOnly = true（会被路由到读库，导致写入丢失）")
    void writeMethods_shouldNotBeMarkedReadOnly() {
        for (Map.Entry<Class<?>, List<String>> entry : WRITE_METHODS.entrySet()) {
            Class<?> serviceType = entry.getKey();
            for (String methodName : entry.getValue()) {
                Transactional transactional = declaredMethod(serviceType, methodName)
                        .getAnnotation(Transactional.class);

                if (transactional == null) {
                    // 未开启事务的写方法同样不会被切面路由到读库，属于安全状态
                    continue;
                }
                assertThat(transactional.readOnly())
                        .as("%s#%s 是写方法，不得标注 readOnly = true",
                                serviceType.getSimpleName(), methodName)
                        .isFalse();
            }
        }
    }

    private static Method declaredMethod(Class<?> serviceType, String methodName) {
        return List.of(serviceType.getDeclaredMethods()).stream()
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        serviceType.getSimpleName() + " 中不存在方法 " + methodName + "，约定测试已失效"));
    }
}
