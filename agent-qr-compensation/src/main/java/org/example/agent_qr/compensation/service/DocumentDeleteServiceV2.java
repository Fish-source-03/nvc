package org.example.agent_qr.compensation.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.compensation.entity.DeleteTask;
import org.example.agent_qr.compensation.mapper.DeleteTaskMapper;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文档删除服务 V2 — ChromaDB 物理删除。
 * <p>
 * 独立于 knowledge 模块，负责异步物理删除 ChromaDB 中的向量记录。
 * 通过 {@link ChromaRetriever} 封装类操作 ChromaDB，失败时通过 DLQ 重试。
 * </p>
 *
 * <h3>批次 08 · 任务 8.2（问题 31）：两种"跳过"必须区分</h3>
 * <p>
 * 原实现在 {@code chromaRetriever == null} 时只打一条 WARN 便继续执行，
 * 随后照常把任务标记为 {@code DONE}——<b>一条向量都没删，任务却记录为完成</b>，
 * 把"依赖缺失"这一部署/配置错误伪装成了成功。现在两者语义严格区分：
 * </p>
 * <ul>
 *   <li><b>无向量 ID</b>（{@code chromaIds} 为空）→ 置 {@code DONE}：
 *       "没有要删的东西"确实是完成；</li>
 *   <li><b>依赖不可用</b>（{@code chromaRetriever == null}）→ 走<b>失败路径</b>：
 *       置 {@code FAILED} + 递增重试次数 + DLQ 入队 + 计数告警。
 *       依赖缺失属"删除根本没执行"，绝不能与"无需删除"混为一谈。</li>
 * </ul>
 *
 * <h3>关于 {@code @Autowired(required = false)}（任务 8.2.4 的权衡结论：保留）</h3>
 * <p>
 * 保留可选注入，<b>不去掉</b> {@code required = false}。理由：
 * </p>
 * <ol>
 *   <li>compensation 模块的设计定位是"可以装载在被裁剪的部署里"，
 *       强制依赖 rag 模块的 Bean 会让整个应用在缺少该 Bean 时启动失败——
 *       用"启动期崩溃"换取"运行期可见性"，代价过大；</li>
 *   <li>本任务已把"缺失"从静默成功改为<b>显式失败</b>（FAILED + DLQ + 计数），
 *       可见性问题已由失败路径解决，不再需要靠强依赖兜底；</li>
 *   <li>另有 {@link #verifyDependencyOnStartup()} 在启动期打印一次明确结论，
 *       使"装配缺失"在服务启动时即可被发现。</li>
 * </ol>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class DocumentDeleteServiceV2 {

    /**
     * 删除链路必需依赖（向量物理删除）为可选注入。
     * <p>为 {@code null} 时<b>不再静默跳过</b>，而是让删除任务落 {@code FAILED} 并入 DLQ
     * （任务 8.2.1）。是否去掉 {@code required = false} 的权衡见类注释。</p>
     */
    @Autowired(required = false)
    private ChromaRetriever chromaRetriever;

    @Autowired
    private DeleteTaskMapper deleteTaskMapper;

    @Autowired
    private DeadLetterQueue deadLetterQueue;

    /**
     * "因必需依赖缺失而失败"的累计次数（批次 08 · 任务 8.2.3）。
     * <p>
     * 单次失败已有 ERROR 日志，但本异常的特点是<b>长期持续</b>（装配问题不会自愈），
     * 若无累计指标，日志会被后续正常日志淹没。该计数可在监控/排查时直接读取，
     * 用于区分"偶发删除失败"与"依赖长期缺失"。
     * </p>
     */
    private final AtomicLong dependencyMissingCount = new AtomicLong();

    /**
     * 启动期依赖校验（批次 08 · 任务 8.2.4）。
     * <p>
     * 只做一次存在性检查并给出明确结论，不抛异常、不改变注入方式
     * （保持 compensation 模块可独立装载的能力）。
     * </p>
     */
    @PostConstruct
    void verifyDependencyOnStartup() {
        if (chromaRetriever == null) {
            log.error("DocumentDeleteServiceV2 启动检查：ChromaRetriever 未装配 —— "
                    + "文档删除链路的向量物理删除将不可用，删除任务将落 FAILED 并入 DLQ；"
                    + "请检查 agent-qr-rag 模块是否在部署中");
        } else {
            log.info("DocumentDeleteServiceV2 启动检查：ChromaRetriever 已装配，向量物理删除可用");
        }
    }

    /**
     * 因依赖缺失而失败的累计次数（供监控与测试断言）。
     *
     * @return 累计次数
     */
    public long dependencyMissingCount() {
        return dependencyMissingCount.get();
    }

    /**
     * 异步物理删除 ChromaDB 向量记录（<b>主链路 / 事件驱动</b>）。
     * <p>
     * 流程：创建 DeleteTask(PENDING) →
     * <ul>
     *   <li>{@code chromaIds} 为空 → updateStatus(DONE)（无需删除）；</li>
     *   <li>{@code chromaRetriever} 为 null → 失败路径（FAILED + retryCount + DLQ，任务 8.2.1）；</li>
     *   <li>正常 → chromaRetriever.deleteByIds → 成功 updateStatus(DONE) /
     *       失败 incrementRetryCount + updateStatus(FAILED) + DLQ 入队。</li>
     * </ul>
     * </p>
     * <p>
     * ⚠️ 本方法为 {@code @Async} fire-and-forget，<b>失败时自行入队</b>一条新的死信——
     * 这只适用于主链路（调用方是事件监听器，异常无人接手）。<b>DLQ 重放不得调用本方法</b>：
     * 那会让"当前消息被置成功"与"新消息入队（退避计数归零）"同时发生，
     * 形成永不终止的环路（批次 01 风险 R1）。重放请用
     * {@link #retryPhysicalDelete(Long, List)}。
     * </p>
     *
     * @param documentId 文档 ID
     * @param chromaIds  ChromaDB 向量 ID 列表
     */
    @Async("deleteExecutor")
    public void asyncPhysicalDelete(Long documentId, List<String> chromaIds) {
        try {
            executePhysicalDelete(documentId, chromaIds);
        } catch (RuntimeException e) {
            // 主链路失败 → 入 DLQ，由退避重试接手（批次 01 语义，保留）
            deadLetterQueue.enqueue(DlqMessage.EVENT_DELETE, documentId,
                    buildPayload(documentId, chromaIds), e);
        }
    }

    /**
     * <b>同步</b>物理删除 —— DLQ 重放专用（批次 08 · 8.5 / 风险 R1 修复）。
     * <p>
     * 与 {@link #asyncPhysicalDelete} 的差别只有两点，二者共同消除环路：
     * </p>
     * <ol>
     *   <li><b>同步执行</b>（无 {@code @Async}）：删除结果对调用方可见；</li>
     *   <li><b>失败不自行入队</b>：直接向上抛出，由 {@code DlqRetryScheduler#retryDelete}
     *       的调用方把失败记到<b>当前</b>死信消息上 → 既有指数退避（3s→9s→27s→81s）
     *       → 超过最大重试次数转 {@code DEAD}。<b>死信表不会因持续失败而无界增长。</b></li>
     * </ol>
     * <p>
     * 删除任务记录（{@code delete_task}）与状态流转仍与主链路一致：
     * 每次尝试建一条任务，成功置 {@code DONE}、失败置 {@code FAILED}（保留尝试审计）。
     * </p>
     *
     * @param documentId 文档 ID
     * @param chromaIds  ChromaDB 向量 ID 列表
     * @throws RuntimeException 删除失败时（含依赖缺失），供调用方按当前消息退避重试
     */
    public void retryPhysicalDelete(Long documentId, List<String> chromaIds) {
        executePhysicalDelete(documentId, chromaIds);
    }

    /**
     * 物理删除核心：建任务 →（无向量 ID：DONE）→ 删向量 → DONE / 失败：递增重试 + FAILED + 抛出。
     *
     * @param documentId 文档 ID
     * @param chromaIds  ChromaDB 向量 ID 列表
     * @throws RuntimeException 删除失败或必需依赖缺失时
     */
    private void executePhysicalDelete(Long documentId, List<String> chromaIds) {
        // 1. 创建删除任务记录
        DeleteTask task = new DeleteTask();
        task.setDocumentId(documentId);
        task.setChromaIds(String.join(",", chromaIds != null ? chromaIds : List.of()));
        task.setStatus(DeleteTask.STATUS_PENDING);
        task.setRetryCount(0);
        task.setCreateTime(LocalDateTime.now());
        deleteTaskMapper.insert(task);

        // 2. 无向量 ID：确实"无需删除"，置 DONE 是正确的完成语义（与依赖缺失严格区分）
        if (chromaIds == null || chromaIds.isEmpty()) {
            deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_DONE);
            log.info("ChromaDB 物理删除无需操作（无向量 ID）: documentId={}", documentId);
            return;
        }

        // 3. ChromaDB 物理删除（通过 ChromaRetriever 封装）
        try {
            if (chromaRetriever == null) {
                // 问题 31：依赖缺失 ≠ 无需删除。抛异常走失败路径，
                // 因此下面的"物理删除完成"日志不会打印（不会出现"跳过…完成"的自相矛盾）
                throw new IllegalStateException(String.format(
                        "ChromaRetriever 未装配，向量物理删除未执行（非'无需删除'）: documentId=%d, chromaIdCount=%d",
                        documentId, chromaIds.size()));
            }
            chromaRetriever.deleteByIds(chromaIds);

            // 4. 标记完成
            deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_DONE);
            log.info("ChromaDB 物理删除完成: documentId={}, chromaIdCount={}", documentId, chromaIds.size());
        } catch (Exception e) {
            if (chromaRetriever == null) {
                long missing = dependencyMissingCount.incrementAndGet();
                log.error("ChromaDB 物理删除失败（依赖缺失，累计 {} 次）: documentId={}, error={}",
                        missing, documentId, e.getMessage());
            } else {
                log.error("ChromaDB 物理删除失败: documentId={}, error={}", documentId, e.getMessage(), e);
            }
            deleteTaskMapper.incrementRetryCount(task.getId());

            // 失败状态落库：否则任务永久停留 PENDING，运维无法区分"执行中"与"已失败"（设计 §10.4）
            deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_FAILED);

            // 失败向上抛出：主链路由 asyncPhysicalDelete 入队，重放链路由 DLQ 按当前消息退避
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("ChromaDB 物理删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 DELETE 死信 payload（与批次 01 的契约一致，重试体按此解析 chromaIds）。
     *
     * @param documentId 文档 ID
     * @param chromaIds  向量 ID 列表（可为 null）
     * @return JSON 字符串
     */
    private static String buildPayload(Long documentId, List<String> chromaIds) {
        return String.format("{\"documentId\":%d,\"chromaIds\":\"%s\"}",
                documentId, chromaIds != null ? String.join(",", chromaIds) : "");
    }
}
