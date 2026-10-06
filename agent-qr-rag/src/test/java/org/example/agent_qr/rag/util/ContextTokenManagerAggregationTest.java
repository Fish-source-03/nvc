package org.example.agent_qr.rag.util;

import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContextTokenManager#buildAggregationContext} 测试（批次 04 · 任务 4.4.4，问题 13）。
 * <p>
 * 拦截的核心缺陷：L4 Token 预算层会对聚合结果二次裁剪，而旧实现既不标注总数、
 * 也不提示"被裁剪"，用户无法区分"只有 15 条"与"只显示了前 15 条"。
 * 本测试固化：① 紧凑格式 + 匹配记录总数；② 裁剪时显式标注；③ {@code isTruncated} 判定正确。
 * </p>
 *
 * @author agent-qr
 */
class ContextTokenManagerAggregationTest {

    private ContextTokenManager contextTokenManager;

    @BeforeEach
    void setUp() {
        contextTokenManager = new ContextTokenManager();
        ReflectionTestUtils.setField(contextTokenManager, "maxContextTokens", 100_000);
    }

    private RetrievedDocument doc(int index) {
        RetrievedDocument document = new RetrievedDocument();
        document.setChunkId((long) index);
        document.setContent("记录" + index + "：研发部员工张三");
        return document;
    }

    private List<RetrievedDocument> docs(int count) {
        List<RetrievedDocument> documents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            documents.add(doc(i));
        }
        return documents;
    }

    @Test
    @DisplayName("预算充足：全部记录进入上下文，并标注匹配记录总数")
    void buildAggregationContext_shouldIncludeAllRecords_withinBudget() {
        List<RetrievedDocument> documents = docs(40);

        ContextTokenManager.AggregationContext context = contextTokenManager.buildAggregationContext(
                documents, "base", "列出所有研发部员工", documents.size());

        assertThat(context.text()).contains("【匹配记录总数: 40 条】");
        assertThat(context.text()).contains("记录0").contains("记录39");
        assertThat(context.includedCount()).isEqualTo(40);
        assertThat(context.isTruncated()).isFalse();
        // 紧凑格式：不包含语义路径的【标题】分隔
        assertThat(context.text()).doesNotContain("【记录");
    }

    @Test
    @DisplayName("★ 预算不足：显式标注『仅展示 x/y 条记录』，且 isTruncated 为真（任务 4.4.6）")
    void buildAggregationContext_shouldMarkTruncation_whenBudgetExceeded() {
        ReflectionTestUtils.setField(contextTokenManager, "maxContextTokens", 2100);
        List<RetrievedDocument> documents = docs(40);

        ContextTokenManager.AggregationContext context = contextTokenManager.buildAggregationContext(
                documents, "base", "列出所有研发部员工", documents.size());

        assertThat(context.text()).contains("【匹配记录总数: 40 条】");
        assertThat(context.text())
                .as("裁剪必须显式标注，用户/LLM 才能区分'只有 N 条'与'显示了前 N 条'")
                .contains("[Token预算已满，以下仅展示 ");
        assertThat(context.includedCount()).isBetween(1, 39);
        assertThat(context.isTruncated()).isTrue();
    }

    @Test
    @DisplayName("单条为 JSON 时原样使用（Token 密度提升的紧凑格式）")
    void buildAggregationContext_shouldKeepJsonContentAsCompactEntry() {
        RetrievedDocument jsonDoc = new RetrievedDocument();
        jsonDoc.setChunkId(1L);
        jsonDoc.setContent("{\"name\":\"张三\",\"department\":\"RD\"}");

        ContextTokenManager.AggregationContext context = contextTokenManager.buildAggregationContext(
                List.of(jsonDoc), "base", "列出所有员工", 1);

        assertThat(context.text()).contains("{\"name\":\"张三\",\"department\":\"RD\"}");
    }

    @Test
    @DisplayName("超长文本按紧凑上限截断（单条记录不得吃掉整个预算）")
    void buildAggregationContext_shouldTruncateLongContent() {
        RetrievedDocument longDoc = new RetrievedDocument();
        longDoc.setChunkId(1L);
        longDoc.setContent("x".repeat(500));

        ContextTokenManager.AggregationContext context = contextTokenManager.buildAggregationContext(
                List.of(longDoc), "base", "列出所有员工", 1);

        assertThat(context.text()).contains("x".repeat(100) + "...");
        assertThat(context.text()).doesNotContain("x".repeat(101));
    }

    @Test
    @DisplayName("语义路径上下文构建行为不受影响（回归）")
    void buildContextWithBudget_shouldRemainUnchanged() {
        String text = contextTokenManager.buildContextWithBudget(
                docs(3), "base", "离职流程是什么");

        assertThat(text).contains("【") .contains("记录0");
        assertThat(contextTokenManager.estimateTokens(text)).isGreaterThan(0);
    }
}
