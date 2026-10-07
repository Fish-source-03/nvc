package org.example.agent_qr.rag.retriever;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RerankerService} 测试（批次 10 · 任务 10.3，问题 14）。
 * <p>
 * 拦截的核心缺陷：原实现是纯本地"字符 n-gram + Jaccard"启发式，<b>零网络调用</b>，
 * 模型配置项与权重常量都是死的；接入真实交叉编码器后必须保证：
 * </p>
 * <ol>
 *   <li>模型可用时<b>真的调用模型</b>（不再用词面重叠度冒充语义精排）；</li>
 *   <li>模型不可用/被关闭时降级到启发式，且<b>必须留 WARN 日志</b>（原缺陷的教训就是静默降级）；</li>
 *   <li>权重可配置、topK 语义不变（回归）。</li>
 * </ol>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RerankerServiceTest {

    @Mock
    private RerankerProvider provider;

    private RerankerService service;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        service = new RerankerService(provider);
        ReflectionTestUtils.setField(service, "modelEnabled", true);
        ReflectionTestUtils.setField(service, "originalWeight", 0.4);
        ReflectionTestUtils.setField(service, "textRelevanceWeight", 0.6);
        ReflectionTestUtils.setField(service, "maxCandidates", 32);

        when(provider.name()).thenReturn("mock-provider");

        serviceLogger = (Logger) LoggerFactory.getLogger(RerankerService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("★ 模型可用：按模型分数排序（语义分压过粗排分），组合分 = 0.4×原相似度 + 0.6×模型分")
    void rerank_shouldUseCrossEncoderScores_whenProviderAvailable() {
        // A 的粗排分最高，但模型判定 B 最相关
        List<RetrievedDocument> candidates = List.of(
                doc("a", 0.9, "无关内容"),
                doc("b", 0.1, "员工如何解除劳动合同"),
                doc("c", 0.5, "无关内容"));
        when(provider.rerank(eq("如何离职"), anyList())).thenReturn(List.of(
                new RerankerProvider.Score(0, 0.01),
                new RerankerProvider.Score(1, 0.99),
                new RerankerProvider.Score(2, 0.5)));

        List<RetrievedDocument> result = service.rerank("如何离职", candidates, 2);

        assertThat(result).extracting(RetrievedDocument::getDocumentId).containsExactly("b", "c");
        // 组合分：b = 0.4*0.1 + 0.6*0.99 = 0.634；c = 0.4*0.5 + 0.6*0.5 = 0.5
        assertThat(result.get(0).getSimilarity()).isCloseTo(0.634, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.get(1).getSimilarity()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        verify(provider).rerank(eq("如何离职"), anyList());
    }

    @Test
    @DisplayName("★ 模型调用失败：降级到本地启发式（n-gram + Jaccard）并留下 WARN 日志")
    void rerank_shouldFallbackToHeuristic_withWarnLog_whenProviderFails() {
        when(provider.rerank(any(), anyList()))
                .thenThrow(new RerankerProvider.RerankerException("精排服务不可达: http://localhost:8080"));
        List<RetrievedDocument> candidates = List.of(
                doc("a", 0.9, "毫不相干的内容"),
                doc("b", 0.1, "离职流程与解除劳动合同"));
        ReflectionTestUtils.setField(service, "originalWeight", 0.0);
        ReflectionTestUtils.setField(service, "textRelevanceWeight", 1.0);

        List<RetrievedDocument> result = service.rerank("离职流程", candidates, 1);

        // 启发式命中的是 b（字面重叠高）
        assertThat(result).extracting(RetrievedDocument::getDocumentId).containsExactly("b");
        assertThat(warnMessages())
                .as("降级必须留 WARN（问题 14 的教训：原实现是静默降级）")
                .anySatisfy(message -> assertThat(message)
                        .contains("降级")
                        .contains("精排服务不可达"));
    }

    @Test
    @DisplayName("★ 模型被配置关闭（agent-qr.reranker.enabled=false）：不调用模型，走启发式并留 WARN")
    void rerank_shouldSkipModel_andWarn_whenDisabled() {
        ReflectionTestUtils.setField(service, "modelEnabled", false);
        List<RetrievedDocument> candidates = List.of(
                doc("a", 0.9, "毫不相干的内容"),
                doc("b", 0.1, "离职流程与解除劳动合同"));
        ReflectionTestUtils.setField(service, "originalWeight", 0.0);
        ReflectionTestUtils.setField(service, "textRelevanceWeight", 1.0);

        List<RetrievedDocument> result = service.rerank("离职流程", candidates, 1);

        assertThat(result).extracting(RetrievedDocument::getDocumentId).containsExactly("b");
        verify(provider, never()).rerank(any(), anyList());
        assertThat(warnMessages()).anySatisfy(message ->
                assertThat(message).contains("agent-qr.reranker.enabled=false"));
    }

    @Test
    @DisplayName("★ 权重可参数化：权重置为 1/0 时按粗排分排序，置为 0/1 时按模型分排序")
    void rerank_shouldHonorConfigurableWeights() {
        // 3 条候选 + topK=2：避开"候选数 <= topK 直接返回"的短路分支
        List<RetrievedDocument> candidates = List.of(
                doc("a", 0.9, "content-a"),
                doc("b", 0.5, "content-b"),
                doc("c", 0.1, "content-c"));
        when(provider.rerank(any(), anyList())).thenReturn(List.of(
                new RerankerProvider.Score(0, 0.0),
                new RerankerProvider.Score(1, 0.2),
                new RerankerProvider.Score(2, 1.0)));

        ReflectionTestUtils.setField(service, "originalWeight", 1.0);
        ReflectionTestUtils.setField(service, "textRelevanceWeight", 0.0);
        assertThat(service.rerank("q", candidates, 2))
                .extracting(RetrievedDocument::getDocumentId)
                .as("权重 1/0 = 完全按粗排分（a=0.9 > b=0.5）")
                .containsExactly("a", "b");

        ReflectionTestUtils.setField(service, "originalWeight", 0.0);
        ReflectionTestUtils.setField(service, "textRelevanceWeight", 1.0);
        assertThat(service.rerank("q", candidates, 2))
                .extracting(RetrievedDocument::getDocumentId)
                .as("权重 0/1 = 完全按模型分（c=1.0 > b=0.2）")
                .containsExactly("c", "b");
    }

    @Test
    @DisplayName("回归：返回数量与 topK 一致；候选数 <= topK 时原样返回且不调用模型")
    void rerank_shouldReturnTopK_andSkipModel_whenCandidatesNotExceedTopK() {
        List<RetrievedDocument> fewCandidates = List.of(doc("a", 0.1, "x"), doc("b", 0.2, "y"));
        assertThat(service.rerank("q", fewCandidates, 5)).isSameAs(fewCandidates);
        verify(provider, never()).rerank(any(), anyList());

        List<RetrievedDocument> manyCandidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyCandidates.add(doc("d" + i, 0.1 * i, "content-" + i));
        }
        when(provider.rerank(any(), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            List<RerankerProvider.Score> scores = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                scores.add(new RerankerProvider.Score(i, 1.0 - i * 0.01));
            }
            return scores;
        });

        assertThat(service.rerank("q", manyCandidates, 3)).hasSize(3);
    }

    @Test
    @DisplayName("★ 候选上限：超过 agent-qr.reranker.max-candidates 时只把前 N 条发给模型（TEI 单请求 ≤32 条）")
    void rerank_shouldTruncateCandidatesToMaxCandidates() {
        ReflectionTestUtils.setField(service, "maxCandidates", 32);
        List<RetrievedDocument> candidates = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            candidates.add(doc("d" + i, 1.0 - i * 0.01, "content-" + i));
        }
        when(provider.rerank(any(), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            List<RerankerProvider.Score> scores = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                scores.add(new RerankerProvider.Score(i, 1.0));
            }
            return scores;
        });

        List<RetrievedDocument> result = service.rerank("q", candidates, 5);

        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(provider).rerank(eq("q"), textsCaptor.capture());
        assertThat(textsCaptor.getValue()).hasSize(32);
        assertThat(result).hasSize(5);
        // 截断只取粗排分最高的前 32 条（d49 等低分候选不参与精排）
        assertThat(result).extracting(RetrievedDocument::getDocumentId).doesNotContain("d49");
    }

    @Test
    @DisplayName("空候选与 null 输入不触发模型调用")
    void rerank_shouldHandleEmptyCandidates() {
        assertThat(service.rerank("q", null, 3)).isEmpty();
        assertThat(service.rerank("q", List.of(), 3)).isEmpty();
        verify(provider, never()).rerank(any(), anyList());
    }

    // ==================== 辅助 ====================

    private static RetrievedDocument doc(String id, double similarity, String content) {
        RetrievedDocument document = new RetrievedDocument();
        document.setDocumentId(id);
        document.setSimilarity(similarity);
        document.setContent(content);
        return document;
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
