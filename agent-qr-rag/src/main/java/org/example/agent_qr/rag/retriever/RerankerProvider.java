package org.example.agent_qr.rag.retriever;

import java.util.List;

/**
 * 精排（Cross-Encoder）服务提供者接口（批次 10 · 任务 10.3，问题 14）。
 * <p>
 * 设计 §6.2.5.3 要求 Reranker 使用 <b>bge-reranker-v2-m3 交叉编码器</b>对候选文档做精排；
 * 原实现只有本地"字符 n-gram + Jaccard"启发式、零网络调用（问题 14）。
 * 本接口把"模型调用"抽象出来，由 {@link BgeRerankerProvider} 通过 HTTP 调用本地
 * TEI（text-embeddings-inference）服务实现，{@link RerankerService} 负责组合打分与降级。
 * </p>
 * <p>
 * <b>降级契约</b>：实现类在"服务不可用 / 超时 / 响应异常"时<b>必须抛出</b>
 * {@link RerankerException}，由 {@link RerankerService} 捕获并降级到本地启发式
 * （降级必须留下 WARN 日志，不得静默）。
 * </p>
 *
 * @author agent-qr
 */
public interface RerankerProvider {

    /**
     * 对候选文档逐条与查询做交叉编码打分（query-document 交互式打分，非双塔相似度）。
     *
     * @param query     用户查询
     * @param documents 候选文档文本（调用方已按服务能力预截断）
     * @return 打分结果列表；{@code index} 为 {@code documents} 中的<b>原始下标</b>，
     *         {@code score} 越大越相关（bge-reranker 为 sigmoid 后的 [0,1] 分）
     * @throws RerankerException 服务不可用、超时或响应无法解析
     */
    List<Score> rerank(String query, List<String> documents);

    /**
     * 提供者名称（日志用）。
     *
     * @return 名称
     */
    String name();

    /**
     * 单次 HTTP 请求允许的最大文档条数（服务端硬限制，见 progress.md 4.8：TEI 为 32）。
     *
     * @return 单请求上限
     */
    default int maxTextsPerRequest() {
        return 32;
    }

    /**
     * 单条打分结果。
     *
     * @param index 文档在入参列表中的原始下标
     * @param score 相关性分数（越大越相关）
     */
    record Score(int index, double score) {
    }

    /**
     * 精排服务调用失败（不可达 / 超时 / 响应异常）。
     * <p>由 {@link RerankerService} 捕获并触发启发式降级。</p>
     */
    class RerankerException extends RuntimeException {

        public RerankerException(String message) {
            super(message);
        }

        public RerankerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
