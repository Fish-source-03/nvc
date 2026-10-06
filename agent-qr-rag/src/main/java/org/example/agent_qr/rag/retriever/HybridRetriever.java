package org.example.agent_qr.rag.retriever;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.catalog.router.DomainRouter;
import org.example.agent_qr.rag.router.DomainRouterV2;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.FilterCondition;
import org.example.agent_qr.rag.filter.StructuredFilterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索器 — 双路召回 + 域过滤 + RRF 融合 + Rerank 精排。
 * <p>
 * 检索流程：
 * <ol>
 *   <li>Step 0: 结构化过滤缩小候选范围（域过滤覆盖数据同步+文档上传两条管线）</li>
 *   <li>Step 1: 双路宽召回（语义 ChromaDB Top20 + BM25 Top20）</li>
 *   <li>Step 1.5: 域后过滤 — 仅保留 candidateChunkIds 内的结果</li>
 *   <li>Step 1.6: 权限兜底 — 按当前登录用户的 allowedDomains 强制裁剪（问题 09，独立于入口鉴权）</li>
 *   <li>Step 2: RRF 加权融合去重（score = w1/(k+rank_semantic) + w2/(k+rank_bm25), k=60）</li>
 *   <li>Step 3: Rerank 精排 → TopK</li>
 * </ol>
 * </p>
 * <p>
 * P3 扩展：注入 DomainRouterV2（可选），路由决策在 ChatQueryService 中统一处理，
 * HybridRetriever 仅作为检索执行器。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class HybridRetriever {

    @Autowired
    private ChromaRetriever chromaRetriever;

    @Autowired
    private BM25Retriever bm25Retriever;

    @Autowired
    private RerankerService rerankerService;

    @Autowired
    private StructuredFilterService structuredFilterService;

    @Autowired(required = false)
    private DomainRouter domainRouter;

    /** P3 新增：语义路由 V2（可选，用于日志/调试） */
    @Autowired(required = false)
    private DomainRouterV2 domainRouterV2;

    /** 语义检索权重 */
    @Value("${agent-qr.retrieval.semantic-weight:0.55}")
    private double semanticWeight;

    /** 关键词检索权重 */
    @Value("${agent-qr.retrieval.keyword-weight:0.45}")
    private double keywordWeight;

    /** 宽召回 TopK */
    @Value("${agent-qr.retrieval.wide-top-k:20}")
    private int wideTopK;

    /** 最终返回 TopK */
    @Value("${agent-qr.retrieval.final-top-k:5}")
    private int finalTopK;

    /** RRF 常数 k — 控制排名对分数的区分度 */
    @Value("${agent-qr.retrieval.rrf-k:15}")
    private int rrfK;

    /**
     * 执行混合检索。
     *
     * @param query            用户查询文本
     * @param queryEmbedding   查询向量
     * @param routing          域路由结果
     * @param filterConditions 结构化过滤条件
     * @return 精排后的 TopK 检索结果
     */
    public List<RetrievedDocument> hybridSearch(String query, float[] queryEmbedding,
                                                 DomainRoutingResult routing,
                                                 List<FilterCondition> filterConditions) {
        // Step 0: 结构化过滤 → 候选 chunkId 列表
        String domain = routing != null ? routing.getPrimaryDomain() : null;
        List<Long> candidateChunkIds = structuredFilterService.filterChunkIds(domain, filterConditions);

        // Step 1: 双路宽召回
        List<RetrievedDocument> semanticResults = chromaRetriever.similaritySearch(queryEmbedding, wideTopK);
        List<RetrievedDocument> keywordResults = bm25Retriever.keywordSearch(query, wideTopK);

        // Step 1.5: 域后过滤 — 仅当指定了域时才过滤
        boolean hasDomainFilter = domain != null && !domain.isBlank();
        if (hasDomainFilter && !candidateChunkIds.isEmpty()) {
            Set<Long> allowedIds = new HashSet<>(candidateChunkIds);
            semanticResults = semanticResults.stream()
                    .filter(doc -> doc.getChunkId() != null && allowedIds.contains(doc.getChunkId()))
                    .toList();
            keywordResults = keywordResults.stream()
                    .filter(doc -> doc.getChunkId() != null && allowedIds.contains(doc.getChunkId()))
                    .toList();
            log.debug("域后过滤: domain={}, allowedIds={}, semanticAfter={}, keywordAfter={}",
                    domain, allowedIds.size(), semanticResults.size(), keywordResults.size());
        }

        // Step 1.6: ★ 权限兜底 — 按当前登录用户的 allowedDomains 强制裁剪候选集（问题 09）
        //   入口鉴权（ChatController 的 @PreAuthorize canQueryDomain）之外的第二道防线，
        //   不依赖调用方是否做过域校验。与 Step 1.5 相互独立：
        //   空候选集的守卫逻辑属批次 04 任务 4.2 的范围，此处不做改动。
        List<Long> permittedChunkIds = resolvePermittedChunkIds(domain, candidateChunkIds);
        if (permittedChunkIds != null) {
            Set<Long> permitted = new HashSet<>(permittedChunkIds);
            int semanticBefore = semanticResults.size();
            int keywordBefore = keywordResults.size();
            semanticResults = semanticResults.stream()
                    .filter(doc -> doc.getChunkId() != null && permitted.contains(doc.getChunkId()))
                    .toList();
            keywordResults = keywordResults.stream()
                    .filter(doc -> doc.getChunkId() != null && permitted.contains(doc.getChunkId()))
                    .toList();
            log.debug("权限兜底裁剪: permittedChunkIds={}, semantic {}→{}, keyword {}→{}",
                    permitted.size(), semanticBefore, semanticResults.size(),
                    keywordBefore, keywordResults.size());
        }

        // Step 2: RRF 加权融合去重
        List<RetrievedDocument> fusedResults = rrfFusion(semanticResults, keywordResults);

        // Step 3: Rerank 精排
        List<RetrievedDocument> finalResults = rerankerService.rerank(query, fusedResults, finalTopK);

        log.info("混合检索完成: query={}, semantic={}, keyword={}, fused={}, final={}",
                query, semanticResults.size(), keywordResults.size(), fusedResults.size(), finalResults.size());
        return finalResults;
    }

    /**
     * 解析"当前登录用户有权访问的候选切片 ID"（问题 09 的检索侧兜底）。
     * <p>
     * 允许的域与 {@code UserPrincipal.hasDomainAccess} 保持同一规则：
     * {@code allowedDomains} ∪ {@code department}。
     * </p>
     *
     * @param routingDomain          Step 0 使用的路由域（可为 null）
     * @param routingCandidateChunkIds Step 0 已查询到的候选 ID（若路由域属于用户允许域，可直接复用，避免重复查库）
     * @return {@code null} 表示不做裁剪（未认证或 admin）；
     *         空列表表示用户没有任何可用域，此时候选集会被全部裁掉（fail-closed：宁可无结果也不越权返回）
     */
    private List<Long> resolvePermittedChunkIds(String routingDomain, List<Long> routingCandidateChunkIds) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        if (principal.isAdmin()) {
            return null;
        }

        Set<String> permittedDomains = new LinkedHashSet<>();
        if (principal.getAllowedDomains() != null) {
            principal.getAllowedDomains().stream()
                    .filter(StringUtils::hasText)
                    .forEach(permittedDomains::add);
        }
        if (StringUtils.hasText(principal.getDepartment())) {
            permittedDomains.add(principal.getDepartment());
        }

        Set<Long> ids = new LinkedHashSet<>();
        for (String permittedDomain : permittedDomains) {
            if (permittedDomain.equals(routingDomain)
                    && routingCandidateChunkIds != null && !routingCandidateChunkIds.isEmpty()) {
                ids.addAll(routingCandidateChunkIds);
            } else {
                ids.addAll(structuredFilterService.filterChunkIds(permittedDomain, List.of()));
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * RRF（Reciprocal Rank Fusion）加权融合。
     * <p>
     * 公式：score(d) = w_semantic/(k + rank_semantic) + w_keyword/(k + rank_bm25)
     * 其中 k 从配置 agent-qr.retrieval.rrf-k 读取（默认 15）。
     * </p>
     */
    private List<RetrievedDocument> rrfFusion(List<RetrievedDocument> semantic,
                                               List<RetrievedDocument> keyword) {
        // 用 documentId 去重，记录在两路中的排名
        Map<String, Integer> semanticRanks = new LinkedHashMap<>();
        Map<String, Integer> keywordRanks = new LinkedHashMap<>();
        Map<String, RetrievedDocument> docMap = new LinkedHashMap<>();

        for (int i = 0; i < semantic.size(); i++) {
            String id = semantic.get(i).getDocumentId();
            semanticRanks.put(id, i + 1); // rank 从 1 开始
            docMap.putIfAbsent(id, semantic.get(i));
        }

        for (int i = 0; i < keyword.size(); i++) {
            String id = keyword.get(i).getDocumentId();
            keywordRanks.put(id, i + 1);
            docMap.putIfAbsent(id, keyword.get(i));
        }

        // 计算 RRF 分数
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        for (String docId : docMap.keySet()) {
            int rankS = semanticRanks.getOrDefault(docId, Integer.MAX_VALUE);
            int rankK = keywordRanks.getOrDefault(docId, Integer.MAX_VALUE);

            double score = semanticWeight / (rrfK + rankS)
                    + keywordWeight / (rrfK + rankK);
            rrfScores.put(docId, score);
        }

        // 按 RRF 分数降序排列
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    RetrievedDocument doc = docMap.get(entry.getKey());
                    doc.setSimilarity(entry.getValue());
                    return doc;
                })
                .toList();
    }
}
