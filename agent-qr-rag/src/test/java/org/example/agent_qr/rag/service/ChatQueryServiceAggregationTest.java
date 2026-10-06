package org.example.agent_qr.rag.service;

import org.example.agent_qr.rag.circuitbreaker.LLMCircuitBreaker;
import org.example.agent_qr.rag.entity.Message;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.intent.QueryIntentClassifier;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.rag.prompt.PromptTemplate;
import org.example.agent_qr.rag.provider.EmbeddingProvider;
import org.example.agent_qr.rag.provider.LLMProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.example.agent_qr.rag.retriever.HybridRetriever;
import org.example.agent_qr.rag.util.ContextTokenManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatQueryService} 聚合查询分流测试（批次 04 · 任务 4.4.5 / 4.4.6，问题 13）。
 * <p>
 * 拦截的核心缺陷：列举/统计类查询只有一条"取最相关 Top-K"的路径，
 * "超过 15 人只返回 15 人"且<b>没有任何提示</b>。
 * </p>
 * <p>
 * 本测试固化：① 聚合类问题走全量取回路径（不经过 HybridRetriever / final-top-k）；
 * ② 结果被 Token 预算裁剪时回答里出现"结果可能不完整"；
 * ③ 域内无记录 → 返回空，不回退全库（与任务 4.2 语义一致）；
 * ④ 语义类问题路径不受影响（回归）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatQueryServiceAggregationTest {

    @Mock
    private ProviderFactory providerFactory;
    @Mock
    private HybridRetriever hybridRetriever;
    @Mock
    private PromptTemplate promptTemplate;
    @Mock
    private ConversationService conversationService;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private LLMCircuitBreaker circuitBreaker;
    @Mock
    private ContextTokenManager contextTokenManager;
    @Mock
    private EmbeddingProvider embeddingProvider;
    @Mock
    private QueryIntentClassifier queryIntentClassifier;
    @Mock
    private AggregationQueryService aggregationQueryService;
    @Mock
    private org.example.agent_qr.rag.filter.FilterConditionExtractor filterConditionExtractor;
    @Mock
    private LLMProvider llmProvider;

    private ChatQueryService chatQueryService;

    @BeforeEach
    void setUp() {
        chatQueryService = new ChatQueryService(providerFactory, hybridRetriever, promptTemplate,
                conversationService, messageMapper, eventPublisher, circuitBreaker, contextTokenManager);
        ReflectionTestUtils.setField(chatQueryService, "queryIntentClassifier", queryIntentClassifier);
        ReflectionTestUtils.setField(chatQueryService, "aggregationQueryService", aggregationQueryService);
        ReflectionTestUtils.setField(chatQueryService, "filterConditionExtractor", filterConditionExtractor);

        when(providerFactory.getEmbeddingProvider()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(conversationService.createConversation(anyLong(), any())).thenReturn(100L);
        when(circuitBreaker.getActiveProvider()).thenReturn(llmProvider);
        when(promptTemplate.getSystemPromptBase()).thenReturn("base");
        when(promptTemplate.buildSystemPrompt(any())).thenReturn("semantic-system");
        when(promptTemplate.getAggregationPromptBase()).thenReturn("aggregation-base");
        when(promptTemplate.buildAggregationSystemPrompt(any())).thenReturn("aggregation-system");
        when(contextTokenManager.buildContextWithBudget(any(), any(), any())).thenReturn("context");
    }

    private List<RetrievedDocument> documents(int count) {
        List<RetrievedDocument> documents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RetrievedDocument document = new RetrievedDocument();
            document.setDocumentId("chunk-" + i);
            document.setChunkId((long) i);
            document.setDocumentTitle("title-" + i);
            document.setContent("record-" + i);
            document.setSimilarity(1.0);
            documents.add(document);
        }
        return documents;
    }

    private void givenAggregation(String query, List<RetrievedDocument> documents,
                                  int includedCount) {
        when(queryIntentClassifier.classify(any())).thenReturn(QueryIntentClassifier.IntentType.AGGREGATION);
        when(aggregationQueryService.aggregate(any(), any(), any()))
                .thenReturn(AggregationQueryService.AggregationResult.of(documents));
        when(contextTokenManager.buildAggregationContext(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new ContextTokenManager.AggregationContext(
                        "agg-context", documents.size(), includedCount));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sourcesOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("sources");
    }

    @Test
    @DisplayName("★ 聚合类问题走全量取回路径：40 条记录不被 final-top-k 截断")
    void ask_shouldUseAggregationPath_forListQuery() {
        givenAggregation("列出所有离职员工", documents(40), 40);
        when(llmProvider.generate(any())).thenReturn("共有 40 人");

        Map<String, Object> result = chatQueryService.ask("列出所有离职员工", null, 7L, "HR");

        assertThat(result.get("answer")).isEqualTo("共有 40 人");
        assertThat(sourcesOf(result))
                .as("来源列表应覆盖全部匹配记录（无 Token 裁剪时）")
                .hasSize(40);
        verify(hybridRetriever, never()).hybridSearch(any(), any(), any(), any());
    }

    @Test
    @DisplayName("聚合路径下过滤条件只提取一次（提取结果由 ChatQueryService 传入聚合服务）")
    void ask_shouldExtractConditionsOnlyOnce_whenAggregationPathIsUsed() {
        givenAggregation("列出所有离职员工", documents(3), 3);
        when(llmProvider.generate(any())).thenReturn("答案");

        chatQueryService.ask("列出所有离职员工", null, 7L, "HR");

        verify(filterConditionExtractor, times(1)).extract("列出所有离职员工", "HR");
        // 提取结果被下传（而不是聚合服务内部再提取一次）
        verify(aggregationQueryService).aggregate(any(), any(), any());
    }

    @Test
    @DisplayName("★ Token 预算裁剪时：回答显式标注「结果可能不完整 x/y 条」")
    void ask_shouldAppendTruncationNotice_whenContextIsTruncated() {
        givenAggregation("列出所有离职员工", documents(40), 10);
        when(llmProvider.generate(any())).thenReturn("部分名单如下");

        Map<String, Object> result = chatQueryService.ask("列出所有离职员工", null, 7L, "HR");

        String answer = (String) result.get("answer");
        assertThat(answer)
                .as("用户必须能区分'只有 10 条'与'显示了前 10 条'")
                .contains("结果可能不完整")
                .contains("10 条 / 共 40 条");
        assertThat(sourcesOf(result)).hasSize(10);
    }

    @Test
    @DisplayName("★ 聚合路径域内无记录 → 回答'未找到匹配记录'，不回退全库检索")
    void ask_shouldReturnEmpty_whenAggregationFindsNothing() {
        givenAggregation("列出所有离职员工", List.of(), 0);

        Map<String, Object> result = chatQueryService.ask("列出所有离职员工", null, 7L, "HR");

        assertThat(result.get("answer")).isEqualTo("未找到匹配记录");
        verify(hybridRetriever, never()).hybridSearch(any(), any(), any(), any());
        verify(llmProvider, never()).generate(any());
    }

    @Test
    @DisplayName("语义类问题路径不受影响（回归：仍走混合检索 + 相关性排序）")
    void ask_shouldUseSemanticPath_forProcessQuestion() {
        when(queryIntentClassifier.classify(any())).thenReturn(QueryIntentClassifier.IntentType.SEMANTIC);
        when(hybridRetriever.hybridSearch(any(), any(), any(), any())).thenReturn(documents(3));
        when(llmProvider.generate(any())).thenReturn("离职流程如下");

        Map<String, Object> result = chatQueryService.ask("离职流程是什么", null, 7L, "HR");

        assertThat(result.get("answer")).isEqualTo("离职流程如下");
        assertThat(sourcesOf(result)).hasSize(3);
        verify(aggregationQueryService, never()).aggregate(any(), any(), any());
    }

    @Test
    @DisplayName("聚合路径不可用（无结构化条件等）→ 自动降级语义路径")
    void ask_shouldFallBackToSemantic_whenAggregationNotApplicable() {
        when(queryIntentClassifier.classify(any())).thenReturn(QueryIntentClassifier.IntentType.AGGREGATION);
        when(aggregationQueryService.aggregate(any(), any(), any()))
                .thenReturn(AggregationQueryService.AggregationResult.notApplicable());
        when(hybridRetriever.hybridSearch(any(), any(), any(), any())).thenReturn(documents(2));
        when(llmProvider.generate(any())).thenReturn("语义答案");

        Map<String, Object> result = chatQueryService.ask("列出所有员工", null, 7L, "HR");

        assertThat(result.get("answer")).isEqualTo("语义答案");
    }

    @Test
    @DisplayName("聚合路径异常 → 降级语义路径，不阻塞问答")
    void ask_shouldFallBackToSemantic_whenAggregationThrows() {
        when(queryIntentClassifier.classify(any())).thenReturn(QueryIntentClassifier.IntentType.AGGREGATION);
        when(aggregationQueryService.aggregate(any(), any(), any())).thenThrow(new RuntimeException("db down"));
        when(hybridRetriever.hybridSearch(any(), any(), any(), any())).thenReturn(documents(2));
        when(llmProvider.generate(any())).thenReturn("语义答案");

        assertThat(chatQueryService.ask("列出所有员工", null, 7L, "HR").get("answer"))
                .isEqualTo("语义答案");
    }

    @Test
    @DisplayName("★ 流式链路同样走聚合路径，且把「结果可能不完整」写入回答")
    void askStream_shouldAppendTruncationNotice_forAggregation() {
        givenAggregation("列出所有离职员工", documents(40), 10);
        when(llmProvider.generateStream(any())).thenReturn(Flux.just("部分", "名单"));

        chatQueryService.askStream("列出所有离职员工", 100L, 7L, "HR", new SseEmitter());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper, atLeastOnce()).insert(captor.capture());
        Message assistantMessage = captor.getAllValues().stream()
                .filter(message -> "assistant".equals(message.getRole()))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(assistantMessage.getContent())
                .contains("部分名单")
                .contains("结果可能不完整")
                .contains("10 条 / 共 40 条");
    }
}
