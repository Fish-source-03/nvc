package org.example.agent_qr.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.FilterCondition;
import org.example.agent_qr.rag.filter.StructuredFilterService;
import org.example.agent_qr.rag.filter.mapper.ChunkStructuredFilterMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 聚合查询编排服务（批次 04 · 任务 4.4.3，问题 13）。
 * <p>
 * 面向「列举 / 统计」类查询的另一条检索路径：
 * </p>
 * <pre>
 *   FilterConditionExtractor（提取条件，由 ChatQueryService 提取一次后传入）
 *     → StructuredFilterService.filterChunkIdsUnbounded（SQL 无界查询，安全上限 2000）
 *     → ChunkStructuredFilterMapper.selectChunkContentsByIds（批量取回切片内容）
 *     → 返回「全部匹配记录」
 * </pre>
 * <p>
 * 该路径<b>跳过 HybridRetriever 与 Reranker</b>，因此不受 {@code final-top-k} / RRF 相关性排序的截断；
 * 剩余的唯一截断点是上下文 Token 预算（L4，由 {@code ContextTokenManager} 负责并会显式标注）。
 * </p>
 * <p>
 * <b>空集语义（与任务 4.2 一致）</b>：目标域内无匹配记录时返回「适用且为空」的结果，
 * 由调用方回答「未找到匹配记录」，<b>不回退到全库检索</b>；只有当聚合路径本身不可用
 * （无明确域 / 无结构化条件 / 无权访问该域）时才返回 {@code applicable=false}，交由语义路径处理。
 * </p>
 * <p>
 * <b>条件来源</b>：本服务不再自行提取条件，改由 {@link ChatQueryService} 统一提取一次后传入，
 * 保证「同一问题只提取一次」——避免"列举类但无结构化条件"的提问在降级路径上重复调用 LLM。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class AggregationQueryService {

    @Autowired
    private StructuredFilterService structuredFilterService;

    @Autowired
    private ChunkStructuredFilterMapper chunkStructuredFilterMapper;

    /**
     * 聚合查询结果。
     *
     * @param applicable  聚合路径是否可用（false = 交回语义路径）
     * @param documents   匹配记录（{@code applicable=true} 时可能为空 = 域内无匹配记录）
     */
    public record AggregationResult(boolean applicable, List<RetrievedDocument> documents) {

        /** 聚合路径不可用（降级语义路径）。 */
        public static AggregationResult notApplicable() {
            return new AggregationResult(false, List.of());
        }

        /** 聚合路径可用（{@code documents} 为空表示域内无匹配记录，属正常结果）。 */
        public static AggregationResult of(List<RetrievedDocument> documents) {
            return new AggregationResult(true, documents == null ? List.of() : documents);
        }
    }

    /**
     * 执行聚合查询：取出目标域内满足结构化条件的<b>全部</b>记录。
     * <p>
     * ★ 过滤条件由调用方（{@link ChatQueryService}）提取后传入——
     * 「同一问题只提取一次」是硬要求：聚合路径与降级后的语义路径共用同一次提取结果，
     * 否则"列举类但无结构化条件"的提问会白付一次 LLM 调用。
     * </p>
     *
     * @param query      用户自然语言问题（仅用于日志）
     * @param routing    域路由结果（域必须明确，否则不适用）
     * @param conditions 已提取的结构化过滤条件（空 = 无法聚合，交回语义路径）
     * @return 聚合结果；不可用时 {@code applicable=false}
     */
    public AggregationResult aggregate(String query, DomainRoutingResult routing,
                                       List<FilterCondition> conditions) {
        if (routing == null || routing.isFallbackToGlobal()) {
            log.debug("聚合查询：无明确业务域（全局降级路由），不适用");
            return AggregationResult.notApplicable();
        }
        String domain = routing.getPrimaryDomain();
        if (domain == null || domain.isBlank()) {
            log.debug("聚合查询：路由结果为空域，不适用");
            return AggregationResult.notApplicable();
        }
        // 权限兜底：聚合路径跳过了 HybridRetriever，需独立校验当前用户是否有权访问该域
        if (!isDomainPermitted(domain)) {
            log.warn("聚合查询：当前用户无权访问域 {}，不适用（交回语义路径，由其权限兜底裁剪）", domain);
            return AggregationResult.notApplicable();
        }
        if (conditions == null || conditions.isEmpty()) {
            log.info("聚合查询：无结构化过滤条件，降级语义路径（复用同一次提取结果）: domain={}, query={}",
                    domain, query);
            return AggregationResult.notApplicable();
        }

        // 1. 无界查询全部匹配 chunkId（安全上限由 agent-qr.aggregation.max-chunk-ids 控制）
        List<Long> chunkIds;
        try {
            chunkIds = structuredFilterService.filterChunkIdsUnbounded(domain, conditions);
        } catch (Exception e) {
            log.warn("聚合查询：无界结构化查询失败，降级语义路径: domain={}", domain, e);
            return AggregationResult.notApplicable();
        }
        log.info("聚合查询：域 {} 匹配 chunk 数={}, 条件数={}", domain, chunkIds.size(), conditions.size());

        if (chunkIds.isEmpty()) {
            // 与任务 4.2 一致：域内无匹配记录 → 返回空，不回退全库
            log.info("聚合查询：域 {} 内无匹配记录，返回空结果（不扩大检索范围）", domain);
            return AggregationResult.of(List.of());
        }

        // 3. 批量取回切片内容
        List<RetrievedDocument> documents;
        try {
            documents = chunkStructuredFilterMapper.selectChunkContentsByIds(chunkIds);
        } catch (Exception e) {
            log.warn("聚合查询：批量取回切片内容失败，降级语义路径: domain={}", domain, e);
            return AggregationResult.notApplicable();
        }
        if (documents == null || documents.isEmpty()) {
            log.info("聚合查询：域 {} 匹配的切片均已删除或不可读，返回空结果", domain);
            return AggregationResult.of(List.of());
        }

        List<RetrievedDocument> filled = new ArrayList<>(documents.size());
        for (RetrievedDocument doc : documents) {
            if (doc.getChunkId() != null) {
                doc.setDocumentId(String.valueOf(doc.getChunkId()));
            }
            if (doc.getDocumentTitle() == null) {
                doc.setDocumentTitle("chunk-" + doc.getChunkId());
            }
            doc.setSimilarity(1.0); // 聚合路径无相关性排序
            filled.add(doc);
        }
        log.info("聚合查询完成: domain={}, 返回记录数={}", domain, filled.size());
        return AggregationResult.of(filled);
    }

    /**
     * 聚合路径的权限兜底。
     * <p>
     * 规则与 {@code HybridRetriever} Step 1.6 保持一致：{@code allowedDomains} ∪ {@code department}，
     * admin 直通，未认证不裁剪（由入口鉴权负责）。
     * </p>
     */
    private boolean isDomainPermitted(String domain) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return true;
        }
        if (principal.isAdmin()) {
            return true;
        }
        Set<String> permittedDomains = new HashSet<>();
        if (principal.getAllowedDomains() != null) {
            principal.getAllowedDomains().stream()
                    .filter(StringUtils::hasText)
                    .forEach(permittedDomains::add);
        }
        if (StringUtils.hasText(principal.getDepartment())) {
            permittedDomains.add(principal.getDepartment());
        }
        return permittedDomains.contains(domain);
    }
}
