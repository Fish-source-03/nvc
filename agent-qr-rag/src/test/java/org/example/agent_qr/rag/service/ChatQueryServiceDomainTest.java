package org.example.agent_qr.rag.service;

import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.rag.circuitbreaker.LLMCircuitBreaker;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.rag.prompt.PromptTemplate;
import org.example.agent_qr.rag.provider.EmbeddingProvider;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatQueryService} 业务域下传测试（批次 03 · 任务 3.5.1/3.5.2，问题 09 + 33 断裂 3）。
 * <p>
 * 拦截的核心缺陷：前端域选择器传入的 {@code domain} 在后端被静默丢弃，
 * 检索范围实际由自动路由（语义/关键词/全局）决定——"界面选了 HR、实际检索全库"。
 * </p>
 * <p>
 * 本测试固化策略：调用方（Controller，已做 ABAC 校验）指定的域优先，
 * <b>不得</b>被自动路由改写。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatQueryServiceDomainTest {

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

    private ChatQueryService chatQueryService;

    @BeforeEach
    void setUp() {
        chatQueryService = new ChatQueryService(providerFactory, hybridRetriever, promptTemplate,
                conversationService, messageMapper, eventPublisher, circuitBreaker, contextTokenManager);
        when(providerFactory.getEmbeddingProvider()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(conversationService.createConversation(anyLong(), any())).thenReturn(100L);
        // 检索为空 → 走"知识库中暂无相关信息"短路，不触发 LLM
        when(hybridRetriever.hybridSearch(any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("★ ask() 使用调用方指定的 domain 作为检索域，而不是自动路由结果")
    void ask_shouldUseRequestedDomainForRetrieval() {
        chatQueryService.ask("请假流程", null, 7L, "HR");

        DomainRoutingResult routing = capturedRouting();
        assertThat(routing.getPrimaryDomain())
                .as("域选择器传入的域必须真实限定检索范围（33 断裂 3）")
                .isEqualTo("HR");
        assertThat(routing.isFallbackToGlobal()).isFalse();
    }

    @Test
    @DisplayName("★ 自动路由不得覆盖用户指定的域（语义路由指向别的域时仍以请求域为准）")
    void ask_shouldNotOverrideRequestedDomain_byAutoRouting() {
        // 语义路由会把问题路由到 FINANCE，但请求指定 HR，必须以 HR 为准
        org.example.agent_qr.rag.router.DomainRouterV2 semanticRouter =
                org.mockito.Mockito.mock(org.example.agent_qr.rag.router.DomainRouterV2.class);
        DomainRoutingResult auto = new DomainRoutingResult();
        java.util.Map<String, Double> matched = new java.util.HashMap<>();
        matched.put("FINANCE", 0.99D);
        auto.setMatchedDomains(matched);
        when(semanticRouter.route(any())).thenReturn(auto);
        ReflectionTestUtils.setField(chatQueryService, "domainRouterV2", semanticRouter);

        chatQueryService.ask("报销标准", null, 7L, "HR");

        assertThat(capturedRouting().getPrimaryDomain())
                .as("auto routing 只能在未指定域时生效（越权防护：不得扩大检索范围）")
                .isEqualTo("HR");
    }

    @Test
    @DisplayName("★ askStream() 同样把 domain 下传到检索层（SSE 链路与同步链路一致）")
    void askStream_shouldUseRequestedDomainForRetrieval() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();

        chatQueryService.askStream("请假流程", null, 7L, "HR", emitter);

        assertThat(capturedRouting().getPrimaryDomain()).isEqualTo("HR");
    }

    private DomainRoutingResult capturedRouting() {
        ArgumentCaptor<DomainRoutingResult> captor = ArgumentCaptor.forClass(DomainRoutingResult.class);
        verify(hybridRetriever).hybridSearch(any(), any(), captor.capture(), any());
        return captor.getValue();
    }
}
