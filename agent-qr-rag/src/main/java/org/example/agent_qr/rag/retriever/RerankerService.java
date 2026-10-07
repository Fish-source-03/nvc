package org.example.agent_qr.rag.retriever;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 重排序服务（批次 10 · 任务 10.3，问题 14）。
 * <p>
 * 对粗排候选结果做精排，组合分 = {@code 原始相似度权重 × 粗排分 + 文本相关性权重 × 精排分}，
 * 重新排序后截断至 topK。
 * </p>
 * <h3>精排分来源（两条路径）</h3>
 * <ol>
 *   <li><b>主路径：真实交叉编码器</b>——{@link RerankerProvider}（{@link BgeRerankerProvider}，
 *       HTTP 调用本地 TEI 服务的 bge-reranker-v2-m3）。模型对 (query, document) 逐对打分，
 *       能捕捉语义交互（"如何离职" ↔ "解除劳动合同流程" 这类同义不同词的匹配）；</li>
 *   <li><b>降级路径：本地启发式</b>——字符级 n-gram（unigram + bigram）+ Jaccard 相似度。
 *       仅在"模型调用失败/超时"或"运维显式关闭（{@code agent-qr.reranker.enabled=false}）"时启用，
 *       <b>每次降级均记 WARN 日志</b>（问题 14 的原缺陷正是"静默降级"：
 *       外观是交叉编码器、实际是词面重叠度，且无任何提示）。</li>
 * </ol>
 * <h3>配置键（批次 10 · 任务 10.3.3）</h3>
 * <ul>
 *   <li>{@code agent-qr.reranker.enabled} —— 精排模型开关（默认 true）；</li>
 *   <li>{@code agent-qr.reranker.original-weight} / {@code text-relevance-weight}
 *       —— 组合分权重（原为硬编码常量 0.4 / 0.6）；</li>
 *   <li>{@code agent-qr.reranker.max-candidates} —— 单次参与精排的候选上限
 *       （默认 32，与 TEI 单请求上限一致；超出部分按粗排分截断）。</li>
 *   <li>模型名 / 地址 / 超时 / 文档预截断由 {@link BgeRerankerProvider} 读取
 *       （{@code agent-qr.reranker.model} / {@code base-url} / {@code connect-timeout-ms} /
 *       {@code read-timeout-ms} / {@code max-doc-chars}）。</li>
 * </ul>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankerService {

    /** 原始相似度在组合分中的权重（批次 10 起可配置，默认沿用原硬编码值 0.4） */
    @Value("${agent-qr.reranker.original-weight:0.4}")
    private double originalWeight = 0.4;

    /** 文本相关性分数在组合分中的权重（批次 10 起可配置，默认沿用原硬编码值 0.6） */
    @Value("${agent-qr.reranker.text-relevance-weight:0.6}")
    private double textRelevanceWeight = 0.6;

    /**
     * 精排模型开关（默认开启）。
     * <p>关闭时直接走本地启发式（记 WARN），用于模型服务维护或想省算力时的应急降级。</p>
     */
    @Value("${agent-qr.reranker.enabled:true}")
    private boolean modelEnabled = true;

    /**
     * 单次参与精排的候选数上限（默认 32）。
     * <p>TEI 单请求最多 32 条 texts，且 CPU 版时延随条数非线性上升
     * （本机实测 8 条 ~1.8s、19 条 ~10s）；超出上限时仅对<b>粗排分最高</b>的前 N 条做精排。</p>
     */
    @Value("${agent-qr.reranker.max-candidates:32}")
    private int maxCandidates = 32;

    /** 精排模型提供者（真实交叉编码器） */
    private final RerankerProvider rerankerProvider;

    /**
     * 对候选文档进行重排序。
     * <p>
     * 候选数 &lt;= topK 时直接返回，不做多余计算（也避免为几个候选付出一次模型调用）。
     * </p>
     *
     * @param query      用户查询
     * @param candidates 候选文档列表（已按粗排分降序）
     * @param topK       返回的最大结果数
     * @return 重排序后的 TopK 结果
     */
    public List<RetrievedDocument> rerank(String query, List<RetrievedDocument> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        if (candidates.size() <= topK) {
            return candidates;
        }

        // 候选上限：TEI 单请求 ≤32 条；超出部分按粗排顺序截断（候选已按粗排分降序）
        int cap = Math.max(maxCandidates, topK);
        List<RetrievedDocument> pool = candidates;
        if (candidates.size() > cap) {
            pool = new ArrayList<>(candidates.subList(0, cap));
            log.info("候选数 {} 超过精排上限 {}（agent-qr.reranker.max-candidates），"
                            + "仅对粗排分最高的 {} 条做精排，其余不参与本轮精排",
                    candidates.size(), cap, cap);
        }

        double[] textScores = resolveTextRelevanceScores(query, pool);

        for (int i = 0; i < pool.size(); i++) {
            RetrievedDocument doc = pool.get(i);
            double original = doc.getSimilarity() != null ? doc.getSimilarity() : 0.0;
            doc.setSimilarity(originalWeight * original + textRelevanceWeight * textScores[i]);
        }

        List<RetrievedDocument> reranked = pool.stream()
                .sorted(Comparator.comparing(RetrievedDocument::getSimilarity).reversed())
                .limit(topK)
                .toList();

        log.debug("重排序完成: query={}, candidates={}, pool={} → topK={}",
                query, candidates.size(), pool.size(), reranked.size());
        return reranked;
    }

    /**
     * 取得每个候选的"文本相关性分数"。
     * <p>优先走真实交叉编码器（{@link RerankerProvider}）；模型不可用、超时、响应异常或
     * 被配置关闭时，降级到本地启发式（字符 n-gram + Jaccard）并记 WARN。</p>
     *
     * @param query 用户查询
     * @param pool  本轮参与精排的候选
     * @return 与 {@code pool} 一一对应的相关性分数
     */
    private double[] resolveTextRelevanceScores(String query, List<RetrievedDocument> pool) {
        if (!modelEnabled) {
            log.warn("精排模型已被配置关闭（agent-qr.reranker.enabled=false），"
                    + "降级使用本地启发式重排（字符 n-gram + Jaccard）: query={}, candidates={}",
                    query, pool.size());
            return heuristicScores(query, pool);
        }

        try {
            List<String> texts = pool.stream()
                    .map(doc -> doc.getContent() == null ? "" : doc.getContent())
                    .toList();
            List<RerankerProvider.Score> scores = rerankerProvider.rerank(query, texts);

            double[] result = new double[pool.size()];
            Arrays.fill(result, Double.NaN);
            for (RerankerProvider.Score score : scores) {
                if (score.index() >= 0 && score.index() < result.length) {
                    result[score.index()] = score.score();
                }
            }
            for (int i = 0; i < result.length; i++) {
                if (Double.isNaN(result[i])) {
                    // 提供者已保证条目数一致；这里是第二道防线，宁可整体降级也不留"半真半假"的排序
                    throw new RerankerProvider.RerankerException("精排结果缺少下标 " + i + " 的分数");
                }
            }
            log.debug("精排模型调用成功: provider={}, candidates={}", rerankerProvider.name(), pool.size());
            return result;
        } catch (Exception e) {
            log.warn("精排模型调用失败，降级为本地启发式重排（字符 n-gram + Jaccard）: "
                            + "provider={}, query={}, candidates={}, error={}",
                    rerankerProvider.name(), query, pool.size(), e.getMessage());
            return heuristicScores(query, pool);
        }
    }

    /**
     * 本地启发式文本相关性（降级路径，<b>不得删除</b>——问题 14 的禁止事项）。
     * <p>
     * 字符级 n-gram（unigram + bigram）提取中文词项，计算与候选内容的 Jaccard 相似度。
     * 它只衡量<b>字面重叠</b>，对同义不同词的查询效果弱于交叉编码器，
     * 但无网络依赖、零时延，作为模型不可用时的兜底仍有价值。
     * </p>
     *
     * @param query 用户查询
     * @param pool  候选文档
     * @return 与 {@code pool} 一一对应的 Jaccard 相似度
     */
    private double[] heuristicScores(String query, List<RetrievedDocument> pool) {
        Set<String> queryNgrams = extractNgrams(query);
        double[] scores = new double[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            scores[i] = calculateJaccardSimilarity(queryNgrams, pool.get(i).getContent());
        }
        return scores;
    }

    /**
     * 提取字符级 n-gram（unigram + bigram）。
     * <p>
     * 对中文等无空格语言，字符 n-gram 能有效捕捉词汇边界信息。
     * 对空格分隔的英文词，也保留完整词项。
     * </p>
     * <ul>
     *   <li>unigram: 每个单独字符</li>
     *   <li>bigram: 每两个相邻字符组成的词项</li>
     *   <li>空格分隔词: 保留完整词（如英文单词、数字）</li>
     * </ul>
     *
     * @param text 输入文本
     * @return n-gram 词项集合
     */
    Set<String> extractNgrams(String text) {
        Set<String> ngrams = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return ngrams;
        }

        String lower = text.toLowerCase().trim();

        // 1. 空格分隔的完整词项（处理英文、数字等）
        String[] words = lower.split("\\s+");
        for (String word : words) {
            if (!word.isEmpty()) {
                ngrams.add(word);
            }
        }

        // 2. 字符级 unigram + bigram（处理中文等无空格语言）
        // 过滤掉空白字符，保留有意义的字符序列
        String compact = lower.replaceAll("\\s+", "");
        int len = compact.length();

        for (int i = 0; i < len; i++) {
            // unigram
            ngrams.add(String.valueOf(compact.charAt(i)));

            // bigram
            if (i + 1 < len) {
                ngrams.add(compact.substring(i, i + 2));
            }
        }

        return ngrams;
    }

    /**
     * 计算 Jaccard 相似度。
     * <p>
     * Jaccard = |A ∩ B| / |A ∪ B|，衡量两个词项集合的重叠程度。
     * 对缓存的查询词项集合复用，避免重复提取。
     * </p>
     *
     * @param queryNgrams 预提取的查询 n-gram 集合
     * @param content     文档内容
     * @return Jaccard 相似度 [0.0, 1.0]
     */
    private double calculateJaccardSimilarity(Set<String> queryNgrams, String content) {
        if (queryNgrams.isEmpty() || content == null || content.isEmpty()) {
            return 0.0;
        }

        Set<String> contentNgrams = extractNgrams(content);
        if (contentNgrams.isEmpty()) {
            return 0.0;
        }

        // 计算交集大小
        Set<String> intersection = new HashSet<>(queryNgrams);
        intersection.retainAll(contentNgrams);

        // 计算并集大小
        Set<String> union = new HashSet<>(queryNgrams);
        union.addAll(contentNgrams);

        return (double) intersection.size() / union.size();
    }
}
