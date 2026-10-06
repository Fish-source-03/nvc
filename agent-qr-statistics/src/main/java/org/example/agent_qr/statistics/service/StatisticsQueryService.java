package org.example.agent_qr.statistics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.rag.entity.Message;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.statistics.dto.DashboardVO;
import org.example.agent_qr.statistics.entity.DailyStats;
import org.example.agent_qr.statistics.mapper.DailyStatsMapper;
import org.example.agent_qr.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 统计查询服务（P2 扩展：满意度指标）。
 * <p>
 * 按设计 §8.13.3 约定：查询侧方法一律标注 {@code @Transactional(readOnly = true)}，
 * 由 {@code ReadWriteDataSourceAspect} 读取该标志后路由到读库（CQRS）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsQueryService {

    private final DailyStatsMapper dailyStatsMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final SysUserMapper sysUserMapper;
    private final MessageMapper messageMapper;

    @Transactional(readOnly = true)
    public DashboardVO getDashboard() {
        try {
            LocalDate today = LocalDate.now();

            DashboardVO vo = new DashboardVO();

            // 1. 今日问答数
            DailyStats todayStats = dailyStatsMapper.selectByDate(today);
            vo.setTodayQA(todayStats != null ? todayStats.getQaCount() : 0);

            // 2. 今日新增用户数
            Long newUserCount = sysUserMapper.countByDate(today);
            vo.setTodayNewUsers(newUserCount != null ? newUserCount.intValue() : 0);

            // 3. 文档总数
            vo.setTotalDocuments(documentMapper.selectCount(null));

            // 4. 分块总数
            vo.setTotalChunks(chunkMapper.selectCount(null));

            // 5. 用户总数
            vo.setTotalUsers(sysUserMapper.selectCount(null));

            // 6. 最近 7 天趋势
            List<DailyStats> weeklyTrend = dailyStatsMapper.selectWeeklyTrend(today);
            vo.setWeeklyTrend(weeklyTrend != null ? weeklyTrend : Collections.emptyList());

            // 为周趋势中每一天计算满意率
            if (weeklyTrend != null) {
                for (DailyStats ds : weeklyTrend) {
                    int pos = ds.getPositiveCount() != null ? ds.getPositiveCount() : 0;
                    int neg = ds.getNegativeCount() != null ? ds.getNegativeCount() : 0;
                    int total = pos + neg;
                    ds.setSatisfactionRate(total > 0 ? (double) pos / total : 0.0);
                }
            }

            // 7. 文档类型分布
            List<Map<String, Object>> typeDistributionList = documentMapper.selectTypeDistribution();
            Map<String, Long> typeDistribution = Collections.emptyMap();
            if (typeDistributionList != null && !typeDistributionList.isEmpty()) {
                typeDistribution = new java.util.LinkedHashMap<>();
                for (Map<String, Object> row : typeDistributionList) {
                    String type = row.get("file_type") != null ? row.get("file_type").toString() : "未知";
                    Object countObj = row.get("cnt");
                    Long count = 0L;
                    if (countObj instanceof Number) {
                        count = ((Number) countObj).longValue();
                    }
                    typeDistribution.put(type, count);
                }
            }
            vo.setDocTypeDistribution(typeDistribution);

            // P2: 满意度指标
            if (todayStats != null) {
                vo.setTodayPositive(todayStats.getPositiveCount() != null ? todayStats.getPositiveCount() : 0);
                vo.setTodayNegative(todayStats.getNegativeCount() != null ? todayStats.getNegativeCount() : 0);
                int totalFeedback = vo.getTodayPositive() + vo.getTodayNegative();
                vo.setTotalFeedbackCount(totalFeedback);
                vo.setSatisfactionRate(totalFeedback > 0
                        ? (double) vo.getTodayPositive() / totalFeedback
                        : 0.0);
            } else {
                vo.setTodayPositive(0);
                vo.setTodayNegative(0);
                vo.setTotalFeedbackCount(0);
                vo.setSatisfactionRate(0.0);
            }

            log.info("仪表盘数据获取成功: todayQA={}, totalDocuments={}", vo.getTodayQA(), vo.getTotalDocuments());
            return vo;
        } catch (Exception e) {
            log.error("获取仪表盘数据失败", e);
            throw new BusinessException("获取仪表盘数据失败: " + e.getMessage());
        }
    }
}
