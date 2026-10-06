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
 * 吞吐量可达逐条调用的 100 倍提升。批量失败时降级逐条重试。
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

    /** 消费者线程数 */
    private final int consumerCount = Runtime.getRuntime().availableProcessors();

    private volatile boolean running = true;

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
                log.error("批量向量化处理异常", e);
                // 失败时降级逐条重试
                for (EmbedTask task : batch) {
                    retrySingle(task);
                }
                batch.clear();
            }
        }
    }

    /**
     * 执行批量向量化。
     */
    private void executeBatch(List<EmbedTask> batch) {
        try {
            EmbeddingProvider provider = providerFactory.getEmbeddingProvider();
            List<String> texts = batch.stream()
                    .map(t -> t.getText().getContent())
                    .toList();
            List<float[]> vectors = provider.embedBatch(texts);

            if (vectors.size() != batch.size()) {
                log.warn("批量向量化返回数量不匹配: expected={}, actual={}", batch.size(), vectors.size());
                // 降级逐条重试
                for (int i = 0; i < batch.size(); i++) {
                    retrySingle(batch.get(i));
                }
                return;
            }

            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).getFuture().complete(vectors.get(i));
            }
            log.debug("批量向量化完成: batchSize={}", batch.size());
        } catch (Exception e) {
            log.error("批量向量化失败，降级逐条重试: batchSize={}", batch.size(), e);
            for (EmbedTask task : batch) {
                retrySingle(task);
            }
        }
    }

    /**
     * 降级逐条重试。
     */
    private void retrySingle(EmbedTask task) {
        try {
            EmbeddingProvider provider = providerFactory.getEmbeddingProvider();
            float[] vector = provider.embed(task.getText().getContent());
            task.getFuture().complete(vector);
        } catch (Exception ex) {
            task.getFuture().completeExceptionally(ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        log.info("批量向量化攒批服务已关闭");
    }

    /**
     * 获取当前 Embedding 模型对应的 ChromaDB Collection 名称（P3 新增）。
     * <p>优先使用 {@link EmbeddingDimensionManager} 动态生成，不可用时返回 {@code null}。</p>
     *
     * @return Collection 名称，不可用时返回 {@code null}
     */
    public String getEffectiveCollectionName() {
        if (dimensionManager != null) {
            try {
                return dimensionManager.getCollectionName();
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
