package org.example.agent_qr.rag.retriever;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link HybridRetriever} 域过滤空集守卫测试（批次 04 · 任务 4.2，问题 19）。
 * <p>
 * 拦截的核心缺陷：旧守卫是 {@code if (hasDomainFilter && !candidateChunkIds.isEmpty())}——
 * 当业务域内没有结构化候选数据时，整个过滤块被跳过，<b>退化为全库检索</b>，
 * 用户以为在域 A 内提问却拿到域 B 的内容（设计 §8.11.3 要求候选集为空时直接返回空）。
 * </p>
 * <p>
 * 本测试固化三条语义：
 * <ol>
 *   <li>指定域 + 空候选集 → 返回空（不回退全库，且不再做无谓的双路召回）；</li>
 *   <li>指定域 + 非空候选集 → 正常按候选集裁剪；</li>
 *   <li>未指定域 → 不做域过滤（全库检索是设计预期行为）。</li>
 * </ol>
 * </p>
 * <p>
 * 注：批次 03 任务 3.5 的<b>权限</b>兜底（allowedDomains 裁剪）另见
 * {@link HybridRetrieverPermissionTest}，两者层次不同、并存。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HybridRetrieverDomainFilterTest {

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

    private Logger retrieverLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(hybridRetriever, "semanticWeight", 0.55);
        ReflectionTestUtils.setField(hybridRetriever, "keywordWeight", 0.45);
        ReflectionTestUtils.setField(hybridRetriever, "wideTopK", 20);
        ReflectionTestUtils.setField(hybridRetriever, "finalTopK", 30);
        ReflectionTestUtils.setField(hybridRetriever, "rrfK", 15);
        when(rerankerService.rerank(any(), anyList(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        retrieverLogger = (Logger) LoggerFactory.getLogger(HybridRetriever.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        retrieverLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        retrieverLogger.detachAppender(logAppender);
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

    private DomainRoutingResult routingOf(String domain) {
        DomainRoutingResult routing = new DomainRoutingResult();
        Map<String, Double> matched = new HashMap<>();
        matched.put(domain, 1.0D);
        routing.setMatchedDomains(matched);
        routing.setFallbackToGlobal(false);
        return routing;
    }

    private boolean warnedAbout(String fragment) {
        return logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains(fragment));
    }

    private void givenTwoWayRecall() {
        when(chromaRetriever.similaritySearch(any(), anyInt()))
                .thenReturn(List.of(doc(1L), doc(99L)));
        when(bm25Retriever.keywordSearch(any(), anyInt()))
                .thenReturn(List.of(doc(2L), doc(99L)));
    }

    @Test
    @DisplayName("★ 指定域且候选集为空 → 返回空结果，不得退化为全库检索（问题 19）")
    void hybridSearch_shouldReturnEmpty_whenCandidateSetIsEmpty() {
        givenTwoWayRecall();
        when(structuredFilterService.filterChunkIds(eq("FINANCE"), anyList())).thenReturn(List.of());

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "报销标准", new float[]{0.1f}, routingOf("FINANCE"), List.of());

        assertThat(results)
                .as("域内无结构化候选数据 = 域内没有答案；返回其他域的内容是跨域越权")
                .isEmpty();
        verifyNoInteractions(chromaRetriever, bm25Retriever, rerankerService);
    }

    @Test
    @DisplayName("★ 空候选集分支必须输出 WARN 日志（便于发现'某域长期无数据'）")
    void hybridSearch_shouldWarn_whenCandidateSetIsEmpty() {
        when(structuredFilterService.filterChunkIds(eq("FINANCE"), anyList())).thenReturn(List.of());

        hybridRetriever.hybridSearch("报销标准", new float[]{0.1f}, routingOf("FINANCE"), List.of());

        assertThat(warnedAbout("无结构化候选数据"))
                .as("旧实现在被跳过的块内仅 log.debug，运行期完全静默")
                .isTrue();
    }

    @Test
    @DisplayName("指定域且候选集非空 → 正常按候选集裁剪两路召回")
    void hybridSearch_shouldFilterResults_whenCandidateSetIsNotEmpty() {
        givenTwoWayRecall();
        when(structuredFilterService.filterChunkIds(eq("HR"), anyList())).thenReturn(List.of(1L, 2L));

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "请假流程", new float[]{0.1f}, routingOf("HR"), List.of());

        assertThat(results).extracting(RetrievedDocument::getChunkId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("未指定域 → 不做域过滤，全库检索（设计预期，行为不变）")
    void hybridSearch_shouldNotFilter_whenDomainIsNotSpecified() {
        givenTwoWayRecall();

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "公司制度", new float[]{0.1f}, DomainRoutingResult.fallback(), List.of());

        assertThat(results).extracting(RetrievedDocument::getChunkId)
                .containsExactlyInAnyOrder(1L, 2L, 99L);
    }
}
