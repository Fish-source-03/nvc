package org.example.agent_qr.rag.service;

import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.FilterCondition;
import org.example.agent_qr.rag.filter.StructuredFilterService;
import org.example.agent_qr.rag.filter.mapper.ChunkStructuredFilterMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AggregationQueryService} 测试（批次 04 · 任务 4.4.3，问题 13）。
 * <p>
 * 拦截的核心缺陷：列举类查询被 {@code final-top-k} 截断（"超过 15 人只返回 15 人"）。
 * 聚合路径改为「SQL 无界查询 → 全量取回 → 紧凑格式化」，本测试固化：
 * ① 返回全部匹配记录（超过 final-top-k 的数量也不截断）；
 * ② 域内无匹配记录 → 返回空（与任务 4.2 空集语义一致，不回退全库）；
 * ③ 路径不可用（无明确域 / 无结构化条件 / 无权访问该域 / 异常）→ 交回语义路径。
 * </p>
 * <p>
 * 注：过滤条件由 {@link ChatQueryService} 提取一次后传入（"只提取一次"由
 * {@code ChatQueryServiceExtractionOnceTest} 覆盖），本类只验证消费侧语义。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AggregationQueryServiceTest {

    @Mock
    private StructuredFilterService structuredFilterService;

    @Mock
    private ChunkStructuredFilterMapper chunkStructuredFilterMapper;

    @InjectMocks
    private AggregationQueryService aggregationQueryService;

    /** 调用方（ChatQueryService）已提取好的条件。 */
    private static final List<FilterCondition> CONDITIONS = List.of(
            FilterCondition.builder().fieldName("department").fieldType("STRING")
                    .operator(FilterCondition.OP_EQ).value("RD").build());

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private DomainRoutingResult routingOf(String domain) {
        DomainRoutingResult routing = new DomainRoutingResult();
        Map<String, Double> matched = new HashMap<>();
        matched.put(domain, 1.0D);
        routing.setMatchedDomains(matched);
        routing.setFallbackToGlobal(false);
        return routing;
    }

    private List<RetrievedDocument> chunks(int count) {
        List<RetrievedDocument> documents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RetrievedDocument document = new RetrievedDocument();
            document.setChunkId(1000L + i);
            document.setContent("username=user" + i + ";department=RD");
            documents.add(document);
        }
        return documents;
    }

    private void authenticate(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    @DisplayName("★ 聚合查询返回全部匹配记录（40 条 > final-top-k 30，不截断）")
    void aggregate_shouldReturnAllMatchingRecords_beyondFinalTopK() {
        List<Long> chunkIds = chunks(40).stream().map(RetrievedDocument::getChunkId).toList();
        when(structuredFilterService.filterChunkIdsUnbounded(eq("HR"), anyList())).thenReturn(chunkIds);
        when(chunkStructuredFilterMapper.selectChunkContentsByIds(chunkIds)).thenReturn(chunks(40));

        AggregationQueryService.AggregationResult result =
                aggregationQueryService.aggregate("列出所有研发部员工", routingOf("HR"), CONDITIONS);

        assertThat(result.applicable()).isTrue();
        assertThat(result.documents())
                .as("列举型查询必须返回全部匹配记录，不受 Rerank/TOP-K 截断")
                .hasSize(40);
        assertThat(result.documents()).allSatisfy(document -> {
            assertThat(document.getDocumentId()).isNotNull();
            assertThat(document.getSimilarity()).isEqualTo(1.0);
        });
        verify(structuredFilterService).filterChunkIdsUnbounded("HR", CONDITIONS);
    }

    @Test
    @DisplayName("★ 域内无匹配记录 → 返回空结果，不回退全库（与任务 4.2 空集语义一致）")
    void aggregate_shouldReturnEmpty_whenDomainHasNoMatchingRecords() {
        when(structuredFilterService.filterChunkIdsUnbounded(eq("HR"), anyList())).thenReturn(List.of());

        AggregationQueryService.AggregationResult result =
                aggregationQueryService.aggregate("列出所有离职员工", routingOf("HR"), CONDITIONS);

        assertThat(result.applicable())
                .as("路径可用但无数据，属正常结果（调用方返回'未找到匹配记录'），不得降级为全库检索")
                .isTrue();
        assertThat(result.documents()).isEmpty();
        verify(chunkStructuredFilterMapper, never()).selectChunkContentsByIds(anyList());
    }

    @Test
    @DisplayName("无结构化过滤条件（如开关关闭或提取为空）→ 不适用，交回语义路径")
    void aggregate_shouldBeNotApplicable_whenNoConditionsGiven() {
        AggregationQueryService.AggregationResult result =
                aggregationQueryService.aggregate("列出所有员工", routingOf("HR"), List.of());

        assertThat(result.applicable()).isFalse();
        verifyNoInteractions(structuredFilterService);
    }

    @Test
    @DisplayName("路由降级到全局检索（无明确域）→ 不适用，不做跨域全量取回")
    void aggregate_shouldBeNotApplicable_whenRoutingIsGlobalFallback() {
        AggregationQueryService.AggregationResult result =
                aggregationQueryService.aggregate("列出所有员工", DomainRoutingResult.fallback(), CONDITIONS);

        assertThat(result.applicable()).isFalse();
        verifyNoInteractions(structuredFilterService);
    }

    @Test
    @DisplayName("★ 用户无权访问该域 → 不适用（聚合路径跳过 HybridRetriever，必须独立鉴权）")
    void aggregate_shouldBeNotApplicable_whenUserHasNoDomainAccess() {
        UserPrincipal hrUser = new UserPrincipal();
        hrUser.setUserId(7L);
        hrUser.setUsername("chenming");
        hrUser.setRole("user");
        hrUser.setDepartment("HR");
        hrUser.setAllowedDomains(List.of("HR"));
        authenticate(hrUser);

        AggregationQueryService.AggregationResult result =
                aggregationQueryService.aggregate("列出所有财务记录", routingOf("FINANCE"), CONDITIONS);

        assertThat(result.applicable())
                .as("越域提问不得通过聚合路径拿到全量记录")
                .isFalse();
        verifyNoInteractions(structuredFilterService);
    }

    @Test
    @DisplayName("admin 不做域裁剪（与检索侧权限兜底规则一致）")
    void aggregate_shouldAllowAdmin() {
        UserPrincipal admin = new UserPrincipal();
        admin.setUserId(1L);
        admin.setUsername("admin");
        admin.setRole("admin");
        admin.setAllowedDomains(List.of("RD"));
        authenticate(admin);

        when(structuredFilterService.filterChunkIdsUnbounded(eq("HR"), anyList())).thenReturn(List.of(1000L));
        when(chunkStructuredFilterMapper.selectChunkContentsByIds(List.of(1000L))).thenReturn(chunks(1));

        assertThat(aggregationQueryService.aggregate("列出研发部员工", routingOf("HR"), CONDITIONS).applicable())
                .isTrue();
    }

    @Test
    @DisplayName("无界查询异常 → 不适用（降级语义路径）")
    void aggregate_shouldBeNotApplicable_whenUnboundedQueryFails() {
        when(structuredFilterService.filterChunkIdsUnbounded(eq("HR"), anyList()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(aggregationQueryService.aggregate("列出所有员工", routingOf("HR"), CONDITIONS).applicable())
                .isFalse();
    }

    @Test
    @DisplayName("切片内容批量取回为空（已删除）→ 返回空结果")
    void aggregate_shouldReturnEmpty_whenChunksAreDeleted() {
        when(structuredFilterService.filterChunkIdsUnbounded(eq("HR"), anyList())).thenReturn(List.of(1000L));
        when(chunkStructuredFilterMapper.selectChunkContentsByIds(List.of(1000L))).thenReturn(List.of());

        AggregationQueryService.AggregationResult result =
                aggregationQueryService.aggregate("列出所有员工", routingOf("HR"), CONDITIONS);

        assertThat(result.applicable()).isTrue();
        assertThat(result.documents()).isEmpty();
    }
}
