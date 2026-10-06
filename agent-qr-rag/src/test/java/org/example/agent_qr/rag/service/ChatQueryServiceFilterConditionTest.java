package org.example.agent_qr.rag.service;

import org.example.agent_qr.rag.circuitbreaker.LLMCircuitBreaker;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.FilterCondition;
import org.example.agent_qr.rag.filter.FilterConditionExtractor;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatQueryService} 结构化过滤条件接入测试（批次 04 · 任务 4.3.2 / 4.3.4，问题 12）。
 * <p>
 * 拦截的核心缺陷：两个调用点（{@code ask} / {@code askStream}）恒传 {@code List.of()}，
 * 过滤链路"接了线没通电"——用户用自然语言表达的数值/枚举条件完全无效。
 * </p>
 * <p>
 * 本测试固化：① 提取结果被真实传入检索层（收窄候选集）；
 * ② 灰度开关关闭（提取器返回空）时传入空列表，行为与修复前一致；
 * ③ 提取异常时降级为无过滤条件，<b>问答照常返回结果</b>；
 * ④ 无明确域（全局降级路由）时不触发提取。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatQueryServiceFilterConditionTest {

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
    private FilterConditionExtractor filterConditionExtractor;
    @Mock
    private LLMProvider llmProvider;

    private ChatQueryService chatQueryService;

    private static final FilterCondition CONDITION = FilterCondition.builder()
            .fieldName("clearance_level").fieldType("NUMBER")
            .operator(FilterCondition.OP_GT).value("10000")
            .build();

    @BeforeEach
    void setUp() {
        chatQueryService = new ChatQueryService(providerFactory, hybridRetriever, promptTemplate,
                conversationService, messageMapper, eventPublisher, circuitBreaker, contextTokenManager);
        ReflectionTestUtils.setField(chatQueryService, "filterConditionExtractor", filterConditionExtractor);

        when(providerFactory.getEmbeddingProvider()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(conversationService.createConversation(anyLong(), any())).thenReturn(100L);
        when(circuitBreaker.getActiveProvider()).thenReturn(llmProvider);
        when(promptTemplate.getSystemPromptBase()).thenReturn("base");
        when(promptTemplate.buildSystemPrompt(any())).thenReturn("system");
        when(contextTokenManager.buildContextWithBudget(any(), any(), any())).thenReturn("context");
        when(hybridRetriever.hybridSearch(any(), any(), any(), any())).thenReturn(List.of(document()));
        when(llmProvider.generate(any())).thenReturn("答案");
    }

    private RetrievedDocument document() {
        RetrievedDocument document = new RetrievedDocument();
        document.setDocumentId("chunk-1");
        document.setChunkId(1L);
        document.setDocumentTitle("title-1");
        document.setContent("content-1");
        document.setSimilarity(0.9);
        return document;
    }

    @SuppressWarnings("unchecked")
    private List<FilterCondition> capturedConditions() {
        ArgumentCaptor<List<FilterCondition>> captor = ArgumentCaptor.forClass(List.class);
        verify(hybridRetriever).hybridSearch(any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("★ 提取出的过滤条件被下传到检索层（检索范围被收窄）")
    void ask_shouldPassExtractedConditionsToRetrieval() {
        when(filterConditionExtractor.extract("月薪大于 1 万的研发部员工有哪些", "HR"))
                .thenReturn(List.of(CONDITION));

        chatQueryService.ask("月薪大于 1 万的研发部员工有哪些", 100L, 7L, "HR");

        assertThat(capturedConditions())
                .as("调用点不得再恒传 List.of()")
                .containsExactly(CONDITION);
        verify(filterConditionExtractor).extract("月薪大于 1 万的研发部员工有哪些", "HR");
    }

    @Test
    @DisplayName("★ 灰度开关关闭（提取器返回空列表）→ 检索行为与修复前一致")
    void ask_shouldPassEmptyConditions_whenGraySwitchDisabled() {
        when(filterConditionExtractor.extract(any(), any())).thenReturn(List.of());

        Map<String, Object> result = chatQueryService.ask("请假流程是什么", 100L, 7L, "HR");

        assertThat(capturedConditions()).isEmpty();
        assertThat(result.get("answer")).isEqualTo("答案");
    }

    @Test
    @DisplayName("★ 提取失败（超时/异常）→ 降级为无过滤条件，问答仍能返回结果")
    void ask_shouldContinue_whenExtractionFails() {
        when(filterConditionExtractor.extract(any(), any()))
                .thenThrow(new RuntimeException("llm timeout"));

        Map<String, Object> result = chatQueryService.ask("月薪大于 1 万的员工", 100L, 7L, "HR");

        assertThat(capturedConditions()).isEmpty();
        assertThat(result.get("answer"))
                .as("提取失败绝不能阻塞问答主流程")
                .isEqualTo("答案");
    }

    @Test
    @DisplayName("无明确域（全局降级路由）→ 不触发提取（无字段可依据）")
    void ask_shouldNotExtract_whenRoutingIsGlobalFallback() {
        chatQueryService.ask("月薪大于 1 万的员工", 100L, 7L, null);

        verify(filterConditionExtractor, never()).extract(any(), any());
        assertThat(capturedConditions()).isEmpty();
    }

    @Test
    @DisplayName("★ 流式链路同样把过滤条件下传到检索层（两条链路一致）")
    void askStream_shouldPassExtractedConditionsToRetrieval() {
        when(filterConditionExtractor.extract(any(), eq("HR"))).thenReturn(List.of(CONDITION));
        when(llmProvider.generateStream(any())).thenReturn(Flux.just("答案"));

        chatQueryService.askStream("月薪大于 1 万的员工", 100L, 7L, "HR", new SseEmitter());

        assertThat(capturedConditions()).containsExactly(CONDITION);
    }
}
