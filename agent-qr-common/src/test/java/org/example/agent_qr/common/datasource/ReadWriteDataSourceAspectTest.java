package org.example.agent_qr.common.datasource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * {@link ReadWriteDataSourceAspect} 切面测试（批次 02 · 任务 2.3，问题 05）。
 * <p>
 * 验证设计 §8.13.3 约定的生效链路：
 * {@code @Transactional(readOnly)} → 切面写入 ThreadLocal → {@code determineCurrentLookupKey()}
 * 返回 {@code "read"} / {@code "write"} → {@code finally} 中清理 ThreadLocal。
 * </p>
 * <p>
 * 用例直接取用<b>方法上真实的 {@link Transactional} 注解</b>（而非 mock 注解），
 * 以保证"注解必须标在方法上，{@code @annotation} 切点才匹配"这一约束被测试覆盖。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadWriteDataSourceAspectTest {

    private final ReadWriteDataSourceAspect aspect = new ReadWriteDataSourceAspect();

    private final ReadWriteRoutingDataSource routingDataSource =
            new ReadWriteRoutingDataSource(mock(DataSource.class), mock(DataSource.class));

    @AfterEach
    void clearThreadLocal() {
        ReadWriteRoutingDataSource.clear();
    }

    @Test
    @DisplayName("标注 readOnly = true 的方法：执行期间路由键应为 read")
    void routeDataSource_shouldRouteToRead_whenMethodAnnotatedReadOnly() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        AtomicReference<Object> lookupKeyInsideMethod = new AtomicReference<>();
        doAnswer(invocation -> {
            lookupKeyInsideMethod.set(routingDataSource.determineCurrentLookupKey());
            return "read-result";
        }).when(pjp).proceed();

        Object result = aspect.routeDataSource(pjp, transactionOf("readOnlyQuery"));

        assertThat(lookupKeyInsideMethod.get()).isEqualTo("read");
        assertThat(result).isEqualTo("read-result");
    }

    @Test
    @DisplayName("未标注 readOnly 的方法：执行期间路由键应为 write")
    void routeDataSource_shouldRouteToWrite_whenMethodAnnotatedWithDefaultTransaction() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        AtomicReference<Object> lookupKeyInsideMethod = new AtomicReference<>();
        doAnswer(invocation -> {
            lookupKeyInsideMethod.set(routingDataSource.determineCurrentLookupKey());
            return "write-result";
        }).when(pjp).proceed();

        aspect.routeDataSource(pjp, transactionOf("writeOperation"));

        assertThat(lookupKeyInsideMethod.get()).isEqualTo("write");
    }

    @Test
    @DisplayName("切面返回后应清理 ThreadLocal，避免线程池复用导致后续写请求被误路由到读库")
    void routeDataSource_shouldClearThreadLocal_afterSuccessfulInvocation() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        doAnswer(invocation -> "ok").when(pjp).proceed();

        aspect.routeDataSource(pjp, transactionOf("readOnlyQuery"));

        assertThat(routingDataSource.determineCurrentLookupKey())
                .as("切面 finally 中的 clear() 未执行时会残留 read 标志")
                .isEqualTo("write");
    }

    @Test
    @DisplayName("目标方法抛异常时也必须清理 ThreadLocal")
    void routeDataSource_shouldClearThreadLocal_whenTargetThrows() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        doThrow(new IllegalStateException("目标方法失败")).when(pjp).proceed();

        assertThatThrownBy(() -> aspect.routeDataSource(pjp, transactionOf("readOnlyQuery")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("write");
    }

    /** 取用夹具方法上真实的 {@link Transactional} 注解。 */
    private static Transactional transactionOf(String methodName) throws NoSuchMethodException {
        Method method = AnnotatedFixture.class.getDeclaredMethod(methodName);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional)
                .as("夹具方法 %s 必须标注 @Transactional，否则 @annotation 切点不会匹配", methodName)
                .isNotNull();
        return transactional;
    }

    /** 注解夹具：对应生产代码中查询侧 / 写侧 Service 方法的两种标注方式。 */
    @SuppressWarnings("unused")
    static class AnnotatedFixture {

        @Transactional(readOnly = true)
        public String readOnlyQuery() {
            return "read-only";
        }

        @Transactional
        public String writeOperation() {
            return "write";
        }
    }
}
