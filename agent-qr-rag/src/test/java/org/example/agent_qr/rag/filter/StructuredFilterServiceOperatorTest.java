package org.example.agent_qr.rag.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.agent_qr.rag.filter.mapper.ChunkStructuredFilterMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StructuredFilterService} 操作符语义测试（批次 04 · 任务 4.1，问题 20）。
 * <p>
 * 拦截的核心缺陷：{@code FilterCondition.operator} 定义了 GT/GTE/LT/LTE 常量却从不被读取，
 * "小于 5000" 会被解析成闭区间 [5000, 5000]，即 <b>等值查询</b>——
 * 结果看起来正常返回，因此极难定位；一旦接入 LLM 提取器（任务 4.3）会立即爆发。
 * </p>
 * <p>
 * 本测试断言的是"下推到 SQL 的比较语义"：通过 Mapper 交互固化
 * GT→{@code >}、GTE→{@code >=}、LT→{@code <}、LTE→{@code <=}、无 operator→闭区间。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StructuredFilterServiceOperatorTest {

    @Mock
    private ChunkStructuredFilterMapper chunkStructuredFilterMapper;

    @InjectMocks
    private StructuredFilterService structuredFilterService;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(structuredFilterService, "unboundedLimit", 2000);
        serviceLogger = (Logger) LoggerFactory.getLogger(StructuredFilterService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    private FilterCondition numberCondition(String operator, String value) {
        return FilterCondition.builder()
                .fieldName("clearance_level")
                .fieldType("NUMBER")
                .operator(operator)
                .value(value)
                .build();
    }

    private boolean warnedAbout(String fragment) {
        return logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains(fragment));
    }

    // ==================== NUMBER 操作符 ====================

    @Test
    @DisplayName("★ OP_GT → SQL 为 `> 10000`，而不是等值 `= 10000`")
    void dispatch_shouldUseStrictGreaterThan_whenOperatorIsGt() {
        when(chunkStructuredFilterMapper.selectChunkIdsByNumberGt(any(), any(), anyInt()))
                .thenReturn(List.of(11L, 12L));

        List<Long> result = structuredFilterService.filterChunkIds(null, List.of(numberCondition("GT", "10000")));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberGt("clearance_level", new BigDecimal("10000"), 500);
        verify(chunkStructuredFilterMapper, never())
                .selectChunkIdsByNumberRange(any(), any(), any(), anyInt());
        verify(chunkStructuredFilterMapper, never())
                .selectChunkIdsByNumberGte(any(), any(), anyInt());
        assertThat(result).containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("★ OP_LT → SQL 为 `< 5000`（修复前会退化为 `= 5000`）")
    void dispatch_shouldUseStrictLessThan_whenOperatorIsLt() {
        when(chunkStructuredFilterMapper.selectChunkIdsByNumberLt(any(), any(), anyInt()))
                .thenReturn(List.of(7L));

        structuredFilterService.filterChunkIds(null, List.of(numberCondition("LT", "5000")));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberLt("clearance_level", new BigDecimal("5000"), 500);
        verify(chunkStructuredFilterMapper, never())
                .selectChunkIdsByNumberRange(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("OP_GTE → SQL 为 `>= value`（边界包含）")
    void dispatch_shouldUseGreaterThanOrEqual_whenOperatorIsGte() {
        when(chunkStructuredFilterMapper.selectChunkIdsByNumberGte(any(), any(), anyInt()))
                .thenReturn(List.of(3L));

        structuredFilterService.filterChunkIds(null, List.of(numberCondition("GTE", "3")));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberGte("clearance_level", new BigDecimal("3"), 500);
        verify(chunkStructuredFilterMapper, never())
                .selectChunkIdsByNumberLt(any(), any(), anyInt());
    }

    @Test
    @DisplayName("OP_LTE → SQL 为 `<= value`（边界包含）")
    void dispatch_shouldUseLessThanOrEqual_whenOperatorIsLte() {
        when(chunkStructuredFilterMapper.selectChunkIdsByNumberLte(any(), any(), anyInt()))
                .thenReturn(List.of(4L));

        structuredFilterService.filterChunkIds(null, List.of(numberCondition("LTE", "4")));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberLte("clearance_level", new BigDecimal("4"), 500);
        verify(chunkStructuredFilterMapper, never())
                .selectChunkIdsByNumberGt(any(), any(), anyInt());
    }

    @Test
    @DisplayName("OP_EQ → 闭区间 [value, value]（等值语义，不再是隐式退化）")
    void dispatch_shouldUseEqualityRange_whenOperatorIsEq() {
        structuredFilterService.filterChunkIds(null, List.of(numberCondition("EQ", "2")));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberRange(
                "clearance_level", new BigDecimal("2"), new BigDecimal("2"), 500);
    }

    @Test
    @DisplayName("操作符大小写不敏感（LLM 产出可能为小写）")
    void dispatch_shouldNormalizeOperatorCase() {
        structuredFilterService.filterChunkIds(null, List.of(numberCondition("gt", "10000")));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberGt("clearance_level", new BigDecimal("10000"), 500);
    }

    @Test
    @DisplayName("OP_BETWEEN → 维持闭区间语义（与无 operator 一致）")
    void dispatch_shouldUseClosedRange_whenOperatorIsBetween() {
        FilterCondition condition = FilterCondition.builder()
                .fieldName("clearance_level").fieldType("NUMBER")
                .operator("BETWEEN").minValue("1").maxValue("3").build();

        structuredFilterService.filterChunkIds(null, List.of(condition));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberRange(
                "clearance_level", new BigDecimal("1"), new BigDecimal("3"), 500);
    }

    // ==================== 无 operator：保持既有区间语义（禁止事项） ====================

    @Test
    @DisplayName("★ 无 operator 时维持现有区间语义 [minValue, maxValue]（不得改变）")
    void dispatch_shouldKeepRangeSemantics_whenOperatorIsAbsent() {
        FilterCondition condition = FilterCondition.builder()
                .fieldName("clearance_level").fieldType("NUMBER")
                .minValue("1").maxValue("3").build();

        structuredFilterService.filterChunkIds(null, List.of(condition));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberRange(
                "clearance_level", new BigDecimal("1"), new BigDecimal("3"), 500);
    }

    @Test
    @DisplayName("无 operator 且只有 value 时，维持历史行为：区间退化为 [value, value]")
    void dispatch_shouldKeepLegacyEqualityRange_whenOperatorIsAbsentAndOnlyValueGiven() {
        FilterCondition condition = FilterCondition.builder()
                .fieldName("clearance_level").fieldType("NUMBER")
                .value("5").build();

        structuredFilterService.filterChunkIds(null, List.of(condition));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberRange(
                "clearance_level", new BigDecimal("5"), new BigDecimal("5"), 500);
    }

    // ==================== 非法 operator（任务 4.1.2） ====================

    @Test
    @DisplayName("★ 非法 operator：记录 WARN 后按区间语义处理，不静默")
    void dispatch_shouldWarnAndFallBackToRange_whenOperatorIsInvalid() {
        structuredFilterService.filterChunkIds(null, List.of(numberCondition("≈", "10000")));

        assertThat(warnedAbout("非法的过滤操作符"))
                .as("非法 operator 必须有 WARN 级可观测性，不能静默退化")
                .isTrue();
        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberRange(
                "clearance_level", new BigDecimal("10000"), new BigDecimal("10000"), 500);
    }

    @Test
    @DisplayName("操作符合法但单值无法解析时：记录 WARN 后按区间语义处理")
    void dispatch_shouldWarnAndFallBackToRange_whenValueIsNotNumeric() {
        structuredFilterService.filterChunkIds(null, List.of(numberCondition("GT", "一万")));

        assertThat(warnedAbout("数值条件解析失败") || warnedAbout("缺少可解析的单值")).isTrue();
        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberRange(
                "clearance_level", new BigDecimal("0"), new BigDecimal("999999999"), 500);
    }

    // ==================== DATE 操作符（与 NUMBER 对称） ====================

    @Test
    @DisplayName("DATE 条件同样按 operator 分派（LT → `date < value`）")
    void dispatch_shouldUseStrictLessThan_whenDateOperatorIsLt() {
        FilterCondition condition = FilterCondition.builder()
                .fieldName("create_time").fieldType("DATE")
                .operator("LT").value("2025-06-15").build();

        structuredFilterService.filterChunkIds(null, List.of(condition));

        verify(chunkStructuredFilterMapper).selectChunkIdsByDateLt(
                "create_time", LocalDate.of(2025, 6, 15), 500);
        verify(chunkStructuredFilterMapper, never())
                .selectChunkIdsByDateRange(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("DATE 无 operator 时维持闭区间语义")
    void dispatch_shouldKeepDateRangeSemantics_whenOperatorIsAbsent() {
        FilterCondition condition = FilterCondition.builder()
                .fieldName("create_time").fieldType("DATE")
                .minValue("2025-01-01").maxValue("2025-12-31").build();

        structuredFilterService.filterChunkIds(null, List.of(condition));

        verify(chunkStructuredFilterMapper).selectChunkIdsByDateRange(
                "create_time", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 500);
    }

    // ==================== 多条件 AND / 域过滤 / 聚合路径上限 ====================

    @Test
    @DisplayName("多条件按 AND 取交集（操作符修复不影响组合语义）")
    void filterChunkIds_shouldIntersectMultipleConditions() {
        when(chunkStructuredFilterMapper.selectChunkIdsByNumberGt(any(), any(), anyInt()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(chunkStructuredFilterMapper.selectChunkIdsByStringValue(any(), any(), anyInt()))
                .thenReturn(List.of(2L, 3L, 4L));

        List<Long> result = structuredFilterService.filterChunkIds(null, List.of(
                numberCondition("GT", "10000"),
                FilterCondition.builder().fieldName("department").fieldType("STRING").value("RD").build()));

        assertThat(result).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("无条件但指定域：走域查询，SQL 上限为 500")
    void filterChunkIds_shouldQueryDomain_whenNoConditionsGiven() {
        when(chunkStructuredFilterMapper.selectChunkIdsByDomain(eq("HR"), anyInt()))
                .thenReturn(List.of(1L, 2L));
        when(chunkStructuredFilterMapper.selectChunkIdsByDocumentDomain(eq("HR"), anyInt()))
                .thenReturn(List.of(3L));

        List<Long> result = structuredFilterService.filterChunkIds("HR", List.of());

        assertThat(result).containsExactly(1L, 2L, 3L);
        verify(chunkStructuredFilterMapper).selectChunkIdsByDomain("HR", 500);
        verify(chunkStructuredFilterMapper).selectChunkIdsByDocumentDomain("HR", 500);
    }

    @Test
    @DisplayName("聚合路径（无界过滤）：SQL 上限放宽到 2000，语义与语义路径一致")
    void filterChunkIdsUnbounded_shouldUseSafetyLimit() {
        when(chunkStructuredFilterMapper.selectChunkIdsByNumberGt(any(), any(), anyInt()))
                .thenReturn(List.of(11L, 12L, 13L));
        when(chunkStructuredFilterMapper.selectChunkIdsByDomain(eq("HR"), anyInt()))
                .thenReturn(List.of(11L, 12L, 13L));

        List<Long> result = structuredFilterService.filterChunkIdsUnbounded(
                "HR", List.of(numberCondition("GT", "10000")));

        verify(chunkStructuredFilterMapper).selectChunkIdsByNumberGt("clearance_level", new BigDecimal("10000"), 2000);
        verify(chunkStructuredFilterMapper).selectChunkIdsByDomain("HR", 2000);
        assertThat(result).containsExactly(11L, 12L, 13L);
    }

    @Test
    @DisplayName("聚合路径超过安全上限时截断并 WARN，不静默")
    void filterChunkIdsUnbounded_shouldTruncateAtSafetyLimit() {
        ReflectionTestUtils.setField(structuredFilterService, "unboundedLimit", 2);
        when(chunkStructuredFilterMapper.selectChunkIdsByDomain(eq("HR"), anyInt()))
                .thenReturn(List.of(1L, 2L, 3L));

        List<Long> result = structuredFilterService.filterChunkIdsUnbounded("HR", List.of());

        assertThat(result).containsExactly(1L, 2L);
        assertThat(warnedAbout("超过安全上限")).isTrue();
    }
}
