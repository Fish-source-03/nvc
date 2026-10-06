package org.example.agent_qr.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.catalog.router.DomainRouter;
import org.example.agent_qr.rag.router.DomainRouterV2;
import org.example.agent_qr.common.event.AnswerGeneratedEvent;
import org.example.agent_qr.rag.circuitbreaker.LLMCircuitBreaker;
import org.example.agent_qr.rag.entity.Message;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.FilterCondition;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.rag.prompt.PromptTemplate;
import org.example.agent_qr.rag.provider.EmbeddingProvider;
import org.example.agent_qr.rag.provider.LLMProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.example.agent_qr.rag.retriever.HybridRetriever;
import org.example.agent_qr.rag.util.ContextTokenManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答核心服务。
 * <p>
 * P1 原有：同步 RAG 问答（ask 方法）。
 * P2 扩展：SSE 流式输出（askStream 方法），集成混合检索、熔断器和域路由。
 * P3 扩展：集成 DomainRouterV2 语义路由，降级链 P3语义 → P2关键词 → 全局检索。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class ChatQueryService {

    private final ProviderFactory providerFactory;
    private final HybridRetriever hybridRetriever;
    private final PromptTemplate promptTemplate;
    private final ConversationService conversationService;
    private final MessageMapper messageMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final LLMCircuitBreaker circuitBreaker;
    private final ContextTokenManager contextTokenManager;

    /** P2 域路由器 — 可选注入，避免与 catalog 模块的硬循环依赖 */
    @Autowired(required = false)
    private DomainRouter domainRouter;

    /** P3 语义域路由器 — 可选注入，优先于 P2 关键词路由 */
    @Autowired(required = false)
    private DomainRouterV2 domainRouterV2;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ChatQueryService(ProviderFactory providerFactory,
                            HybridRetriever hybridRetriever,
                            PromptTemplate promptTemplate,
                            ConversationService conversationService,
                            MessageMapper messageMapper,
                            ApplicationEventPublisher eventPublisher,
                            LLMCircuitBreaker circuitBreaker,
                            ContextTokenManager contextTokenManager) {
        this.providerFactory = providerFactory;
        this.hybridRetriever = hybridRetriever;
        this.promptTemplate = promptTemplate;
        this.conversationService = conversationService;
        this.messageMapper = messageMapper;
        this.eventPublisher = eventPublisher;
        this.circuitBreaker = circuitBreaker;
        this.contextTokenManager = contextTokenManager;
    }

    /**
     * 执行同步问答流程（P1 保留）。
     * <p>
     * ★ 问题 09 + 33 断裂 3：新增 {@code domain} 参数——由 Controller 校验后强制指定，
     * 检索范围以该域为准，不再被自动路由（语义/关键词）改写。
     * </p>
     *
     * @param query          用户问题
     * @param conversationId 会话 ID（可为 null，表示新建）
     * @param userId         当前用户 ID
     * @param domain         业务域（由入口鉴权保证非空且用户有权访问）
     */
    public Map<String, Object> ask(String query, Long conversationId, Long userId, String domain) {
        // 1. 会话管理
        if (conversationId == null) {
            conversationId = conversationService.createConversation(userId, query);
        }

        // 2. 保存用户消息
        Message userMessage = new Message();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(query);
        messageMapper.insert(userMessage);

        // 3. 消息计数 +1
        conversationService.incrementMessageCount(conversationId);

        // 4. 向量化用户问题
        EmbeddingProvider embeddingProvider = providerFactory.getEmbeddingProvider();
        float[] queryEmbedding = embeddingProvider.embed(query);

        // 5. 域路由（★ 用户指定的域优先）+ 混合检索
        DomainRoutingResult routing = resolveRouting(query, domain);
        List<RetrievedDocument> retrievedDocs = hybridRetriever.hybridSearch(
                query, queryEmbedding, routing, List.of());

        // 6. 无结果处理
        String answer;
        String sourcesJson = "[]";
        List<Map<String, Object>> sources = new ArrayList<>();

        if (retrievedDocs.isEmpty()) {
            answer = "知识库中暂无相关信息";
            log.info("混合检索无结果，conversationId={}", conversationId);
        } else {
            String systemPromptBase = promptTemplate.getSystemPromptBase();
            String contextText = contextTokenManager.buildContextWithBudget(
                    retrievedDocs, systemPromptBase, query);
            String systemPrompt = promptTemplate.buildSystemPrompt(contextText);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(query));

            LLMProvider llmProvider = circuitBreaker.getActiveProvider();
            try {
                answer = llmProvider.generate(messages);
                if (answer == null || answer.isBlank()) {
                    log.warn("LLM 返回空内容，conversationId={}, query={}", conversationId, query);
                    answer = "抱歉，AI 未能生成有效回答，请稍后重试";
                }
                circuitBreaker.recordSuccess();
            } catch (Exception e) {
                circuitBreaker.recordFailure();
                log.error("LLM 调用失败", e);
                answer = "抱歉，AI 服务暂时不可用，请稍后重试";
            }

            for (RetrievedDocument doc : retrievedDocs) {
                Map<String, Object> sourceMap = new HashMap<>();
                sourceMap.put("documentId", doc.getDocumentId());
                sourceMap.put("documentTitle", doc.getDocumentTitle());
                sourceMap.put("content", doc.getContent());
                sourceMap.put("similarity", doc.getSimilarity());
                sources.add(sourceMap);
            }
            try {
                sourcesJson = OBJECT_MAPPER.writeValueAsString(sources);
            } catch (JsonProcessingException e) {
                log.error("序列化 sources 失败", e);
            }
        }

        // 保存助手消息
        saveMessage(conversationId, "assistant", answer, sourcesJson);
        conversationService.incrementMessageCount(conversationId);

        // 发布事件
        eventPublisher.publishEvent(new AnswerGeneratedEvent(this, userId, conversationId));

        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        result.put("conversationId", conversationId);
        result.put("sources", sources);
        log.info("问答流程完成，conversationId={}, 检索文档数={}", conversationId, retrievedDocs.size());
        return result;
    }

    /**
     * SSE 流式问答（P2 新增）。
     * <p>
     * ★ 问题 09 + 33 断裂 3：与 {@link #ask} 一致，检索域由 {@code domain} 强制指定。
     * </p>
     *
     * @param query          用户问题
     * @param conversationId 会话 ID（可为 null，表示新建）
     * @param userId         当前用户 ID
     * @param domain         业务域（由入口鉴权保证非空且用户有权访问）
     * @param emitter        SSE 发射器
     */
    public void askStream(String query, Long conversationId, Long userId, String domain, SseEmitter emitter) {
        try {
            if (conversationId == null) {
                conversationId = conversationService.createConversation(userId, query);
            }

            Message userMessage = new Message();
            userMessage.setConversationId(conversationId);
            userMessage.setRole("user");
            userMessage.setContent(query);
            messageMapper.insert(userMessage);
            conversationService.incrementMessageCount(conversationId);

            EmbeddingProvider embeddingProvider = providerFactory.getEmbeddingProvider();
            float[] queryEmbedding = embeddingProvider.embed(query);
            DomainRoutingResult routing = resolveRouting(query, domain);
            List<RetrievedDocument> retrievedDocs = hybridRetriever.hybridSearch(
                    query, queryEmbedding, routing, List.of());

            List<Map<String, Object>> sources = new ArrayList<>();
            if (!retrievedDocs.isEmpty()) {
                for (RetrievedDocument doc : retrievedDocs) {
                    Map<String, Object> sourceMap = new HashMap<>();
                    sourceMap.put("documentId", doc.getDocumentId());
                    sourceMap.put("documentTitle", doc.getDocumentTitle());
                    sourceMap.put("content", doc.getContent());
                    sourceMap.put("similarity", doc.getSimilarity());
                    sources.add(sourceMap);
                }
            }

            // 空检索短路 — 与 ask() 保持一致，避免无结果时浪费 LLM 调用
            if (retrievedDocs.isEmpty()) {
                String emptyAnswer = "知识库中暂无相关信息";
                saveMessage(conversationId, "assistant", emptyAnswer, "[]");
                conversationService.incrementMessageCount(conversationId);
                sendSseEvent(emitter, "token", emptyAnswer);
                Map<String, Object> doneData = new HashMap<>();
                doneData.put("conversationId", conversationId);
                doneData.put("sources", List.of());
                doneData.put("answer", emptyAnswer);
                sendSseEvent(emitter, "done", doneData);
                eventPublisher.publishEvent(new AnswerGeneratedEvent(this, userId, conversationId));
                emitter.complete();
                return;
            }

            String systemPromptBase = promptTemplate.getSystemPromptBase();
            String contextText = contextTokenManager.buildContextWithBudget(
                    retrievedDocs, systemPromptBase, query);
            String systemPrompt = promptTemplate.buildSystemPrompt(contextText);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(query));

            LLMProvider llmProvider = circuitBreaker.getActiveProvider();
            StringBuilder fullAnswer = new StringBuilder();

            final Long finalConversationId = conversationId;
            llmProvider.generateStream(messages)
                    .doOnNext(token -> {
                        fullAnswer.append(token);
                        sendSseEvent(emitter, "token", token);
                    })
                    .doOnComplete(() -> {
                        try {
                            String answer = fullAnswer.toString();
                            // ★ 防御：LLM 成功完成但未产出任何内容 token
                            if (answer.isEmpty()) {
                                log.warn("SSE 完成但 LLM 返回空内容，conversationId={}, sources.size={}",
                                        finalConversationId, sources.size());
                                if (!sources.isEmpty()) {
                                    answer = "已找到参考资料，但 AI 未能生成回答，请重试或查看下方来源文档";
                                } else {
                                    answer = "抱歉，AI 未能生成有效回答，请稍后重试";
                                }
                            }
                            String sourcesJson = OBJECT_MAPPER.writeValueAsString(sources);
                            Long messageId = saveMessage(finalConversationId, "assistant", answer, sourcesJson);
                            conversationService.incrementMessageCount(finalConversationId);

                            Map<String, Object> doneData = new HashMap<>();
                            doneData.put("conversationId", finalConversationId);
                            doneData.put("messageId", messageId);
                            doneData.put("sources", sources);
                            doneData.put("answer", answer);
                            sendSseEvent(emitter, "done", doneData);

                            eventPublisher.publishEvent(new AnswerGeneratedEvent(this, userId, finalConversationId));
                            circuitBreaker.recordSuccess();
                            emitter.complete();

                            log.info("SSE 流式问答完成，conversationId={}, answerLength={}",
                                    finalConversationId, answer.length());
                        } catch (Exception e) {
                            log.warn("SSE 完成事件处理失败: conversationId={}", finalConversationId, e);
                            // 尝试发送 error 事件通知前端
                            try {
                                sendSseEvent(emitter, "error", Map.of("message", "响应处理异常"));
                            } catch (Exception ignored) {
                                // 连接已断开
                            }
                            emitter.completeWithError(e);
                        }
                    })
                    .doOnError(error -> {
                        log.error("SSE 流式生成失败", error);
                        circuitBreaker.recordFailure();
                        sendSseEvent(emitter, "error", Map.of("message", error.getMessage()));

                        String assistantContent;
                        if (!fullAnswer.isEmpty()) {
                            assistantContent = fullAnswer + "\n[生成中断]";
                        } else {
                            // LLM 未产出任何 token → 保存占位消息，避免会话变空
                            assistantContent = "抱歉，AI 服务暂时不可用，请稍后重试";
                        }
                        saveMessage(finalConversationId, "assistant", assistantContent, "[]");
                        conversationService.incrementMessageCount(finalConversationId);
                        emitter.completeWithError(error);
                    })
                    .subscribe();
        } catch (Exception e) {
            log.error("SSE 流式问答初始化失败", e);
            sendSseEvent(emitter, "error", Map.of("message", e.getMessage()));
            // 如果用户消息已保存（conversationId 已创建），保存占位 assistant 消息避免空对话
            if (conversationId != null) {
                try {
                    saveMessage(conversationId, "assistant",
                            "抱歉，AI 服务暂时不可用，请稍后重试", "[]");
                    conversationService.incrementMessageCount(conversationId);
                } catch (Exception ignored) {
                    // 保存失败不影响主流程
                }
            }
            emitter.completeWithError(e);
        }
    }

    /**
     * 解析域路由。
     * <p>
     * ★ 问题 09：当调用方显式指定 {@code domain}（来自请求体且已通过 ABAC 校验）时，
     * 直接锁定该域，<b>不再</b>走自动路由——否则语义/关键词路由可能把检索范围
     * 改写到用户指定之外的域，形成"界面选了域、实际检索别的域"的越权面。
     * </p>
     * <p>
     * 未指定域时保持原降级链：P3 语义路由 → P2 关键词路由 → 全局检索。
     * 任何环节异常均自动降级，不影响问答主流程。
     * </p>
     */
    private DomainRoutingResult resolveRouting(String query, String domain) {
        // 0. 用户显式指定的域优先（入口已做 ABAC 校验 + 强制非空）
        if (domain != null && !domain.isBlank()) {
            DomainRoutingResult pinned = new DomainRoutingResult();
            Map<String, Double> matchedDomains = new HashMap<>();
            matchedDomains.put(domain, 1.0D);
            pinned.setMatchedDomains(matchedDomains);
            pinned.setFallbackToGlobal(false);
            log.debug("使用请求指定的业务域进行检索: domain={}", domain);
            return pinned;
        }

        // 1. 优先使用 P3 语义路由
        if (domainRouterV2 != null) {
            try {
                DomainRoutingResult result = domainRouterV2.route(query);
                if (!result.isFallbackToGlobal()) {
                    return result;
                }
                log.debug("DomainRouterV2 未匹配到域，降级到关键词路由");
            } catch (Exception e) {
                log.warn("DomainRouterV2 语义路由异常，降级到关键词路由", e);
            }
        }
        // 2. 降级到 P2 关键词路由
        if (domainRouter != null) {
            try {
                return domainRouter.route(query);
            } catch (Exception e) {
                log.warn("域路由失败，降级到全局检索: {}", e.getMessage());
            }
        }
        // 3. 最终降级：全局检索
        return DomainRoutingResult.fallback();
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException e) {
            log.error("SSE 事件发送失败: eventName={}", eventName, e);
        } catch (Exception e) {
            // AsyncRequestNotUsableException 等运行时异常：连接已断开，静默跳过
            log.warn("SSE 事件发送异常，连接可能已断开: eventName={}", eventName);
        }
    }

    private Long saveMessage(Long conversationId, String role, String content, String sources) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setSources(sources);
        messageMapper.insert(message);
        return message.getId();
    }
}
