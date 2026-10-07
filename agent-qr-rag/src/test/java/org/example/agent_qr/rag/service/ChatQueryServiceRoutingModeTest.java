package org.example.agent_qr.rag.service;

import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.catalog.router.DomainRouter;
import org.example.agent_qr.rag.circuitbreaker.LLMCircuitBreaker;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.rag.prompt.PromptTemplate;
import org.example.agent_qr.rag.provider.EmbeddingProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.example.agent_qr.rag.retriever.HybridRetriever;
import org.example.agent_qr.rag.router.DomainRouterV2;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ChatQueryService} 语义路由开关接线测试（批次 07 · 任务 7.5，问题 38）。
 * <p>
 * 拦截的核心缺陷：{@code agent-qr.routing.mode} 在 application-p3.yml 声明了
 * {@code keyword | semantic | auto}，但<b>无任何 Java 读取点</b>——运维改它没有任何效果。
 * 本测试固化三种模式的真实行为，并断言配置键确实被读取。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatQueryServiceRoutingModeTest {

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
    private DomainRouter keywordRouter;
    @Mock
    private DomainRouterV2 semanticRouter;

    private ChatQueryService service;

    @BeforeEach
    void setUp() {
        service = new ChatQueryService(providerFactory, hybridRetriever, promptTemplate,
                conversationService, messageMapper, eventPublisher, circuitBreaker, contextTokenManager);
        when(providerFactory.getEmbeddingProvider()).thenReturn(embeddingProvider);
        when(embeddingProvider.embed(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(conversationService.createConversation(anyLong(), any())).thenReturn(100L);
        // 检索为空 → 短路"知识库中暂无相关信息"，不触发 LLM
        when(hybridRetriever.hybridSearch(any(), any(), any(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "domainRouter", keywordRouter);
        ReflectionTestUtils.setField(service, "domainRouterV2", semanticRouter);
    }

    @Test
    @DisplayName("★ routing.mode=keyword：只走 V1 关键词路由（语义路由零调用）")
    void keywordMode_shouldUseKeywordRouterOnly() {
        ReflectionTestUtils.setField(service, "routingMode", "keyword");
        when(keywordRouter.route(any())).thenReturn(routing("HR", 1.0D));

        ask("请假流程");

        assertThat(capturedRouting().getPrimaryDomain()).isEqualTo("HR");
        verifyNoInteractions(semanticRouter);
    }

    @Test
    @DisplayName("★ routing.mode=semantic：只走 V2 语义路由")
    void semanticMode_shouldUseSemanticRouterOnly() {
        ReflectionTestUtils.setField(service, "routingMode", "semantic");
        when(semanticRouter.route(any())).thenReturn(routing("FINANCE", 0.9D));

        ask("报销标准");

        assertThat(capturedRouting().getPrimaryDomain()).isEqualTo("FINANCE");
        verifyNoInteractions(keywordRouter);
    }

    @Test
    @DisplayName("★ routing.mode=semantic：V2 未匹配到域时回退全局检索，不改用关键词路由（口径不分裂）")
    void semanticMode_shouldFallBackToGlobal_whenSemanticMisses() {
        ReflectionTestUtils.setField(service, "routingMode", "semantic");
        when(semanticRouter.route(any())).thenReturn(DomainRoutingResult.fallback());

        ask("无关问题");

        assertThat(capturedRouting().isFallbackToGlobal()).isTrue();
        verifyNoInteractions(keywordRouter);
    }

    @Test
    @DisplayName("★ routing.mode=auto：V2 优先 → V1 降级（保留既有降级链）")
    void autoMode_shouldPreferSemanticThenFallBackToKeyword() {
        ReflectionTestUtils.setField(service, "routingMode", "auto");
        when(semanticRouter.route(any())).thenReturn(routing("RD", 0.8D));
        when(keywordRouter.route(any())).thenReturn(routing("SALES", 1.0D));

        ask("代码评审");

        assertThat(capturedRouting().getPrimaryDomain()).as("V2 命中时用 V2").isEqualTo("RD");

        // 模拟 V2 不可用（未匹配） → 降级 V1
        when(semanticRouter.route(any())).thenReturn(DomainRoutingResult.fallback());
        ask("销售合同");

        ArgumentCaptor<DomainRoutingResult> captor = ArgumentCaptor.forClass(DomainRoutingResult.class);
        verify(hybridRetriever, org.mockito.Mockito.times(2))
                .hybridSearch(any(), any(), captor.capture(), any());
        assertThat(captor.getAllValues().get(1).getPrimaryDomain())
                .as("V2 未匹配时必须降级到 V1，而不是直接全局检索")
                .isEqualTo("SALES");
    }

    @Test
    @DisplayName("★ routing.mode=auto：V2 抛异常时降级 V1 而不是中断问答")
    void autoMode_shouldFallBackToKeyword_whenSemanticThrows() {
        ReflectionTestUtils.setField(service, "routingMode", "auto");
        when(semanticRouter.route(any())).thenThrow(new RuntimeException("Ollama 不可达"));
        when(keywordRouter.route(any())).thenReturn(routing("HR", 1.0D));

        ask("薪酬制度");

        assertThat(capturedRouting().getPrimaryDomain()).isEqualTo("HR");
    }

    @Test
    @DisplayName("配置值大小写/空格容错；未知值回退 auto（不中断问答）")
    void routingMode_shouldBeLenient() {
        ReflectionTestUtils.setField(service, "routingMode", " KEYWORD ");
        assertThat(service.normalizedRoutingMode()).isEqualTo("keyword");

        ReflectionTestUtils.setField(service, "routingMode", "semantics-typo");
        assertThat(service.normalizedRoutingMode()).as("未知值按 auto 处理").isEqualTo("auto");

        ReflectionTestUtils.setField(service, "routingMode", "");
        assertThat(service.normalizedRoutingMode()).isEqualTo("auto");
    }

    @Test
    @DisplayName("★ application-p3.yml 必须声明 agent-qr.routing.mode（配置与代码不脱节）")
    void applicationP3Yml_shouldDeclareRoutingMode() throws Exception {
        String yml = readApplicationP3();
        Matcher matcher = Pattern.compile("(?m)^\\s*mode:\\s*(keyword|semantic|auto)\\s*(#.*)?$").matcher(yml);

        assertThat(matcher.find()).as("yml 未声明 routing.mode，接线等于虚设").isTrue();
    }

    private void ask(String query) {
        service.ask(query, null, 7L, null);
    }

    private DomainRoutingResult capturedRouting() {
        ArgumentCaptor<DomainRoutingResult> captor = ArgumentCaptor.forClass(DomainRoutingResult.class);
        verify(hybridRetriever).hybridSearch(any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    private static DomainRoutingResult routing(String domain, double score) {
        DomainRoutingResult result = new DomainRoutingResult();
        Map<String, Double> matched = new HashMap<>();
        matched.put(domain, score);
        result.setMatchedDomains(matched);
        result.setFallbackToGlobal(false);
        return result;
    }

    private static String readApplicationP3() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", "application-p3.yml"));
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 application-p3.yml");
    }
}
