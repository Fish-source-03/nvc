package org.example.agent_qr.rag.prompt;

import org.springframework.stereotype.Component;

/**
 * Prompt 模板，用于构建发送给 LLM 的提示词。
 * <p>
 * 将用户问题和检索到的知识库上下文拼接成结构化的 Prompt，
 * 引导 LLM 基于参考资料进行回答。
 * </p>
 *
 * @author agent-qr
 */
@Component
public class PromptTemplate {

    /** 系统提示词前缀：角色设定 + 参考资料引导 */
    private static final String SYSTEM_PROMPT_PREFIX = """
            你是一个企业知识库助手，请根据以下参考资料回答用户问题。
            如果参考资料中没有相关信息，请如实告知用户"知识库中暂无相关信息"，不要编造答案。

            参考资料：
            """;

    /** 系统提示词后缀：回答指令 */
    private static final String SYSTEM_PROMPT_SUFFIX = """

            请基于以上参考资料给出准确、简洁的回答：""";

    /**
     * 返回不含文档内容的系统提示词基础文本。
     * <p>
     * 供 {@link ContextTokenManager} 进行 token 预算估算使用。
     * </p>
     *
     * @return 系统提示词前缀 + 后缀的拼接
     */
    public String getSystemPromptBase() {
        return SYSTEM_PROMPT_PREFIX + SYSTEM_PROMPT_SUFFIX;
    }

    /** 聚合查询系统提示词前缀：强调记录列表是完整的，要求逐条列出 / 准确统计（批次 04 · 任务 4.4.4） */
    private static final String AGGREGATION_PROMPT_PREFIX = """
            你是一个企业知识库助手，请根据以下从知识库中检索到的匹配记录回答用户问题。
            这批记录是完整的匹配结果（若标注了「仅展示 x/y 条记录」，则说明存在 Token 预算裁剪）：
            - 列举类问题：请逐条列出全部记录，不要只举几例、不要概括
            - 统计类问题：请基于记录总数给出准确数量

            匹配记录：
            """;

    /** 聚合查询系统提示词后缀：回答指令 */
    private static final String AGGREGATION_PROMPT_SUFFIX = """

            请基于以上完整记录给出准确、完整的回答：""";

    /**
     * 构建系统消息（角色指令 + 检索上下文）。
     *
     * @param context 从知识库检索到的上下文文本
     * @return 系统 Prompt 文本
     */
    public String buildSystemPrompt(String context) {
        return SYSTEM_PROMPT_PREFIX + context + SYSTEM_PROMPT_SUFFIX;
    }

    /**
     * 返回聚合查询系统提示词的基础文本（不含记录内容），供 Token 预算估算使用。
     *
     * @return 聚合提示词前缀 + 后缀的拼接
     */
    public String getAggregationPromptBase() {
        return AGGREGATION_PROMPT_PREFIX + AGGREGATION_PROMPT_SUFFIX;
    }

    /**
     * 构建聚合查询的系统消息（批次 04 · 任务 4.4.4）。
     * <p>与 {@link #buildSystemPrompt} 的区别：明确告知 LLM「这是完整记录列表，需要全部列出 / 准确统计」。</p>
     *
     * @param context 聚合路径构建的紧凑上下文文本
     * @return 聚合系统 Prompt 文本
     */
    public String buildAggregationSystemPrompt(String context) {
        return AGGREGATION_PROMPT_PREFIX + context + AGGREGATION_PROMPT_SUFFIX;
    }

    /**
     * 构建用户消息（用户原始问题）。
     *
     * @param query 用户问题
     * @return 用户 Prompt 文本
     */
    public String buildUserPrompt(String query) {
        return query;
    }

    /**
     * @deprecated 请使用 {@link #buildSystemPrompt(String)} + {@link #buildUserPrompt(String)}
     *             分别构建 SystemMessage 和 UserMessage，以符合 Chat API 标准对话格式。
     */
    @Deprecated
    public String build(String query, String context) {
        return String.format("""
                你是一个企业知识库助手，请根据以下参考资料回答用户问题。
                如果参考资料中没有相关信息，请如实告知用户"知识库中暂无相关信息"，不要编造答案。

                参考资料：
                %s

                用户问题：%s

                请基于以上参考资料给出准确、简洁的回答：""", context, query);
    }
}
