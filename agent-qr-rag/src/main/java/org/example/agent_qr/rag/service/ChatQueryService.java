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
import org.example.agent_qr.rag.filter.FilterConditionExtractor;
import org.example.agent_qr.rag.intent.QueryIntentClassifier;
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
 * 批次 03：域由调用方强制指定（问题 09 / 33 断裂 3）。
 * 批次 04：结构化过滤条件提取（任务 4.3）+ 聚合查询分流（任务 4.4）。
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

    /** 批次 04 · 任务 4.3：LLM 结构化过滤条件提取器（可选注入，灰度开关默认关闭） */
    @Autowired(required = false)
    private FilterConditionExtractor filterConditionExtractor;

    /** 批次 04 · 任务 4.4：查询意图分类器（可选注入，规则匹配零延迟） */
    @Autowired(required = false)
    private QueryIntentClassifier queryIntentClassifier;

    /** 批次 04 · 任务 4.4：聚合查询编排服务（可选注入） */
    @Autowired(required = false)
    private AggregationQueryService aggregationQueryService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 聚合路径空结果时的回答（与语义路径的"知识库中暂无相关信息"区分，语义一致：都不回退全库） */
    private static final String EMPTY_ANSWER_AGGREGATION = "未找到匹配记录";

    /** 语义路径空结果时的回答（P1 原有文案，保持不变） */
    private static final String EMPTY_ANSWER_SEMANTIC = "知识库中暂无相关信息";

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

        // 5. 域路由（★ 用户指定的域优先）+ 检索（★ 结构化过滤 / 聚合分流，见 retrieveWithPrompt）
        DomainRoutingResult routing = resolveRouting(query, domain);
        RetrievalPrompt retrieval = retrieveWithPrompt(query, queryEmbedding, routing);

        // 6. 无结果处理
        String answer;
        String sourcesJson = "[]";
        List<Map<String, Object>> sources = new ArrayList<>();

        if (retrieval.isEmpty()) {
            answer = retrieval.emptyAnswer();
            log.info("混合检索无结果，conversationId={}, 路径={}", conversationId, retrieval.pathName());
        } else {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(retrieval.systemPrompt()));
            messages.add(new UserMessage(query));

            LLMProvider llmProvider = circuitBreaker.getActiveProvider();
            try {
                answer = llmProvider.generate(messages);
                if (answer == null || answer.isBlank()) {
                    log.warn("LLM 返回空内容，conversationId={}, query={}", conversationId, query);
                    answer = "抱歉，AI 未能生成有效回答，请稍后重试";
                } else {
                    // ★ 任务 4.4.6：聚合结果被 Token 预算裁剪时，显式标注"结果可能不完整"
                    answer = answer + retrieval.truncationNotice();
                }
                circuitBreaker.recordSuccess();
            } catch (Exception e) {
                circuitBreaker.recordFailure();
                log.error("LLM 调用失败", e);
                answer = "抱歉，AI 服务暂时不可用，请稍后重试";
            }

            sources = buildSources(retrieval);
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
        log.info("问答流程完成，conversationId={}, 检索文档数={}, 路径={}",
                conversationId, retrieval.documents().size(), retrieval.pathName());
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
            RetrievalPrompt retrieval = retrieveWithPrompt(query, queryEmbedding, routing);

            List<Map<String, Object>> sources = new ArrayList<>();
            if (!retrieval.isEmpty()) {
                sources.addAll(buildSources(retrieval));
            }

            // 空检索短路 — 与 ask() 保持一致，避免无结果时浪费 LLM 调用
            if (retrieval.isEmpty()) {
                String emptyAnswer = retrieval.emptyAnswer();
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

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(retrieval.systemPrompt()));
            messages.add(new UserMessage(query));

            LLMProvider llmProvider = circuitBreaker.getActiveProvider();
            StringBuilder fullAnswer = new StringBuilder();

            final Long finalConversationId = conversationId;
            final String truncationNotice = retrieval.truncationNotice();
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
                            } else if (!truncationNotice.isEmpty()) {
                                // ★ 任务 4.4.6：流式链路同样显式提示"结果可能不完整"
                                answer = answer + truncationNotice;
                                sendSseEvent(emitter, "token", truncationNotice);
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
     * 检索并构建系统 Prompt（批次 04 · 任务 4.3 / 4.4 的分派点）。
     * <p>
     * 分派顺序：
     * <ol>
     *   <li>提取结构化过滤条件（任务 4.3；灰度开关关闭时恒为空列表 → 行为与修复前完全一致）；</li>
     *   <li>聚合类查询走「全量取回」路径（任务 4.4；跳过 HybridRetriever / Rerank，不受 final-top-k 截断）；</li>
     *   <li>其余（含聚合路径不可用时的降级）走原有语义混合检索路径。</li>
     * </ol>
     * </p>
     *
     * @return 检索结果 + 系统 Prompt + 完整性信息（documents 为空表示无结果）
     */
    private RetrievalPrompt retrieveWithPrompt(String query, float[] queryEmbedding,
                                               DomainRoutingResult routing) {
        // ★ 任务 4.3：结构化过滤条件「只提取一次」，聚合路径与（可能发生的）降级语义路径共用同一结果。
        //   灰度开关关闭时提取器直接返回空列表，行为与修复前完全一致。
        List<FilterCondition> filterConditions = extractFilterConditions(query, routing);

        // ★ 任务 4.4：聚合类查询分流（条件由上面统一提取后传入，聚合服务不再二次提取）
        AggregationQueryService.AggregationResult aggregation = tryAggregate(query, routing, filterConditions);
        if (aggregation.applicable()) {
            List<RetrievedDocument> documents = aggregation.documents();
            if (documents.isEmpty()) {
                // 与任务 4.2 一致的空集语义：域内无匹配记录 → 返回空，不回退全库检索
                log.info("聚合路径无匹配记录，返回空结果: query={}", query);
                return RetrievalPrompt.aggregationEmpty();
            }
            ContextTokenManager.AggregationContext aggregationContext =
                    contextTokenManager.buildAggregationContext(
                            documents, promptTemplate.getAggregationPromptBase(), query, documents.size());
            return RetrievalPrompt.aggregation(
                    documents,
                    promptTemplate.buildAggregationSystemPrompt(aggregationContext.text()),
                    aggregationContext.includedCount());
        }

        // 语义路径（原有管道，除过滤条件外逻辑不变；降级时复用同一次提取结果）
        List<RetrievedDocument> retrievedDocs = hybridRetriever.hybridSearch(
                query, queryEmbedding, routing, filterConditions);
        if (retrievedDocs.isEmpty()) {
            return RetrievalPrompt.semanticEmpty();
        }
        String contextText = contextTokenManager.buildContextWithBudget(
                retrievedDocs, promptTemplate.getSystemPromptBase(), query);
        return RetrievalPrompt.semantic(retrievedDocs, promptTemplate.buildSystemPrompt(contextText));
    }

    /**
     * 提取结构化过滤条件（批次 04 · 任务 4.3.2 / 4.3.4）。
     * <p>
     * 未注入提取器、无明确域、开关关闭或提取失败时一律返回空列表——
     * 「降级为无过滤条件继续检索」，不阻塞问答主流程。
     * </p>
     */
    private List<FilterCondition> extractFilterConditions(String query, DomainRoutingResult routing) {
        if (filterConditionExtractor == null) {
            return List.of();
        }
        if (routing == null || routing.isFallbackToGlobal()) {
            return List.of();
        }
        String domain = routing.getPrimaryDomain();
        if (domain == null || domain.isBlank()) {
            return List.of();
        }
        try {
            return filterConditionExtractor.extract(query, domain);
        } catch (Exception e) {
            log.warn("结构化过滤条件提取异常，降级为无过滤条件继续检索: query={}", query, e);
            return List.of();
        }
    }

    /**
     * 尝试聚合查询路径（批次 04 · 任务 4.4.5）。
     * <p>
     * 意图非聚合类、服务未注入或执行异常时返回 {@code applicable=false}，交由语义路径处理；
     * 过滤条件由调用方传入（同一次提取结果），聚合服务不重复提取。
     * </p>
     */
    private AggregationQueryService.AggregationResult tryAggregate(String query, DomainRoutingResult routing,
                                                                   List<FilterCondition> filterConditions) {
        if (aggregationQueryService == null || queryIntentClassifier == null) {
            return AggregationQueryService.AggregationResult.notApplicable();
        }
        try {
            if (queryIntentClassifier.classify(query) != QueryIntentClassifier.IntentType.AGGREGATION) {
                return AggregationQueryService.AggregationResult.notApplicable();
            }
            return aggregationQueryService.aggregate(query, routing, filterConditions);
        } catch (Exception e) {
            log.warn("聚合查询路径异常，降级语义路径: query={}", query, e);
            return AggregationQueryService.AggregationResult.notApplicable();
        }
    }

    /**
     * 构建返回给前端的来源列表。
     * <p>
     * 语义路径返回全部检索结果（历史行为不变）；
     * 聚合路径只返回实际进入 LLM 上下文的记录（避免上千条来源把 SSE 响应与
     * {@code chat_message.sources} 撑爆），且与 {@link #truncationNotice()} 的口径一致。
     * </p>
     */
    private List<Map<String, Object>> buildSources(RetrievalPrompt retrieval) {
        List<RetrievedDocument> documents = retrieval.documents();
        int limit = Math.min(retrieval.sourceLimit(), documents.size());
        List<Map<String, Object>> sources = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            RetrievedDocument doc = documents.get(i);
            Map<String, Object> sourceMap = new HashMap<>();
            sourceMap.put("documentId", doc.getDocumentId());
            sourceMap.put("documentTitle", doc.getDocumentTitle());
            sourceMap.put("content", doc.getContent());
            sourceMap.put("similarity", doc.getSimilarity());
            sources.add(sourceMap);
        }
        return sources;
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

    /**
     * 检索结果（含系统 Prompt 与结果完整性信息）。
     *
     * @param aggregation   是否走聚合路径
     * @param documents     检索到的文档（空表示无结果）
     * @param systemPrompt  已构建好的系统 Prompt
     * @param includedCount 实际进入 LLM 上下文的记录数（聚合路径下可能小于 documents.size()）
     */
    private record RetrievalPrompt(boolean aggregation, List<RetrievedDocument> documents,
                                   String systemPrompt, int includedCount) {

        static RetrievalPrompt semantic(List<RetrievedDocument> documents, String systemPrompt) {
            return new RetrievalPrompt(false, documents, systemPrompt, documents.size());
        }

        static RetrievalPrompt aggregation(List<RetrievedDocument> documents, String systemPrompt,
                                           int includedCount) {
            return new RetrievalPrompt(true, documents, systemPrompt, includedCount);
        }

        static RetrievalPrompt semanticEmpty() {
            return semantic(List.of(), null);
        }

        static RetrievalPrompt aggregationEmpty() {
            return aggregation(List.of(), null, 0);
        }

        boolean isEmpty() {
            return documents.isEmpty();
        }

        String pathName() {
            return aggregation ? "聚合路径" : "语义路径";
        }

        String emptyAnswer() {
            return aggregation ? EMPTY_ANSWER_AGGREGATION : EMPTY_ANSWER_SEMANTIC;
        }

        /** 返回给前端的来源条数上限（聚合路径只回传进入上下文的记录）。 */
        int sourceLimit() {
            return aggregation ? includedCount : documents.size();
        }

        /**
         * 结果完整性提示（任务 4.4.6）。
         * <p>仅在聚合路径发生 Token 预算裁剪时非空，让用户能区分"只有 N 条"与"显示了前 N 条"。</p>
         */
        String truncationNotice() {
            if (!aggregation || documents.isEmpty() || includedCount >= documents.size()) {
                return "";
            }
            return "\n\n（提示：结果可能不完整，已展示 " + includedCount
                    + " 条 / 共 " + documents.size() + " 条匹配记录）";
        }
    }
}
