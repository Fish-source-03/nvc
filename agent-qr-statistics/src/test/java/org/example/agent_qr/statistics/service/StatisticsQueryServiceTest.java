package org.example.agent_qr.statistics.service;

import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.statistics.dto.DashboardVO;
import org.example.agent_qr.statistics.entity.DailyStats;
import org.example.agent_qr.statistics.mapper.DailyStatsMapper;
import org.example.agent_qr.user.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * {@link StatisticsQueryService} 测试（批次 11 · 任务 11.2.2）。
 * <p>
 * 仪表盘是"零数据也要能打开"的页面：库中尚无当日统计、或历史上从未产生反馈时，
 * 服务必须给出 0 而不是抛异常/返回 null（否则首页直接报错）。
 * 用例锁定两类契约：
 * </p>
 * <ul>
 *   <li><b>空值容错</b>：无当日统计、周趋势为 null、各计数为 null → 一律回落为 0/空列表；</li>
 *   <li><b>满意率口径</b>：{@code positive / (positive + negative)}，分母为 0 时为 0.0
 *       （不得出现 NaN 或除零异常），周趋势逐日同口径。</li>
 * </ul>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatisticsQueryServiceTest {

    @Mock
    private DailyStatsMapper dailyStatsMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private MessageMapper messageMapper;

    private StatisticsQueryService service;

    @BeforeEach
    void setUp() {
        service = new StatisticsQueryService(dailyStatsMapper, documentMapper, chunkMapper,
                sysUserMapper, messageMapper);
    }

    private static DailyStats stats(int positive, int negative) {
        DailyStats stats = new DailyStats();
        stats.setStatDate(LocalDate.now());
        stats.setQaCount(10);
        stats.setPositiveCount(positive);
        stats.setNegativeCount(negative);
        return stats;
    }

    @Test
    @DisplayName("★ 无当日数据与空趋势时回落为 0/空，而库总量照常透出（首页不得因空库报错）")
    void getDashboard_shouldReturnZeros_whenNoData() {
        when(dailyStatsMapper.selectByDate(any(LocalDate.class))).thenReturn(null);
        when(dailyStatsMapper.selectWeeklyTrend(any(LocalDate.class))).thenReturn(null);
        when(sysUserMapper.countByDate(any(LocalDate.class))).thenReturn(null);
        when(documentMapper.selectCount(isNull())).thenReturn(5L);
        when(chunkMapper.selectCount(isNull())).thenReturn(11788L);
        when(sysUserMapper.selectCount(isNull())).thenReturn(12L);
        when(documentMapper.selectTypeDistribution()).thenReturn(List.of());

        DashboardVO vo = service.getDashboard();

        // 当日指标：无数据 → 0（而非 null/异常）
        assertThat(vo.getTodayQA()).isZero();
        assertThat(vo.getTodayNewUsers()).isZero();
        assertThat(vo.getWeeklyTrend()).isEmpty();
        // 库总量：照常透出
        assertThat(vo.getTotalDocuments()).isEqualTo(5L);
        assertThat(vo.getTotalChunks()).isEqualTo(11788L);
        assertThat(vo.getTotalUsers()).isEqualTo(12L);
        // 分布为空时给出空集合（前端仍可渲染）
        assertThat(vo.getDocTypeDistribution()).isEmpty();
        // 反馈指标：无当日统计 → 全 0
        assertThat(vo.getTodayPositive()).isZero();
        assertThat(vo.getTodayNegative()).isZero();
        assertThat(vo.getTotalFeedbackCount()).isZero();
        assertThat(vo.getSatisfactionRate()).isZero();
    }

    @Test
    @DisplayName("★ 满意率 = 正向 /（正向 + 负向）")
    void getDashboard_shouldComputeSatisfactionRate() {
        when(dailyStatsMapper.selectByDate(any(LocalDate.class))).thenReturn(stats(3, 1));
        when(dailyStatsMapper.selectWeeklyTrend(any(LocalDate.class))).thenReturn(new ArrayList<>());
        when(documentMapper.selectTypeDistribution()).thenReturn(List.of());

        DashboardVO vo = service.getDashboard();

        assertThat(vo.getTotalFeedbackCount()).isEqualTo(4);
        assertThat(vo.getSatisfactionRate()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("★ 当日无反馈时满意率为 0.0（不得除零或 NaN）")
    void getDashboard_shouldGuardAgainstZeroFeedback() {
        when(dailyStatsMapper.selectByDate(any(LocalDate.class))).thenReturn(stats(0, 0));
        when(dailyStatsMapper.selectWeeklyTrend(any(LocalDate.class))).thenReturn(new ArrayList<>());
        when(documentMapper.selectTypeDistribution()).thenReturn(List.of());

        DashboardVO vo = service.getDashboard();

        assertThat(vo.getSatisfactionRate()).isZero();
        assertThat(vo.getSatisfactionRate()).isNotNaN();
    }

    @Test
    @DisplayName("周趋势逐日计算满意率（含 null 计数与除零保护）")
    void getDashboard_shouldComputeWeeklyTrendRate() {
        List<DailyStats> trend = new ArrayList<>();
        trend.add(stats(1, 3));  // 0.25
        DailyStats noFeedback = new DailyStats();
        noFeedback.setStatDate(LocalDate.now().minusDays(1));
        noFeedback.setPositiveCount(null);
        noFeedback.setNegativeCount(null);
        trend.add(noFeedback);   // 0.0

        when(dailyStatsMapper.selectByDate(any(LocalDate.class))).thenReturn(null);
        when(dailyStatsMapper.selectWeeklyTrend(any(LocalDate.class))).thenReturn(trend);
        when(documentMapper.selectTypeDistribution()).thenReturn(List.of());

        DashboardVO vo = service.getDashboard();

        assertThat(vo.getWeeklyTrend()).hasSize(2);
        assertThat(vo.getWeeklyTrend().get(0).getSatisfactionRate()).isEqualTo(0.25);
        assertThat(vo.getWeeklyTrend().get(1).getSatisfactionRate()).isZero();
    }

    @Test
    @DisplayName("文档类型分布按 file_type 归集，缺失类型归入『未知』")
    void getDashboard_shouldMapDocTypeDistribution() {
        Map<String, Object> pdfRow = new java.util.LinkedHashMap<>();
        pdfRow.put("file_type", "pdf");
        pdfRow.put("cnt", 3L);
        Map<String, Object> unknownRow = new java.util.LinkedHashMap<>();
        unknownRow.put("file_type", null);
        unknownRow.put("cnt", 1L);

        when(dailyStatsMapper.selectByDate(any(LocalDate.class))).thenReturn(null);
        when(dailyStatsMapper.selectWeeklyTrend(any(LocalDate.class))).thenReturn(new ArrayList<>());
        when(documentMapper.selectTypeDistribution()).thenReturn(List.of(pdfRow, unknownRow));

        DashboardVO vo = service.getDashboard();

        assertThat(vo.getDocTypeDistribution()).containsEntry("pdf", 3L).containsEntry("未知", 1L);
    }

    @Test
    @DisplayName("底层查询异常时转换为业务异常（不把堆栈暴露给前端）")
    void getDashboard_shouldWrapFailureAsBusinessException() {
        when(dailyStatsMapper.selectByDate(any(LocalDate.class)))
                .thenThrow(new IllegalStateException("连接池耗尽"));

        assertThatThrownBy(() -> service.getDashboard())
                .hasMessageContaining("获取仪表盘数据失败");
    }
}
