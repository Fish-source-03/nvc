package org.example.agent_qr.rag.util;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 感知上下文管理器。
 * <p>
 * 负责 token 数量估算和预算约束的文档上下文拼接。
 * 采用保守启发式估算（中文 ~1.5 tokens/字, ASCII ~0.25 tokens/字），
 * 确保不会超出 LLM 上下文窗口。
 * P4 可升级为调用真实 tokenizer（如 langchain4j Tokenizer）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class ContextTokenManager {

    @Value("${agent-qr.retrieval.max-context-tokens:8000}")
    private int maxContextTokens;

    /** LLM 回复预留 token 数（与 llm.deepseek.max-tokens 对齐） */
    private static final int RESPONSE_RESERVED_TOKENS = 2048;

    /**
     * 估算文本的 token 数量（保守启发式）。
     * <p>
     * 中文字符 ~1.5 tokens, ASCII ~0.25 tokens, 其他 ~1 token。
     * 估算值始终偏高，确保不会超出真实上下文窗口。
     * </p>
     *
     * @param text 待估算文本
     * @return 估算的 token 数
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int asciiChars = 0;
        int otherChars = 0;

        for (char c : text.toCharArray()) {
            if (Character.isIdeographic(c)
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseChars++;
            } else if (c < 128) {
                asciiChars++;
            } else {
                otherChars++;
            }
        }

        return (int) (chineseChars * 1.5 + asciiChars * 0.25 + otherChars);
    }

    /**
     * 在 token 预算内构建文档上下文文本。
     * <p>
     * 按精排顺序遍历文档，逐个累加 token 估算值，
     * 超出预算时停止后续文档的拼接。
     * </p>
     *
     * @param documents  精排后的文档列表（已按相关性降序排列）
     * @param promptBase 系统提示词模板（不含文档内容，用于 token 估算）
     * @param query      用户原始问题
     * @return 拼接后的上下文文本（已裁剪至预算内）
     */
    public String buildContextWithBudget(List<RetrievedDocument> documents,
                                         String promptBase,
                                         String query) {
        // 固定开销：系统提示词基础文本 + 用户问题 + LLM 回复预留
        int fixedTokens = estimateTokens(promptBase)
                        + estimateTokens(query)
                        + RESPONSE_RESERVED_TOKENS;
        int availableTokens = maxContextTokens - fixedTokens;

        if (availableTokens <= 0) {
            log.warn("固定开销已超过 token 预算 (fixed={}, budget={})，使用紧急回退预算",
                    fixedTokens, maxContextTokens);
            availableTokens = maxContextTokens / 2; // 紧急回退：使用一半预算
        }

        StringBuilder contextBuilder = new StringBuilder();
        int usedTokens = 0;
        int docCount = 0;

        for (RetrievedDocument doc : documents) {
            String docSegment = String.format("【%s】\n%s",
                    doc.getDocumentTitle(), doc.getContent());
            int docTokens = estimateTokens(docSegment);

            // 文档间分隔符 "\n\n" 约 1 token
            if (docCount > 0) {
                docTokens += 1;
            }

            if (usedTokens + docTokens > availableTokens) {
                log.info("Token 预算已满: used={}, nextDoc={}, budget={}, "
                                + "totalCandidates={}, included={}",
                        usedTokens, docTokens, availableTokens,
                        documents.size(), docCount);
                break;
            }

            if (docCount > 0) {
                contextBuilder.append("\n\n");
            }
            contextBuilder.append(docSegment);
            usedTokens += docTokens;
            docCount++;
        }

        log.info("上下文构建完成: {}/{} 篇文档被采用, 估算 tokens: {}/{}",
                docCount, documents.size(), usedTokens, availableTokens);

        return contextBuilder.toString();
    }

    // ==================== 聚合查询路径（批次 04 · 任务 4.4.4，问题 13） ====================

    /** 单条记录在紧凑格式下的最大字符数（超出即截断，避免单条记录吃掉整个预算） */
    private static final int COMPACT_ENTRY_MAX_CHARS = 100;

    /**
     * 构建聚合查询的上下文（紧凑格式）。
     * <p>
     * 与 {@link #buildContextWithBudget} 的区别：
     * <ul>
     *   <li>语义路径：保留完整 chunk 文本，按相关性排序，取最相关的前若干篇；</li>
     *   <li>聚合路径：按自然顺序列出<b>全部</b>匹配记录，单条使用紧凑格式
     *       （完整文本 ~50 tokens/条 → 紧凑格式 ~10 tokens/条，密度提升约 5 倍），
     *       并显式标注「匹配记录总数」，让 LLM 能区分"只有 N 条"与"只展示了前 N 条"。</li>
     * </ul>
     * </p>
     * <p>
     * Token 预算层（L4）保留：它是必要的上下文保护，聚合路径同样受其约束；
     * 一旦发生裁剪，返回值中的 {@code includedCount < totalCount} 即为
     * "结果可能不完整"的判定依据。
     * </p>
     *
     * @param documents  全部匹配的文档（已去重、已排序）
     * @param promptBase 系统提示词基础文本
     * @param query      用户原始问题
     * @param totalCount 匹配记录总数
     * @return 聚合上下文及其计数信息
     */
    public AggregationContext buildAggregationContext(List<RetrievedDocument> documents,
                                                      String promptBase,
                                                      String query,
                                                      int totalCount) {
        int fixedTokens = estimateTokens(promptBase)
                        + estimateTokens(query)
                        + RESPONSE_RESERVED_TOKENS;
        int availableTokens = maxContextTokens - fixedTokens;

        if (availableTokens <= 0) {
            log.warn("固定开销已超过 token 预算 (fixed={}, budget={})，使用紧急回退预算",
                    fixedTokens, maxContextTokens);
            availableTokens = maxContextTokens / 2;
        }

        String header = "【匹配记录总数: " + totalCount + " 条】\n";
        StringBuilder contextBuilder = new StringBuilder(header);
        int usedTokens = estimateTokens(header);
        int includedCount = 0;
        boolean truncated = false;

        for (RetrievedDocument doc : documents) {
            String compactEntry = buildCompactEntry(doc);
            int entryTokens = estimateTokens(compactEntry) + 1; // +1：换行分隔符

            if (usedTokens + entryTokens > availableTokens) {
                truncated = true;
                break;
            }

            contextBuilder.append(compactEntry).append('\n');
            usedTokens += entryTokens;
            includedCount++;
        }

        if (truncated) {
            contextBuilder.append("[Token预算已满，以下仅展示 ")
                    .append(includedCount).append('/').append(totalCount).append(" 条记录]");
        }

        log.info("聚合上下文构建完成: {}/{} 条记录被采用, 估算 tokens: {}/{}",
                includedCount, totalCount, usedTokens, availableTokens);

        return new AggregationContext(contextBuilder.toString(), totalCount, includedCount);
    }

    /**
     * 从 RetrievedDocument 构建紧凑的单条记录。
     * <p>内容本身是 JSON 时直接使用；否则按 {@link #COMPACT_ENTRY_MAX_CHARS} 截断。</p>
     */
    private String buildCompactEntry(RetrievedDocument doc) {
        String content = doc.getContent();
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        if (trimmed.length() > COMPACT_ENTRY_MAX_CHARS) {
            return trimmed.substring(0, COMPACT_ENTRY_MAX_CHARS) + "...";
        }
        return trimmed;
    }

    /**
     * 聚合上下文构建结果。
     *
     * @param text          上下文文本（含匹配记录总数与截断标注）
     * @param totalCount    匹配记录总数
     * @param includedCount 实际进入上下文的记录数（小于 totalCount 即表示结果被裁剪）
     */
    public record AggregationContext(String text, int totalCount, int includedCount) {

        /** 是否发生了 Token 预算裁剪（任务 4.4.6：需在回答中提示"结果可能不完整"） */
        public boolean isTruncated() {
            return includedCount < totalCount;
        }
    }
}
