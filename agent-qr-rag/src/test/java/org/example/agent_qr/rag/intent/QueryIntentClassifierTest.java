package org.example.agent_qr.rag.intent;

import org.example.agent_qr.rag.provider.LLMProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QueryIntentClassifier} 测试（批次 04 · 任务 4.4.2，问题 13）。
 * <p>
 * 拦截的核心缺陷：检索管道只有一条"取最相关 Top-K"的路径，
 * 「有哪些人已经离职」这类列举查询被 final-top-k 截断，用户拿不到完整结果。
 * 分类器负责把这类问题分流到聚合路径；误判为 AGGREGATION 会走错路径，
 * 误判为 SEMANTIC 则退化为截断（与本任务目标一致的安全方向）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryIntentClassifierTest {

    @Mock
    private ProviderFactory providerFactory;

    @Mock
    private LLMProvider llmProvider;

    @InjectMocks
    private QueryIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(classifier, "llmClassifyEnabled", false);
        ReflectionTestUtils.setField(classifier, "timeoutSeconds", 2);
    }

    @Test
    @DisplayName("★『XX 流程是什么』→ SEMANTIC（语义类走原混合检索路径）")
    void classify_shouldReturnSemantic_forProcessQuestion() {
        assertThat(classifier.classify("离职流程是什么")).isEqualTo(QueryIntentClassifier.IntentType.SEMANTIC);
        assertThat(classifier.classify("公司的考勤制度怎么规定的")).isEqualTo(QueryIntentClassifier.IntentType.SEMANTIC);
    }

    @Test
    @DisplayName("★『列出所有 XX』→ AGGREGATION（需要完整数据集）")
    void classify_shouldReturnAggregation_forListQuestion() {
        assertThat(classifier.classify("列出所有离职员工")).isEqualTo(QueryIntentClassifier.IntentType.AGGREGATION);
        assertThat(classifier.classify("公司有哪些人已经离职")).isEqualTo(QueryIntentClassifier.IntentType.AGGREGATION);
        assertThat(classifier.classify("都有谁在研发部")).isEqualTo(QueryIntentClassifier.IntentType.AGGREGATION);
        assertThat(classifier.classify("给我一份部门名单")).isEqualTo(QueryIntentClassifier.IntentType.AGGREGATION);
    }

    @Test
    @DisplayName("★ 统计类问题 → AGGREGATION")
    void classify_shouldReturnAggregation_forStatisticsQuestion() {
        assertThat(classifier.classify("一共有多少人离职")).isEqualTo(QueryIntentClassifier.IntentType.AGGREGATION);
        assertThat(classifier.classify("各部门人数统计")).isEqualTo(QueryIntentClassifier.IntentType.AGGREGATION);
        assertThat(classifier.classify("待审核文档数量是多少")).isEqualTo(QueryIntentClassifier.IntentType.AGGREGATION);
    }

    @Test
    @DisplayName("空问题 → SEMANTIC（不触发聚合路径）")
    void classify_shouldReturnSemantic_forBlankQuery() {
        assertThat(classifier.classify(null)).isEqualTo(QueryIntentClassifier.IntentType.SEMANTIC);
        assertThat(classifier.classify("   ")).isEqualTo(QueryIntentClassifier.IntentType.SEMANTIC);
    }

    @Test
    @DisplayName("LLM 兜底默认关闭：规则未命中时不得产生额外 LLM 调用")
    void classify_shouldNotCallLlm_whenFallbackDisabled() {
        QueryIntentClassifier.IntentType type = classifier.classify("帮我看看公司的情况");

        assertThat(type).isEqualTo(QueryIntentClassifier.IntentType.SEMANTIC);
        verify(providerFactory, never()).getLLMProvider();
    }

    @Test
    @DisplayName("LLM 兜底开启且规则未命中：按 LLM 判定分流")
    void classify_shouldUseLlmFallback_whenEnabled() {
        ReflectionTestUtils.setField(classifier, "llmClassifyEnabled", true);
        when(providerFactory.getLLMProvider()).thenReturn(llmProvider);
        when(llmProvider.generate(any())).thenReturn("AGGREGATION");

        assertThat(classifier.classify("把公司的人都给我看看"))
                .isEqualTo(QueryIntentClassifier.IntentType.AGGREGATION);
    }

    @Test
    @DisplayName("LLM 兜底失败 → 回落 SEMANTIC（不误触发聚合路径）")
    void classify_shouldFallBackToSemantic_whenLlmFails() {
        ReflectionTestUtils.setField(classifier, "llmClassifyEnabled", true);
        when(providerFactory.getLLMProvider()).thenReturn(llmProvider);
        when(llmProvider.generate(any())).thenThrow(new RuntimeException("llm down"));

        assertThat(classifier.classify("把公司的人都给我看看"))
                .isEqualTo(QueryIntentClassifier.IntentType.SEMANTIC);
    }

    @Test
    @DisplayName("LLM 兜底返回非预期内容 → SEMANTIC")
    void classify_shouldFallBackToSemantic_whenLlmOutputUnclear() {
        ReflectionTestUtils.setField(classifier, "llmClassifyEnabled", true);
        when(providerFactory.getLLMProvider()).thenReturn(llmProvider);
        when(llmProvider.generate(any())).thenReturn("我不确定");

        assertThat(classifier.classify("把公司的人都给我看看"))
                .isEqualTo(QueryIntentClassifier.IntentType.SEMANTIC);
    }
}
