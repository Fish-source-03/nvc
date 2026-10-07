package org.example.agent_qr.rag.embedding;

import org.example.agent_qr.rag.provider.EmbeddingProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BatchEmbeddingService} 攒批队列容量参数化测试（批次 05 · 任务 5.2.3 / 问题 21 ③）。
 * <p>
 * 拦截的核心缺陷：队列容量<b>硬编码</b> {@code new LinkedBlockingQueue<>(2000)}，
 * 全仓库无对应配置项，无法按数据源规模调优；队满后生产者会阻塞在 {@code offer(5s)}。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchEmbeddingServiceTest {

    @Mock
    private ProviderFactory providerFactory;

    @Mock
    private EmbeddingDimensionManager dimensionManager;

    @Mock
    private EmbeddingProvider embeddingProvider;

    private BatchEmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new BatchEmbeddingService();
        ReflectionTestUtils.setField(service, "providerFactory", providerFactory);
        ReflectionTestUtils.setField(service, "dimensionManager", dimensionManager);
        when(providerFactory.getEmbeddingProvider()).thenReturn(embeddingProvider);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    // ==================== 容量参数化 ====================

    @Test
    @DisplayName("★ 队列容量不再硬编码 2000：默认值为方案文档建议的 10000")
    void queueCapacity_shouldDefaultTo10000() {
        assertThat(service.queueCapacitySetting())
                .as("硬编码 2000 的旧实现会让本断言失败")
                .isEqualTo(10000);
    }

    @Test
    @DisplayName("★ 队列容量随配置生效：注入 7 后队列的实际容量即为 7")
    void queueCapacity_shouldFollowConfiguredValue() {
        ReflectionTestUtils.setField(service, "queueCapacity", 7);

        service.startConsumers();

        assertThat(service.taskQueueForTest().remainingCapacity())
                .as("容量未参数化时（恒为 2000）本断言失败")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("★ application-p2.yml 必须声明 agent-qr.embedding.queue-capacity（配置与代码不脱节）")
    void applicationP2Yml_shouldDeclareQueueCapacity() throws Exception {
        String yml = readApplicationP2();
        Matcher matcher = Pattern.compile("(?m)^\\s*queue-capacity:\\s*(\\d+)\\s*(#.*)?$").matcher(yml);

        assertThat(matcher.find()).as("yml 未声明 queue-capacity，参数化等于虚设").isTrue();
        assertThat(matcher.group(1)).isEqualTo("10000");
    }

    // ==================== 功能回归 ====================

    @Test
    @DisplayName("提交的任务仍能被消费并完成 Future（队列改造未破坏攒批链路）")
    void submit_shouldCompleteFutures() throws Exception {
        when(embeddingProvider.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[]{texts.get(i).length(), 1f, 2f});
            }
            return vectors;
        });
        service.startConsumers();

        List<CompletableFuture<float[]>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(service.submit(() -> "text-" + System.nanoTime()));
        }

        for (CompletableFuture<float[]> future : futures) {
            float[] vector = future.get(10, TimeUnit.SECONDS);
            assertThat(vector).hasSize(3);
        }
    }

    // ==================== 失败语义：整批失败（批次 07 · 任务 7.2.5） ====================

    @Test
    @DisplayName("★ 批量失败时整批 future 均以异常完成，且不再逐条调用 embed（本用例拦住原缺陷）")
    void submit_shouldFailWholeBatch_whenBatchFails() throws Exception {
        when(embeddingProvider.embedBatch(anyList()))
                .thenThrow(new RuntimeException("bulk endpoint failed"));
        service.startConsumers();

        List<CompletableFuture<float[]>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(service.submit(() -> "text-" + System.nanoTime()));
        }

        for (CompletableFuture<float[]> future : futures) {
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .as("整批失败语义下每个 future 都应以异常完成")
                    .hasRootCauseInstanceOf(RuntimeException.class);
        }
        verify(embeddingProvider, never())
                .embed(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("★ 返回数量不匹配时整批失败（安全阀保留，不按索引错配、不逐条重试）")
    void submit_shouldFailWholeBatch_whenVectorCountMismatch() throws Exception {
        // 真批量端点 /api/embed 的部分失败/截断：返回数量少于输入
        when(embeddingProvider.embedBatch(anyList()))
                .thenAnswer(invocation -> List.of(new float[]{1f}, new float[]{2f}));
        service.startConsumers();

        List<CompletableFuture<float[]>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(service.submit(() -> "text-" + System.nanoTime()));
        }

        for (CompletableFuture<float[]> future : futures) {
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .hasMessageContaining("数量不匹配");
        }
        verify(embeddingProvider, never())
                .embed(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("★ 连续失败达阈值输出聚合告警；服务恢复后复位并提示恢复")
    void failureAlert_shouldRaiseAtThresholdAndResetOnSuccess() throws Exception {
        ReflectionTestUtils.setField(service, "failureAlertThreshold", 2);
        // 用 doThrow/doAnswer 而非 when(...)：重复 stub 一个"已配置抛异常"的方法时，
        // when(...) 的调用本身就会抛出，掩盖真实断言
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
                .when(embeddingProvider).embedBatch(anyList());
        service.startConsumers();

        // 第一次失败：未达阈值
        assertThatThrownBy(() -> service.submit(() -> "a").get(10, TimeUnit.SECONDS));
        awaitCondition(() -> service.consecutiveFailureCount() == 1);
        assertThat(service.isFailureAlertRaised()).isFalse();

        // 第二次失败：达到阈值 → 聚合告警
        assertThatThrownBy(() -> service.submit(() -> "b").get(10, TimeUnit.SECONDS));
        awaitCondition(service::isFailureAlertRaised);
        assertThat(service.isFailureAlertRaised()).isTrue();

        // 服务恢复：计数清零、告警复位
        org.mockito.Mockito.doAnswer(invocation -> List.of(new float[]{7f, 7f}))
                .when(embeddingProvider).embedBatch(anyList());
        assertThat(service.submit(() -> "c").get(10, TimeUnit.SECONDS)).containsExactly(7f, 7f);
        assertThat(service.consecutiveFailureCount()).isZero();
        assertThat(service.isFailureAlertRaised()).isFalse();
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待条件超时");
    }

    private static String readApplicationP2() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", "application-p2.yml"));
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 application-p2.yml");
    }
}
