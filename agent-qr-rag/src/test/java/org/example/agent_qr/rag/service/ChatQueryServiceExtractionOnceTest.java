package org.example.agent_qr.rag.service;

import org.example.agent_qr.rag.circuitbreaker.LLMCircuitBreaker;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.FilterCondition;
import org.example.agent_qr.rag.filter.FilterConditionExtractor;
import org.example.agent_qr.rag.filter.StructuredFilterService;
import org.example.agent_qr.rag.filter.mapper.ChunkStructuredFilterMapper;
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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「同一问答只提取一次结构化过滤条件」测试（批次 04 · 任务 4.3 / 4.4 交叉约束）。
 * <p>
 * 拦截的缺陷（独立验证发现）：聚合路径先提取条件，一旦不可用（无结构化条件 / 无域 / 无权限 / 异常）
 * 降级到语义路径后<b>又提取一次</b>——每次"列举/统计类但无结构化条件"的提问都白付一次 LLM 调用。
 * </p>
 * <p>
 * 与 {@link ChatQueryServiceAggregationTest} 的关键区别：本类使用<b>真实</b>
 * {@link AggregationQueryService}（只 mock 其依赖的 {@link StructuredFilterService} /
 * {@link ChunkStructuredFilterMapper}），因此真正覆盖"聚合服务 → 降级 → 语义路径"链路；
 * 提取器用 {@code Mockito.spy} 包住<b>真实</b> {@link FilterConditionExtractor}，
 * 既能计数又保留真实降级行为。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatQueryServiceExtractionOnceTest {

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
    private LLMProvider llmProvider;
    @Mock
    private QueryIntentClassifier queryIntentClassifier;
    @Mock
    private StructuredFilterService structuredFilterService;
    @Mock
    private ChunkStructuredFilterMapper chunkStructuredFilterMapper;

    /** 真实提取器的 spy：保留真实行为，同时可对调用次数计数 */
    private FilterConditionExtractor extractorSpy;

    private ChatQueryService chatQueryService;

    @BeforeEach
    void setUp() {
        FilterConditionExtractor extractor = new FilterConditionExtractor();
        ReflectionTestUtils.setField(extractor, "providerFactory", providerFactory);
        ReflectionTestUtils.setField(extractor, "chunkStructuredFilterMapper", chunkStructuredFilterMapper);
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "timeoutSeconds", 2);
        extractorSpy = Mockito.spy(extractor);

        AggregationQueryService aggregationQueryService = new AggregationQueryService();
        ReflectionTestUtils.setField(aggregationQueryService, "structuredFilterService", structuredFilterService);
        ReflectionTestUtils.setField(aggregationQueryService, "chunkStructuredFilterMapper",
                chunkStructuredFilterMapper);

        chatQueryService = new ChatQueryService(providerFactory, hybridRetriever, promptTemplate,
                conversationService, messageMapper, eventPublisher, circuitBreaker, contextTokenManager);
        ReflectionTestUtils.setField(chatQueryService, "filterConditionExtractor", extractorSpy);
        ReflectionTestUtils.setField(chatQueryService, "queryIntentClassifier", queryIntentClassifier);
        ReflectionTestUtils.setField(chatQueryService, "aggregationQueryService", aggregationQueryService);

        when(providerFactory.getEmbeddingProvider()).thenReturn(embeddingProvider);
        when(providerFactory.getLLMProvider()).thenReturn(llmProvider);
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f});
        when(conversationService.createConversation(anyLong(), any())).thenReturn(100L);
        when(circuitBreaker.getActiveProvider()).thenReturn(llmProvider);
        when(promptTemplate.getSystemPromptBase()).thenReturn("base");
        when(promptTemplate.buildSystemPrompt(any())).thenReturn("system");
        when(promptTemplate.getAggregationPromptBase()).thenReturn("agg-base");
        when(promptTemplate.buildAggregationSystemPrompt(any())).thenReturn("agg-system");
        when(contextTokenManager.buildContextWithBudget(any(), any(), any())).thenReturn("context");
        when(contextTokenManager.buildAggregationContext(any(), any(), any(), anyInt()))
                .thenReturn(new ContextTokenManager.AggregationContext("agg-context", 1, 1));
        when(hybridRetriever.hybridSearch(any(), any(), any(), any())).thenReturn(List.of(document()));
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

    private FilterConditionExtractor.FieldDefinition field(String name, String type) {
        FilterConditionExtractor.FieldDefinition definition = new FilterConditionExtractor.FieldDefinition();
        definition.setFieldName(name);
        definition.setFieldType(type);
        return definition;
    }

    /** HR 有字段定义，但 LLM 判定"无过滤意图"→ 提取执行但结果为空（即实测中的 raw=0 / valid=0）。 */
    private void givenExtractionYieldsNoCondition() {
        when(chunkStructuredFilterMapper.selectDistinctFieldsByDomain("HR"))
                .thenReturn(List.of(field("clearance_level", "NUMBER")));
        when(llmProvider.generate(any())).thenReturn("[]");
    }

    private void givenAggregationIntent() {
        when(queryIntentClassifier.classify(any())).thenReturn(QueryIntentClassifier.IntentType.AGGREGATION);
    }

    @Test
    @DisplayName("★ 降级路径（聚合不可用 → 语义路径）只提取一次，不重复调用 LLM")
    void ask_shouldExtractOnlyOnce_whenAggregationDegradesToSemanticPath() {
        givenExtractionYieldsNoCondition();
        givenAggregationIntent();

        chatQueryService.ask("列出所有员工", 100L, 7L, "HR");

        // 修复前：聚合服务提取 1 次 + 语义路径再提取 1 次 = 2 次
        verify(extractorSpy, times(1)).extract("列出所有员工", "HR");
        // LLM 总调用 = 1（提取）+ 1（生成回答）：证明没有第三次
        verify(llmProvider, times(2)).generate(any());
    }

    @Test
    @DisplayName("★ 降级后语义检索拿到的正是同一次提取结果（空条件），且未走无界查询")
    void ask_shouldReuseSameExtractionResult_onSemanticPath() {
        givenExtractionYieldsNoCondition();
        givenAggregationIntent();

        chatQueryService.ask("列出所有员工", 100L, 7L, "HR");

        verify(hybridRetriever).hybridSearch(any(), any(), any(), eq(List.of()));
        verify(structuredFilterService, never()).filterChunkIdsUnbounded(any(), any());
    }

    @Test
    @DisplayName("★ 灰度开关关闭态同样只提取一次（不重复走一遍'未启用 → 跳过'）")
    void ask_shouldExtractOnlyOnce_whenSwitchDisabled() {
        givenAggregationIntent();
        ReflectionTestUtils.setField(extractorSpy, "enabled", false);

        chatQueryService.ask("列出所有员工", 100L, 7L, "HR");

        verify(extractorSpy, times(1)).extract("列出所有员工", "HR");
        verify(providerFactory, never()).getLLMProvider();                       // 提取侧完全未调用 LLM
        verify(chunkStructuredFilterMapper, never()).selectDistinctFieldsByDomain(any()); // 且未查字段
        verify(hybridRetriever).hybridSearch(any(), any(), any(), any());         // 语义路径照常执行
    }

    @Test
    @DisplayName("★ 聚合路径命中时同样只提取一次，且条件真实下传到聚合服务")
    void ask_shouldExtractOnlyOnce_whenAggregationPathIsUsed() {
        when(chunkStructuredFilterMapper.selectDistinctFieldsByDomain("HR"))
                .thenReturn(List.of(field("department", "STRING")));
        when(llmProvider.generate(any())).thenReturn(
                "[{\"fieldName\":\"department\",\"fieldType\":\"STRING\",\"operator\":\"EQ\",\"value\":\"RD\"}]");
        givenAggregationIntent();
        when(structuredFilterService.filterChunkIdsUnbounded(eq("HR"), any())).thenReturn(List.of(1000L));
        when(chunkStructuredFilterMapper.selectChunkContentsByIds(List.of(1000L))).thenReturn(List.of(document()));

        chatQueryService.ask("列出所有研发部员工", 100L, 7L, "HR");

        verify(extractorSpy, times(1)).extract("列出所有研发部员工", "HR");
        verify(hybridRetriever, never()).hybridSearch(any(), any(), any(), any());
        verify(structuredFilterService).filterChunkIdsUnbounded(eq("HR"),
                argThat(conditions -> conditions.size() == 1
                        && "department".equals(conditions.get(0).getFieldName())
                        && FilterCondition.OP_EQ.equals(conditions.get(0).getOperator())
                        && "RD".equals(conditions.get(0).getValue())));
    }

    @Test
    @DisplayName("无明确域（全局降级路由）不触发提取，检索照常执行")
    void ask_shouldSkipExtraction_whenNoDomain() {
        chatQueryService.ask("公司概况", 100L, 7L, null);

        verify(extractorSpy, never()).extract(any(), any());
        verify(hybridRetriever).hybridSearch(any(), any(), any(), any());
    }
}
