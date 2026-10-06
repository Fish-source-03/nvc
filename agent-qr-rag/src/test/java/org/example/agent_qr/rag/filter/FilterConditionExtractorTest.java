package org.example.agent_qr.rag.filter;

import dev.langchain4j.data.message.ChatMessage;
import org.example.agent_qr.rag.filter.FilterConditionExtractor.FieldDefinition;
import org.example.agent_qr.rag.filter.mapper.ChunkStructuredFilterMapper;
import org.example.agent_qr.rag.provider.LLMProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FilterConditionExtractor} 测试（批次 04 · 任务 4.3，问题 12）。
 * <p>
 * 拦截的核心缺陷：{@code FilterCondition} 在整个代码库中从未被构造过，
 * {@code ChatQueryService} 两个调用点恒传 {@code List.of()}——SQL 过滤基础设施"接了线没通电"。
 * </p>
 * <p>
 * 本测试固化：① 正常提取（字段/操作符/值）；② 灰度开关默认关闭时提取器完全不被调用；
 * ③ 任何失败（超时 / LLM 异常 / JSON 解析失败 / 校验不通过）都降级为「无过滤条件」，
 * <b>绝不让问答主流程失败</b>；④ 产出端保证 operator 合法（与任务 4.1 配套）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FilterConditionExtractorTest {

    @Mock
    private ProviderFactory providerFactory;

    @Mock
    private ChunkStructuredFilterMapper chunkStructuredFilterMapper;

    @Mock
    private LLMProvider llmProvider;

    @InjectMocks
    private FilterConditionExtractor extractor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "timeoutSeconds", 2);
    }

    private FieldDefinition field(String fieldName, String fieldType) {
        FieldDefinition definition = new FieldDefinition();
        definition.setFieldName(fieldName);
        definition.setFieldType(fieldType);
        return definition;
    }

    /** HR 域的真实字段形态（kb_chunk_structured 实测：NUMBER + STRING，无 ENUM/DATE）。 */
    private void givenHrFields() {
        when(chunkStructuredFilterMapper.selectDistinctFieldsByDomain("HR"))
                .thenReturn(List.of(field("clearance_level", "NUMBER"), field("department", "STRING")));
    }

    private void givenLlmReturns(String response) {
        when(providerFactory.getLLMProvider()).thenReturn(llmProvider);
        when(llmProvider.generate(any())).thenReturn(response);
    }

    @Test
    @DisplayName("★ 能从『月薪大于 1 万的研发部员工有哪些』提取出字段/操作符/值")
    void extract_shouldExtractFieldOperatorAndValue() {
        givenHrFields();
        givenLlmReturns("""
                [{"fieldName":"clearance_level","fieldType":"NUMBER","operator":"GT","value":"10000"},
                 {"fieldName":"department","fieldType":"STRING","operator":"EQ","value":"RD"}]
                """);

        List<FilterCondition> conditions = extractor.extract("月薪大于 1 万的研发部员工有哪些", "HR");

        assertThat(conditions).hasSize(2);
        assertThat(conditions)
                .extracting(FilterCondition::getFieldName, FilterCondition::getOperator, FilterCondition::getValue)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("clearance_level", "GT", "10000"),
                        org.assertj.core.groups.Tuple.tuple("department", "EQ", "RD"));
    }

    @Test
    @DisplayName("★ 灰度开关关闭（默认）时提取器不调用 LLM、不查字段，返回空列表")
    void extract_shouldDoNothing_whenDisabled() {
        ReflectionTestUtils.setField(extractor, "enabled", false);

        List<FilterCondition> conditions = extractor.extract("月薪大于 1 万", "HR");

        assertThat(conditions).isEmpty();
        verifyNoInteractions(chunkStructuredFilterMapper, providerFactory);
    }

    @Test
    @DisplayName("提问中提供了可用字段定义（Prompt 注入），LLM 拿到的是问题原文")
    void extract_shouldInjectAvailableFieldsIntoPrompt() {
        givenHrFields();
        givenLlmReturns("[]");

        extractor.extract("研发部有多少人", "HR");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmProvider).generate(captor.capture());
        String systemPrompt = captor.getValue().get(0).toString();
        assertThat(systemPrompt).contains("clearance_level").contains("department");
        assertThat(captor.getValue().get(1).toString()).contains("研发部有多少人");
    }

    @Test
    @DisplayName("枚举字段会补充可选值列表（供 LLM 与校验使用）")
    void extract_shouldLoadEnumValues_forEnumField() {
        FieldDefinition dept = field("department", "ENUM");
        when(chunkStructuredFilterMapper.selectDistinctFieldsByDomain("HR")).thenReturn(List.of(dept));
        when(chunkStructuredFilterMapper.selectEnumValues("department", "HR"))
                .thenReturn(List.of("HR", "RD", "SALES"));
        givenLlmReturns("[]");

        extractor.extract("研发部有多少人", "HR");

        assertThat(dept.getEnumValues()).containsExactly("HR", "RD", "SALES");
    }

    @Test
    @DisplayName("★ LLM 调用异常 → 降级为无过滤条件（不阻塞问答）")
    void extract_shouldDegradeToEmpty_whenLlmThrows() {
        givenHrFields();
        when(providerFactory.getLLMProvider()).thenReturn(llmProvider);
        when(llmProvider.generate(any())).thenThrow(new RuntimeException("ollama 不可用"));

        assertThat(extractor.extract("月薪大于 1 万", "HR")).isEmpty();
    }

    @Test
    @DisplayName("★ LLM 超时 → 降级为无过滤条件（不阻塞问答）")
    void extract_shouldDegradeToEmpty_whenLlmTimesOut() {
        givenHrFields();
        ReflectionTestUtils.setField(extractor, "timeoutSeconds", 1);
        when(providerFactory.getLLMProvider()).thenReturn(llmProvider);
        when(llmProvider.generate(any())).thenAnswer(invocation -> {
            Thread.sleep(30_000L);
            return "[]";
        });

        long start = System.currentTimeMillis();
        List<FilterCondition> conditions = extractor.extract("月薪大于 1 万", "HR");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(conditions).isEmpty();
        assertThat(elapsed)
                .as("超时后必须立即降级，而不是等 LLM 返回")
                .isLessThan(10_000L);
    }

    @Test
    @DisplayName("★ LLM 返回格式错误（非 JSON）→ 降级为无过滤条件")
    void extract_shouldDegradeToEmpty_whenResponseIsNotJson() {
        givenHrFields();
        givenLlmReturns("抱歉，我无法理解这个问题。");

        assertThat(extractor.extract("月薪大于 1 万", "HR")).isEmpty();
    }

    @Test
    @DisplayName("LLM 返回空字符串 → 降级为无过滤条件")
    void extract_shouldDegradeToEmpty_whenResponseIsBlank() {
        givenHrFields();
        givenLlmReturns("   ");

        assertThat(extractor.extract("月薪大于 1 万", "HR")).isEmpty();
    }

    @Test
    @DisplayName("兼容被 ```json 代码块包裹的响应")
    void extract_shouldParseJsonWrappedInMarkdownBlock() {
        givenHrFields();
        givenLlmReturns("""
                ```json
                [{"fieldName":"department","fieldType":"STRING","operator":"EQ","value":"RD"}]
                ```
                """);

        List<FilterCondition> conditions = extractor.extract("研发部有哪些人", "HR");

        assertThat(conditions).singleElement()
                .extracting(FilterCondition::getFieldName).isEqualTo("department");
    }

    @Test
    @DisplayName("无过滤意图（LLM 返回 []）→ 空列表，走原链路")
    void extract_shouldReturnEmpty_whenNoFilterIntent() {
        givenHrFields();
        givenLlmReturns("[]");

        assertThat(extractor.extract("公司的考勤制度是什么", "HR")).isEmpty();
    }

    @Test
    @DisplayName("域为空 / 域内无可用字段 → 不调用 LLM")
    void extract_shouldSkip_whenNoAvailableFields() {
        when(chunkStructuredFilterMapper.selectDistinctFieldsByDomain("FINANCE")).thenReturn(List.of());

        assertThat(extractor.extract("金额大于 100 万", "FINANCE")).isEmpty();
        verifyNoInteractions(providerFactory);

        assertThat(extractor.extract("金额大于 100 万", " ")).isEmpty();
    }

    // ==================== 校验层（含任务 4.1.2 的产出端保障） ====================

    @Test
    @DisplayName("字段名不在可用字段中 → 丢弃该条件")
    void extract_shouldDiscard_whenFieldNameUnknown() {
        givenHrFields();
        givenLlmReturns("[{\"fieldName\":\"salary\",\"fieldType\":\"NUMBER\",\"operator\":\"GT\",\"value\":\"10000\"}]");

        assertThat(extractor.extract("月薪大于 1 万", "HR")).isEmpty();
    }

    @Test
    @DisplayName("字段类型与真实定义不一致 → 丢弃该条件")
    void extract_shouldDiscard_whenFieldTypeMismatch() {
        givenHrFields();
        givenLlmReturns("[{\"fieldName\":\"department\",\"fieldType\":\"NUMBER\",\"operator\":\"EQ\",\"value\":\"RD\"}]");

        assertThat(extractor.extract("研发部有哪些人", "HR")).isEmpty();
    }

    @Test
    @DisplayName("★ 操作符非法 → 丢弃该条件（不能产出会静默退化的条件）")
    void extract_shouldDiscard_whenOperatorIsInvalid() {
        givenHrFields();
        givenLlmReturns("[{\"fieldName\":\"clearance_level\",\"fieldType\":\"NUMBER\",\"operator\":\"APPROX\",\"value\":\"10000\"}]");

        assertThat(extractor.extract("月薪约 1 万", "HR")).isEmpty();
    }

    @Test
    @DisplayName("字符串字段使用比较操作符 → 丢弃该条件（避免静默等值匹配）")
    void extract_shouldDiscard_whenStringFieldUsesComparisonOperator() {
        givenHrFields();
        givenLlmReturns("[{\"fieldName\":\"department\",\"fieldType\":\"STRING\",\"operator\":\"GT\",\"value\":\"RD\"}]");

        assertThat(extractor.extract("研发部有哪些人", "HR")).isEmpty();
    }

    @Test
    @DisplayName("数值字段的值无法解析为数字 → 丢弃该条件")
    void extract_shouldDiscard_whenNumberValueIsNotNumeric() {
        givenHrFields();
        givenLlmReturns("[{\"fieldName\":\"clearance_level\",\"fieldType\":\"NUMBER\",\"operator\":\"GT\",\"value\":\"一万\"}]");

        assertThat(extractor.extract("月薪大于 1 万", "HR")).isEmpty();
    }

    @Test
    @DisplayName("BETWEEN 缺少边界值 → 丢弃该条件")
    void extract_shouldDiscard_whenBetweenBoundsMissing() {
        givenHrFields();
        givenLlmReturns("[{\"fieldName\":\"clearance_level\",\"fieldType\":\"NUMBER\",\"operator\":\"BETWEEN\",\"minValue\":\"1\"}]");

        assertThat(extractor.extract("职级 1 到 3 的员工", "HR")).isEmpty();
    }

    @Test
    @DisplayName("缺少 operator 但带单值 → 归一化为 EQ（而不是留下会退化的区间条件）")
    void extract_shouldNormalizeMissingOperatorToEq() {
        givenHrFields();
        givenLlmReturns("[{\"fieldName\":\"department\",\"fieldType\":\"STRING\",\"value\":\"RD\"}]");

        List<FilterCondition> conditions = extractor.extract("研发部有哪些人", "HR");

        assertThat(conditions).singleElement()
                .extracting(FilterCondition::getOperator).isEqualTo(FilterCondition.OP_EQ);
    }

    @Test
    @DisplayName("缺少 operator 但带完整区间 → 归一化为 BETWEEN")
    void extract_shouldNormalizeMissingOperatorToBetween() {
        givenHrFields();
        givenLlmReturns("[{\"fieldName\":\"clearance_level\",\"fieldType\":\"NUMBER\",\"minValue\":\"1\",\"maxValue\":\"3\"}]");

        List<FilterCondition> conditions = extractor.extract("职级 1 到 3 的员工", "HR");

        assertThat(conditions).singleElement()
                .extracting(FilterCondition::getOperator).isEqualTo(FilterCondition.OP_BETWEEN);
    }

    @Test
    @DisplayName("枚举值不在真实枚举集合中 → 丢弃该条件")
    void extract_shouldDiscard_whenEnumValueInvalid() {
        FieldDefinition dept = field("department", "ENUM");
        when(chunkStructuredFilterMapper.selectDistinctFieldsByDomain("HR")).thenReturn(List.of(dept));
        when(chunkStructuredFilterMapper.selectEnumValues(anyString(), eq("HR")))
                .thenReturn(List.of("HR", "RD", "SALES"));
        givenLlmReturns("[{\"fieldName\":\"department\",\"fieldType\":\"ENUM\",\"operator\":\"EQ\",\"value\":\"市场部\"}]");

        assertThat(extractor.extract("市场部有哪些人", "HR")).isEmpty();
    }

    @Test
    @DisplayName("LLM 输出含多余字段/未知属性时不应解析失败（忽略未知属性）")
    void extract_shouldIgnoreUnknownProperties() {
        givenHrFields();
        givenLlmReturns("""
                [{"fieldName":"department","fieldType":"STRING","operator":"EQ","value":"RD",
                  "enumValues":[],"confidence":0.9}]
                """);

        assertThat(extractor.extract("研发部有哪些人", "HR")).hasSize(1);
    }

    @Test
    @DisplayName("提取器异常不得向上抛出（问答主流程必须继续）")
    void extract_shouldNeverThrow_evenWhenMapperFails() {
        when(chunkStructuredFilterMapper.selectDistinctFieldsByDomain("HR"))
                .thenThrow(new RuntimeException("db down"));

        assertThat(extractor.extract("月薪大于 1 万", "HR")).isEmpty();
    }

    @Test
    @DisplayName("未启用时不查询 LLM：verify 交互次数为 0")
    void extract_shouldNotCallLlm_whenDisabled() {
        ReflectionTestUtils.setField(extractor, "enabled", false);

        extractor.extract("月薪大于 1 万", "HR");

        verify(providerFactory, never()).getLLMProvider();
    }
}
