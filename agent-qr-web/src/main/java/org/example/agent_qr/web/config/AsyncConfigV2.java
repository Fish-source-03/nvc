package org.example.agent_qr.web.config;

import org.example.agent_qr.common.executor.MdcTaskDecorator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置 V2（P2 四池隔离 + MDC 传递）。
 * <p>
 * 替换 P1 的 {@link AsyncConfigP1}，提供更细粒度的线程池：
 * <ul>
 *   <li>parseExecutor：文档解析</li>
 *   <li>chunkExecutor：文本切片</li>
 *   <li>embedExecutor：向量化</li>
 *   <li>deleteExecutor：删除补偿</li>
 *   <li>indexBuilderExecutor：索引构建</li>
 *   <li>statExecutor：统计更新</li>
 * </ul>
 * 所有线程池均使用 MdcTaskDecorator 传递 TraceId。
 * </p>
 * <p>
 * <b>R49（批次 11）——"裸 {@code @Async} 会落到默认执行器"的洞与处置</b>：
 * 本类实现了 {@link AsyncConfigurer} 但<b>不</b>覆写 {@code getAsyncExecutor()}，
 * 因此<b>未显式指定池名</b>的 {@code @Async} 会落到 Spring 默认执行器，
 * 不享受六池隔离与 MDC 传递。处置选择<b>"逐个 {@code @Async} 指定池名"</b>而非
 * "覆写 {@code getAsyncExecutor()} 指向某一个池"：
 * </p>
 * <ul>
 *   <li>六池是<b>按语义划分</b>的（解析/切片/向量化/删除/索引构建/统计），
 *       不存在一个"万能默认池"——把未知任务导向任一池都会造成语义混淆与单池过载；</li>
 *   <li>本仓库的既定约定就是"生产代码一律显式指定池名（六者之一）"（设计 §7.4），
 *       逐个指定与文档一致；</li>
 *   <li>防回归由 {@code AsyncConfigV2Test} 的<b>源码扫描</b>兜底：
 *       main 源码中出现裸 {@code @Async}（或指定了六池之外的池名）即测试失败——
 *       新代码不会无声地重新开洞。</li>
 * </ul>
 * <p>
 * 影响面：仅 3 个此前未指定池名的监听方法（{@code DataSyncListener.onDataSyncCompleted}
 * → statExecutor；{@code KnowledgeCatalogService} 的两个事件监听 → indexBuilderExecutor），
 * 已指定池名的调用点<b>一字未改</b>（有测试锁定的 {@code chunkExecutor} 归属保持不变）。
 * </p>
 *
 * @author agent-qr
 */
@Configuration
@EnableAsync
public class AsyncConfigV2 implements AsyncConfigurer {

    @Autowired
    private MdcTaskDecorator mdcTaskDecorator;

    /** 文档解析线程池 */
    @Bean("parseExecutor")
    public Executor parseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("parse-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 文本切片线程池 */
    @Bean("chunkExecutor")
    public Executor chunkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("chunk-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 向量化线程池 */
    @Bean("embedExecutor")
    public Executor embedExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("embed-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 删除补偿线程池 */
    @Bean("deleteExecutor")
    public Executor deleteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("delete-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 索引构建线程池 */
    @Bean("indexBuilderExecutor")
    public Executor indexBuilderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setThreadNamePrefix("index-builder-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 统计更新线程池 */
    @Bean("statExecutor")
    public Executor statExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("stat-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
