package org.example.agent_qr.rag.intent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.provider.LLMProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 查询意图分类器（批次 04 · 任务 4.4.2，问题 13）。
 * <p>
 * 区分两类查询，用于在检索入口分派不同管道：
 * <ul>
 *   <li>{@link IntentType#AGGREGATION}：列举 / 统计类（"有哪些人已经离职"、"一共多少人"）——
 *       需要<b>全部</b>匹配记录，走 {@code AggregationQueryService}，不受 TOP-K 截断；</li>
 *   <li>{@link IntentType#SEMANTIC}：语义类（"离职流程是什么"）——需要最相关文档，走原混合检索管道。</li>
 * </ul>
 * </p>
 * <p>
 * <b>策略</b>：规则匹配优先（零延迟，覆盖常见中文表达），LLM 兜底仅在
 * {@code agent-qr.aggregation.llm-classify.enabled=true} 且规则未命中时启用（默认关闭）；
 * LLM 判定失败一律回落到 {@code SEMANTIC}——语义路径与修复前完全一致，聚合路径不会误触发。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class QueryIntentClassifier {

    /** 查询意图类型 */
    public enum IntentType {
        /** 列举 / 统计类：需要完整数据集 */
        AGGREGATION,
        /** 语义类：需要最相关文档 */
        SEMANTIC
    }

    /** 列举模式（方案文档 §Step 2） */
    private static final Pattern LIST_PATTERN = Pattern.compile(
            "哪些人|有哪些|列出|都有谁|所有.{0,10}的|名单|哪些.{0,10}已经|都有哪些|全部.{0,10}的");

    /** 统计模式（方案文档 §Step 2） */
    private static final Pattern STATISTIC_PATTERN = Pattern.compile(
            "有多少|统计|一共|总计|数量|几个|多少人|计数|总共|汇总|合计");

    private static final String CLASSIFY_PROMPT = """
            你是一个查询意图分类器。把用户问题分为两类之一：
            - AGGREGATION：列举或统计类问题，需要"全部匹配记录"（例如"有哪些人已经离职""一共多少人"）
            - SEMANTIC：语义/知识类问题，需要"最相关的说明文档"（例如"离职流程是什么"）

            只输出一个词：AGGREGATION 或 SEMANTIC，不要任何其他内容。""";

    @Autowired(required = false)
    private ProviderFactory providerFactory;

    /** LLM 兜底开关（默认关闭：仅用规则，零额外延迟） */
    @Value("${agent-qr.aggregation.llm-classify.enabled:false}")
    private boolean llmClassifyEnabled;

    /** LLM 兜底调用超时（秒） */
    @Value("${agent-qr.aggregation.llm-classify.timeout-seconds:5}")
    private int timeoutSeconds;

    /**
     * 对用户问题做意图分类。
     *
     * @param query 用户原始问题
     * @return 查询意图；任何异常/不确定情形都返回 {@link IntentType#SEMANTIC}
     */
    public IntentType classify(String query) {
        if (query == null || query.isBlank()) {
            return IntentType.SEMANTIC;
        }
        if (matchesAggregationPattern(query)) {
            log.debug("查询意图=AGGREGATION（规则命中）: query={}", query);
            return IntentType.AGGREGATION;
        }
        if (llmClassifyEnabled) {
            IntentType byLlm = llmClassify(query);
            log.debug("查询意图={}（LLM 兜底）: query={}", byLlm, query);
            return byLlm;
        }
        return IntentType.SEMANTIC;
    }

    /**
     * 规则匹配：是否命中列举 / 统计类表达。
     */
    public boolean matchesAggregationPattern(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return LIST_PATTERN.matcher(query).find() || STATISTIC_PATTERN.matcher(query).find();
    }

    /**
     * LLM 兜底分类（默认关闭）。任何异常、超时、非预期输出都回落为 SEMANTIC。
     */
    private IntentType llmClassify(String query) {
        if (providerFactory == null) {
            return IntentType.SEMANTIC;
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<ChatMessage> messages = List.of(
                    new SystemMessage(CLASSIFY_PROMPT),
                    new UserMessage(query));
            LLMProvider llmProvider = providerFactory.getLLMProvider();
            Future<String> future = executor.submit(() -> llmProvider.generate(messages));
            String response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response != null && response.toUpperCase(Locale.ROOT).contains("AGGREGATION")) {
                return IntentType.AGGREGATION;
            }
            return IntentType.SEMANTIC;
        } catch (Exception e) {
            log.warn("LLM 意图分类失败，回落为 SEMANTIC: query={}", query, e);
            return IntentType.SEMANTIC;
        } finally {
            executor.shutdownNow();
        }
    }
}
