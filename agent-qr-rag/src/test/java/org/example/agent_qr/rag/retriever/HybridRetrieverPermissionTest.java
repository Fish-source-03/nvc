package org.example.agent_qr.rag.retriever;

import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.StructuredFilterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * {@link HybridRetriever} 检索侧权限兜底测试（批次 03 · 任务 3.5.3，问题 09）。
 * <p>
 * 拦截的核心缺陷：检索层完全按"路由域"过滤，没有任何"当前用户是否有权访问"的概念——
 * 一旦入口鉴权被绕过（或路由降级到全局检索），越域内容会直接进入提示词并输出给用户。
 * </p>
 * <p>
 * 本测试固化"入口校验 + 检索兜底"双保险的第二道：
 * 非 admin 用户的候选集必须被裁剪到 {@code allowedDomains} ∪ {@code department} 之内；
 * 用户没有任何可用域时一律裁空（fail-closed）。
 * </p>
 * <p>
 * 注：本测试只覆盖权限相关裁剪，<b>不</b>断言空候选集守卫逻辑（属批次 04 任务 4.2 的范围）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HybridRetrieverPermissionTest {

    @Mock
    private ChromaRetriever chromaRetriever;

    @Mock
    private BM25Retriever bm25Retriever;

    @Mock
    private RerankerService rerankerService;

    @Mock
    private StructuredFilterService structuredFilterService;

    @InjectMocks
    private HybridRetriever hybridRetriever;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(hybridRetriever, "semanticWeight", 0.55);
        ReflectionTestUtils.setField(hybridRetriever, "keywordWeight", 0.45);
        ReflectionTestUtils.setField(hybridRetriever, "wideTopK", 20);
        ReflectionTestUtils.setField(hybridRetriever, "finalTopK", 5);
        ReflectionTestUtils.setField(hybridRetriever, "rrfK", 15);

        // Rerank 直通：返回融合结果本身，便于断言裁剪后的候选集
        when(rerankerService.rerank(any(), anyList(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private RetrievedDocument doc(long chunkId) {
        RetrievedDocument document = new RetrievedDocument();
        document.setDocumentId("doc-" + chunkId);
        document.setChunkId(chunkId);
        document.setDocumentTitle("title-" + chunkId);
        document.setContent("content-" + chunkId);
        document.setSimilarity(0.9);
        return document;
    }

    private void authenticate(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** HR 部门普通用户：只允许 HR 域。 */
    private UserPrincipal hrUser() {
        UserPrincipal principal = new UserPrincipal();
        principal.setUserId(7L);
        principal.setUsername("chenming");
        principal.setRole("user");
        principal.setDepartment("HR");
        principal.setClearanceLevel(2);
        principal.setAllowedDomains(List.of("HR"));
        principal.setTitle("manager");
        return principal;
    }

    private UserPrincipal admin() {
        UserPrincipal principal = new UserPrincipal();
        principal.setUserId(1L);
        principal.setUsername("admin");
        principal.setRole("admin");
        principal.setDepartment("RD");
        principal.setAllowedDomains(List.of("RD"));
        return principal;
    }

    private DomainRoutingResult routingOf(String domain) {
        DomainRoutingResult routing = new DomainRoutingResult();
        Map<String, Double> matched = new HashMap<>();
        matched.put(domain, 1.0D);
        routing.setMatchedDomains(matched);
        routing.setFallbackToGlobal(false);
        return routing;
    }

    /** 双路召回：chunkId 1/2 属于 HR，99 属于其他域。 */
    private void givenTwoWayRecall() {
        when(chromaRetriever.similaritySearch(any(), anyInt()))
                .thenReturn(List.of(doc(1L), doc(99L)));
        when(bm25Retriever.keywordSearch(any(), anyInt()))
                .thenReturn(List.of(doc(2L), doc(99L)));
    }

    @Test
    @DisplayName("★ 非 admin 用户的检索结果被裁剪到 allowedDomains 之内，越域切片不返回")
    void hybridSearch_shouldCropResultsOutsideAllowedDomains() {
        authenticate(hrUser());
        givenTwoWayRecall();
        when(structuredFilterService.filterChunkIds(eq("HR"), anyList()))
                .thenReturn(List.of(1L, 2L));

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "请假流程", new float[]{0.1f}, routingOf("HR"), List.of());

        assertThat(results).extracting(RetrievedDocument::getChunkId)
                .as("chunkId=99 属于用户无权访问的域，必须被裁掉")
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("★ 即使路由域为空（未限定域/降级全局检索），检索侧仍按用户权限裁剪")
    void hybridSearch_shouldCrop_whenRoutingDomainIsNull() {
        authenticate(hrUser());
        givenTwoWayRecall();
        when(structuredFilterService.filterChunkIds(isNull(), anyList())).thenReturn(List.of());
        when(structuredFilterService.filterChunkIds(eq("HR"), anyList()))
                .thenReturn(List.of(1L, 2L));

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "请假流程", new float[]{0.1f}, DomainRoutingResult.fallback(), List.of());

        assertThat(results).extracting(RetrievedDocument::getChunkId)
                .as("不依赖入口鉴权的独立兜底：路由域缺失也不得返回越域内容")
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("admin 不做权限裁剪（设计 §3.2.9：admin 全权限）")
    void hybridSearch_shouldNotCropForAdmin() {
        authenticate(admin());
        givenTwoWayRecall();
        when(structuredFilterService.filterChunkIds(isNull(), anyList())).thenReturn(List.of());

        // 用 fallback 路由隔离出"权限兜底"这一步：路由域为空时，唯一可能裁剪结果的就是权限兜底
        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "全库检索", new float[]{0.1f}, DomainRoutingResult.fallback(), List.of());

        assertThat(results).extracting(RetrievedDocument::getChunkId)
                .contains(99L);
    }

    @Test
    @DisplayName("用户没有任何可用域时一律裁空（fail-closed，宁可无结果也不越权返回）")
    void hybridSearch_shouldCropEverything_whenUserHasNoDomain() {
        UserPrincipal noDomainUser = hrUser();
        noDomainUser.setAllowedDomains(List.of());
        noDomainUser.setDepartment(null);
        authenticate(noDomainUser);
        givenTwoWayRecall();

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "越权尝试", new float[]{0.1f}, routingOf("HR"), List.of());

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("裁剪同时作用于语义路与关键词路（两路都不能漏）")
    void hybridSearch_shouldCropBothRetrievalPaths() {
        authenticate(hrUser());
        when(chromaRetriever.similaritySearch(any(), anyInt()))
                .thenReturn(List.of(doc(1L), doc(99L)));
        when(bm25Retriever.keywordSearch(any(), anyInt()))
                .thenReturn(List.of(doc(98L), doc(99L)));
        when(structuredFilterService.filterChunkIds(eq("HR"), anyList()))
                .thenReturn(List.of(1L));

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "请假流程", new float[]{0.1f}, routingOf("HR"), List.of());

        assertThat(results).extracting(RetrievedDocument::getChunkId).containsExactly(1L);
    }
}
