package org.example.agent_qr.rag.embedding;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.rag.EmbeddableText;
import org.example.agent_qr.rag.provider.EmbeddingProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 批量向量化攒批服务 — BlockingQueue 生产者-消费者模式。
 * <p>
 * 将单个切片向量化请求攒批处理，批量调用 Embedding API，
 * 吞吐量可达逐条调用的 100 倍提升。
 * </p>
 * <p>
 * <b>失败语义（批次 07 · 任务 7.2.5，设计变更）</b>：整批失败——
 * 批量调用异常或返回数量与输入不一致时，整批 future 统一以异常完成，
 * <b>不再降级逐条重试</b>；连续失败达阈值时输出聚合告警（任务 7.2.6）。
 * </p>
 * <p>
 * P3 扩展：集成 {@link EmbeddingDimensionManager}，动态获取 ChromaDB Collection 名称，
 * 确保向量写入与 Embedding 模型维度匹配的 Collection。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class BatchEmbeddingService {

    @Autowired
    private ProviderFactory providerFactory;

    /** P3 新增：向量维度管理器，用于动态获取 Collection 名称 */
    @Autowired
    private EmbeddingDimensionManager dimensionManager;

    /**
     * 攒批队列容量（批次 05 · 任务 5.2.3）。
     * <p>
     * 原实现为硬编码 {@code new LinkedBlockingQueue<>(2000)}，容量不可配置：
     * 大数据源同步时生产端会因队列满而阻塞在 {@code offer(5s)}。
     * 现改为读取 {@code agent-qr.embedding.queue-capacity}（默认 10000）。
     * </p>
     * <p>
     * ⚠️ 队满时的处理策略属批次 07 任务 7.0（事件驱动改造后 ETL 不再直接 submit），
     * 本批次只做容量参数化。
     * </p>
     */
    @Value("${agent-qr.embedding.queue-capacity:10000}")
    private int queueCapacity = 10000;

    /** 攒批队列，容量由 {@link #queueCapacity} 决定（延迟到 {@link #startConsumers()} 初始化） */
    private volatile BlockingQueue<EmbedTask> taskQueue;

    /** 批量大小，默认 32 */
    @Value("${agent-qr.embedding.batch-size:32}")
    private int batchSize;

    /** 批量超时（毫秒），默认 100ms */
    @Value("${agent-qr.embedding.batch-timeout-ms:100}")
    private long batchTimeoutMs;

    /**
     * 连续失败批次的告警阈值（批次 07 · 任务 7.2.6）。
     * <p>Embedding 是单点（本地 Ollama），服务整体不可用时每个批次都会失败。
     * 达到阈值时输出<b>聚合告警</b>，把"单批次失败"升级为"服务级故障"的明确信号；
     * 阈值之上不再重复输出完整告警，避免日志被刷屏。</p>
     */
    @Value("${agent-qr.embedding.failure-alert-threshold:3}")
    private int failureAlertThreshold = 3;

    /** 消费者线程数 */
    private final int consumerCount = Runtime.getRuntime().availableProcessors();

    private volatile boolean running = true;

    /** 连续失败的批次数（成功一批即清零） */
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailureBatches =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 是否已发出聚合告警（避免阈值之上重复输出） */
    private volatile boolean failureAlertRaised;

    /**
     * 启动消费者线程。
     */
    @PostConstruct
    public void startConsumers() {
        queue();
        for (int i = 0; i < consumerCount; i++) {
            Thread consumer = new Thread(this::consumeLoop, "embed-consumer-" + i);
            consumer.setDaemon(true);
            consumer.start();
        }
        log.info("批量向量化攒批服务启动: consumers={}, batchSize={}, batchTimeoutMs={}, queueCapacity={}",
                consumerCount, batchSize, batchTimeoutMs, queueCapacity);
    }

    /**
     * 获取（必要时惰性创建）攒批队列。
     * <p>
     * {@code @Value} 注入发生在构造之后，因此队列不能在字段初始化时构造；
     * 双检锁保证并发 {@link #submit} 与 {@link #startConsumers()} 下只创建一次。
     * </p>
     *
     * @return 攒批队列
     */
    private BlockingQueue<EmbedTask> queue() {
        BlockingQueue<EmbedTask> current = taskQueue;
        if (current == null) {
            synchronized (this) {
                if (taskQueue == null) {
                    taskQueue = new LinkedBlockingQueue<>(queueCapacity);
                }
                current = taskQueue;
            }
        }
        return current;
    }

    /**
     * 当前攒批队列（供测试断言容量是否随配置生效）。
     *
     * @return 攒批队列
     */
    BlockingQueue<?> taskQueueForTest() {
        return queue();
    }

    /**
     * 当前生效的队列容量配置值。
     *
     * @return 队列容量
     */
    int queueCapacitySetting() {
        return queueCapacity;
    }

    /**
     * 提交文本向量化任务到攒批队列。
     *
     * @param text 待向量化的文本（由 knowledge 模块的 Chunk 等实体实现 EmbeddableText 接口提供）
     * @return 完成后的向量 Future
     */
    public CompletableFuture<float[]> submit(EmbeddableText text) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        EmbedTask task = new EmbedTask(text, future);
        try {
            if (!queue().offer(task, 5, TimeUnit.SECONDS)) {
                future.completeExceptionally(
                        new RuntimeException("向量化任务队列已满，提交超时"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 消费者主循环：攒批 → 批量处理。
     */
    private void consumeLoop() {
        List<EmbedTask> batch = new ArrayList<>();
        while (running) {
            try {
                // poll 第一个任务，带超时
                EmbedTask firstTask = queue().poll(batchTimeoutMs, TimeUnit.MILLISECONDS);
                if (firstTask != null) {
                    batch.add(firstTask);
                    // 继续攒批直到达到 batchSize 或队列为空
                    queue().drainTo(batch, batchSize - 1);
                }

                if (!batch.isEmpty()) {
                    executeBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 循环级异常（不该发生）：整批以异常完成，不做逐条降级重试
                if (batch.isEmpty()) {
                    log.error("批量向量化消费者循环异常（当前无待处理批次）", e);
                } else {
                    failBatch(batch, e);
                }
                batch.clear();
            }
        }
    }

    /**
     * 执行批量向量化。
     * <p>
     * <b>失败语义：整批失败</b>（批次 07 · 任务 7.2.5，已确认的设计变更）。
     * 原实现在批量失败（或返回数量不匹配）时"降级为逐条 {@code embed()} 重试"——
     * 该行为是设计 §17.8 d 步的原文，但自批次 05 起 {@code embedBatch} 已改用真批量端点，
     * 且逐条重试在<b>服务整体不可用</b>时只是把同样的失败调用再做 N 次：
     * 既无意义，又会刷出 N 条重复错误日志。现在统一为：
     * 整个批次的 future 以异常完成，由上游（{@code ChunkEmbeddingBatchListener}）
     * 按"整批回退 INDEXED + 整批入一次 DLQ"处理。
     * </p>
     */
    private void executeBatch(List<EmbedTask> batch) {
        try {
            EmbeddingProvider provider = providerFactory.getEmbeddingProvider();
            List<String> texts = batch.stream()
                    .map(t -> t.getText().getContent())
                    .toList();
            List<float[]> vectors = provider.embedBatch(texts);

            if (vectors.size() != batch.size()) {
                // 安全阀（任务 7.2.5a：保留）。配对是按索引进行的，数量不符时必须整批失败，
                // 否则会在循环中 IndexOutOfBounds（前面的 future 已完成、后面的永久挂起），
                // 若改为按内容匹配更糟——A 的向量写给 B，完全静默。
                // 批次 05 实测：真批量端点 /api/embed 在 128/128 场景下无数量不一致（低频但可达），
                // 保留成本仅一次比较，故保留。
                throw new IllegalStateException(String.format(
                        "批量向量化返回数量不匹配（拒绝按索引错配，整批失败）: expected=%d, actual=%d",
                        batch.size(), vectors.size()));
            }

            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).getFuture().complete(vectors.get(i));
            }
            onBatchSuccess(batch.size());
        } catch (Exception e) {
            failBatch(batch, e);
        }
    }

    /**
     * 整批以异常完成，并做失败可见性处理（批次 07 · 任务 7.2.5 / 7.2.6）。
     *
     * @param batch 失败的批次
     * @param cause 失败原因
     */
    private void failBatch(List<EmbedTask> batch, Throwable cause) {
        int failures = consecutiveFailureBatches.incrementAndGet();
        if (failures < failureAlertThreshold) {
            log.error("批量向量化整批失败: batchSize={}, 连续失败批次={}, error={}",
                    batch.size(), failures, cause.getMessage(), cause);
        } else if (failures == failureAlertThreshold) {
            failureAlertRaised = true;
            // 聚合告警：把"单批次失败"升级为"服务级故障"的明确信号（Embedding 是单点）
            log.error("【聚合告警】Embedding 服务疑似不可用：连续 {} 个批次失败（阈值 {}）。"
                            + "当前为单点部署（本地 Ollama，模型由 ollama.embedding.model 指定），"
                            + "请检查服务与模型是否可用；失败批次已整批回退状态并入 DLQ，"
                            + "服务恢复后可按退避重放。最近错误: {}",
                    failures, failureAlertThreshold, cause.getMessage(), cause);
        } else {
            // 已告警：降为 WARN，避免整批失败持续刷重复日志
            log.warn("批量向量化整批失败（已告警，连续失败批次={}）: batchSize={}, error={}",
                    failures, batch.size(), cause.getMessage());
        }
        for (EmbedTask task : batch) {
            task.getFuture().completeExceptionally(cause);
        }
    }

    /**
     * 成功处理一批后的状态复位（连续失败清零；曾告警则提示恢复）。
     *
     * @param batchSize 本批大小
     */
    private void onBatchSuccess(int batchSize) {
        int previousFailures = consecutiveFailureBatches.getAndSet(0);
        if (failureAlertRaised) {
            failureAlertRaised = false;
            log.info("Embedding 服务已恢复：连续失败 {} 个批次后恢复正常（batchSize={}）",
                    previousFailures, batchSize);
        }
        log.debug("批量向量化完成: batchSize={}", batchSize);
    }

    /**
     * 当前连续失败的批次数（供测试与监控断言）。
     *
     * @return 连续失败批次数
     */
    int consecutiveFailureCount() {
        return consecutiveFailureBatches.get();
    }

    /**
     * 是否已发出"Embedding 服务疑似不可用"的聚合告警（供测试断言）。
     *
     * @return {@code true} 表示已告警
     */
    boolean isFailureAlertRaised() {
        return failureAlertRaised;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        log.info("批量向量化攒批服务已关闭");
    }

    /**
     * 获取当前 Embedding 模型对应、且<b>实际生效</b>的 ChromaDB Collection 名称（P3 新增）。
     * <p>
     * 批次 07 · 任务 7.1：由 {@link EmbeddingDimensionManager#getEffectiveCollectionName()} 统一解析
     * （既有数据保护 / 模型隔离规则都在那里），不再返回"仅按模型派生"的名称——
     * 派生名可能与实际写入的 Collection 不一致，导致调用方判断错位。
     * </p>
     *
     * @return Collection 名称，不可用时返回 {@code null}
     */
    public String getEffectiveCollectionName() {
        if (dimensionManager != null) {
            try {
                return dimensionManager.getEffectiveCollectionName();
            } catch (Exception e) {
                log.warn("获取动态 Collection 名称失败，降级使用 P2 配置", e);
            }
        }
        return null;
    }

    /**
     * 内部任务记录类。
     */
    @Data
    private static class EmbedTask {
        private final EmbeddableText text;
        private final CompletableFuture<float[]> future;

        EmbedTask(EmbeddableText text, CompletableFuture<float[]> future) {
            this.text = text;
            this.future = future;
        }
    }
}
