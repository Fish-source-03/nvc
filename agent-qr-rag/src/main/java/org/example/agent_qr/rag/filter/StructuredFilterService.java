package org.example.agent_qr.rag.filter;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.filter.mapper.ChunkStructuredFilterMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 结构化过滤服务 — MySQL B+ 树前置过滤。
 * <p>
 * 在向量检索前，先通过结构化字段条件在 MySQL 中过滤候选切片 ID，
 * 将结果集截断至 500 条，大幅减少后续向量检索的计算量。
 * 域过滤覆盖两条管线：数据同步管线（kb_chunk_structured）和
 * 文档上传管线（kb_chunk JOIN kb_document）。
 * </p>
 * <p>
 * <b>批次 04 · 任务 4.1（问题 20）</b>：{@code operator} 参与分派——
 * NUMBER / DATE 条件按 GT / GTE / LT / LTE 生成真正的开闭区间比较，
 * 无 operator（或 BETWEEN）时维持历史区间语义；非法 operator 记录 WARN 后按区间语义处理，
 * 不再「静默退化」。
 * </p>
 * <p>
 * <b>批次 04 · 任务 4.4（问题 13）</b>：新增 {@link #filterChunkIdsUnbounded} 供聚合查询路径使用，
 * 去掉 500 条硬截断（安全上限由 {@code agent-qr.aggregation.max-chunk-ids} 控制，默认 2000）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class StructuredFilterService {

    /** 语义检索路径候选集上限（沿用历史语义：SQL LIMIT 500 + subList(0, 500)） */
    public static final int CANDIDATE_LIMIT = 500;

    @Autowired
    private ChunkStructuredFilterMapper chunkStructuredFilterMapper;

    /**
     * 聚合查询路径安全上限（批次 04 · 任务 4.4）。
     * <p>避免无条件/超大条件组合导致全表扫描与内存压力；方案文档建议 2000。</p>
     */
    @Value("${agent-qr.aggregation.max-chunk-ids:2000}")
    private int unboundedLimit;

    /**
     * 根据域和过滤条件获取候选切片 ID 列表（语义检索路径，上限 500）。
     * <p>
     * 域过滤覆盖两条管线：
     * <ul>
     *   <li>数据同步管线：kb_chunk_structured.domain</li>
     *   <li>文档上传管线：kb_chunk JOIN kb_document.domain</li>
     * </ul>
     * 多条件取交集（AND），结果集上限 500 条。
     * </p>
     *
     * @param domain     业务域（可为 null，表示不限域）
     * @param conditions 过滤条件列表
     * @return 候选切片 ID 列表
     */
    public List<Long> filterChunkIds(String domain, List<FilterCondition> conditions) {
        return doFilterChunkIds(domain, conditions, CANDIDATE_LIMIT, false);
    }

    /**
     * 无界结构化过滤（批次 04 · 任务 4.4，聚合查询路径专用）。
     * <p>
     * 与 {@link #filterChunkIds} 的唯一差别是结果集上限：去掉 500 条硬截断，
     * 改用安全上限 {@code agent-qr.aggregation.max-chunk-ids}（默认 2000），
     * 使「列出全部匹配记录」类查询不再被 TOP-K 之外的层裁剪。
     * </p>
     * <p>
     * 语义与 {@link #filterChunkIds} 完全一致：条件之间取交集（AND），
     * 域内无匹配数据时返回空列表（<b>不回退全库</b>，与任务 4.2 的空集语义一致）。
     * </p>
     *
     * @param domain     业务域（聚合路径调用方保证非空）
     * @param conditions 过滤条件列表（聚合路径调用方保证非空）
     * @return 候选切片 ID 列表（最多 {@code max-chunk-ids} 条）
     */
    public List<Long> filterChunkIdsUnbounded(String domain, List<FilterCondition> conditions) {
        return doFilterChunkIds(domain, conditions, unboundedLimit, true);
    }

    /**
     * 结构化过滤主流程。
     *
     * @param limit       下推到 SQL 的行数上限
     * @param unbounded   是否为聚合路径（仅影响日志措辞与超限告警）
     */
    private List<Long> doFilterChunkIds(String domain, List<FilterCondition> conditions,
                                        int limit, boolean unbounded) {
        boolean hasDomain = domain != null && !domain.isBlank();

        if (conditions == null || conditions.isEmpty()) {
            if (hasDomain) {
                Set<Long> domainIds = queryDomainChunkIds(domain, limit);
                List<Long> result = sortedAndTruncated(domainIds, limit, domain, unbounded);
                log.debug("域过滤(无结构化条件): domain={}, unbounded={}, resultSize={}",
                        domain, unbounded, result.size());
                return result;
            }
            return List.of();
        }

        Set<Long> resultSet = null;

        for (FilterCondition condition : conditions) {
            List<Long> ids = dispatchCondition(condition, limit);
            if (resultSet == null) {
                resultSet = new HashSet<>(ids);
            } else {
                resultSet.retainAll(ids); // 多条件交集（AND）
            }
            // 不再提前 break — 每个条件都必须参与交集计算，确保 AND 语义完整
        }

        if (resultSet == null || resultSet.isEmpty()) {
            return List.of();
        }

        // 域过滤：覆盖两条管线
        if (hasDomain) {
            resultSet.retainAll(queryDomainChunkIds(domain, limit));
        }

        List<Long> result = sortedAndTruncated(resultSet, limit, domain, unbounded);
        log.debug("结构化过滤: domain={}, unbounded={}, conditions={}, resultSize={}",
                domain, unbounded, conditions.size(), result.size());
        return result;
    }

    /** 查询域内全部候选切片 ID（两条管线并集）。 */
    private Set<Long> queryDomainChunkIds(String domain, int limit) {
        Set<Long> domainIds = new HashSet<>();
        // 数据同步管线
        domainIds.addAll(chunkStructuredFilterMapper.selectChunkIdsByDomain(domain, limit));
        // 文档上传管线
        domainIds.addAll(chunkStructuredFilterMapper.selectChunkIdsByDocumentDomain(domain, limit));
        return domainIds;
    }

    /** 排序 + 截断（截断时对聚合路径给出 WARN，避免安全上限静默生效）。 */
    private List<Long> sortedAndTruncated(Set<Long> ids, int limit, String domain, boolean unbounded) {
        List<Long> result = new ArrayList<>(ids);
        result.sort(Comparator.naturalOrder());
        if (result.size() > limit) {
            if (unbounded) {
                log.warn("聚合路径结果超过安全上限，已截断: size={}, limit={}, domain={}",
                        result.size(), limit, domain);
            }
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    /**
     * 根据字段类型与操作符分派到对应的 Mapper 方法。
     * <p>
     * ★ 任务 4.1：{@code condition.getOperator()} 在此被读取并决定比较语义。
     * </p>
     */
    private List<Long> dispatchCondition(FilterCondition condition, int limit) {
        String fieldType = condition.getFieldType() == null ? "" : condition.getFieldType();
        return switch (fieldType) {
            case "NUMBER" -> dispatchNumber(condition, limit);
            case "DATE" -> dispatchDate(condition, limit);
            case "ENUM", "STRING" -> chunkStructuredFilterMapper.selectChunkIdsByStringValue(
                    condition.getFieldName(), condition.getValue(), limit);
            default -> List.<Long>of();
        };
    }

    /**
     * 数值条件分派（任务 4.1）。
     * <p>
     * | operator | 语义 |
     * |---|---|
     * | GT | (value, +∞) — SQL {@code >} |
     * | GTE | [value, +∞) — SQL {@code >=} |
     * | LT | (-∞, value) — SQL {@code <} |
     * | LTE | (-∞, value] — SQL {@code <=} |
     * | EQ | [value, value] — SQL {@code >= AND <=} |
     * | 无 operator / BETWEEN | [minValue, maxValue]（无 min/max 时回退 value，维持历史语义） |
     * </p>
     */
    private List<Long> dispatchNumber(FilterCondition condition, int limit) {
        String operator = normalizeOperator(condition.getOperator());
        if (operator == null) {
            // 无 operator（区间语义）或非法 operator（已记录 WARN）
            return chunkStructuredFilterMapper.selectChunkIdsByNumberRange(
                    condition.getFieldName(), parseMinNumber(condition), parseMaxNumber(condition), limit);
        }
        BigDecimal value = parseSingleNumber(condition);
        if (value == null) {
            // 单值无法解析：记录 WARN 后按区间语义处理，不静默失败
            log.warn("数值过滤条件缺少可解析的单值，按区间语义处理: field={}, operator={}, condition={}",
                    condition.getFieldName(), condition.getOperator(), condition);
            return chunkStructuredFilterMapper.selectChunkIdsByNumberRange(
                    condition.getFieldName(), parseMinNumber(condition), parseMaxNumber(condition), limit);
        }
        return switch (operator) {
            case FilterCondition.OP_GT -> chunkStructuredFilterMapper.selectChunkIdsByNumberGt(
                    condition.getFieldName(), value, limit);
            case FilterCondition.OP_GTE -> chunkStructuredFilterMapper.selectChunkIdsByNumberGte(
                    condition.getFieldName(), value, limit);
            case FilterCondition.OP_LT -> chunkStructuredFilterMapper.selectChunkIdsByNumberLt(
                    condition.getFieldName(), value, limit);
            case FilterCondition.OP_LTE -> chunkStructuredFilterMapper.selectChunkIdsByNumberLte(
                    condition.getFieldName(), value, limit);
            default -> chunkStructuredFilterMapper.selectChunkIdsByNumberRange(
                    condition.getFieldName(), value, value, limit); // EQ
        };
    }

    /**
     * 日期条件分派（任务 4.1，与数值分支对称，避免日期操作符重蹈「静默退化为等值」的覆辙）。
     */
    private List<Long> dispatchDate(FilterCondition condition, int limit) {
        String operator = normalizeOperator(condition.getOperator());
        if (operator == null) {
            return chunkStructuredFilterMapper.selectChunkIdsByDateRange(
                    condition.getFieldName(), parseStartDate(condition), parseEndDate(condition), limit);
        }
        LocalDate value = parseSingleDate(condition);
        if (value == null) {
            log.warn("日期过滤条件缺少可解析的单值，按区间语义处理: field={}, operator={}, condition={}",
                    condition.getFieldName(), condition.getOperator(), condition);
            return chunkStructuredFilterMapper.selectChunkIdsByDateRange(
                    condition.getFieldName(), parseStartDate(condition), parseEndDate(condition), limit);
        }
        return switch (operator) {
            case FilterCondition.OP_GT -> chunkStructuredFilterMapper.selectChunkIdsByDateGt(
                    condition.getFieldName(), value, limit);
            case FilterCondition.OP_GTE -> chunkStructuredFilterMapper.selectChunkIdsByDateGte(
                    condition.getFieldName(), value, limit);
            case FilterCondition.OP_LT -> chunkStructuredFilterMapper.selectChunkIdsByDateLt(
                    condition.getFieldName(), value, limit);
            case FilterCondition.OP_LTE -> chunkStructuredFilterMapper.selectChunkIdsByDateLte(
                    condition.getFieldName(), value, limit);
            default -> chunkStructuredFilterMapper.selectChunkIdsByDateRange(
                    condition.getFieldName(), value, value, limit); // EQ
        };
    }

    /**
     * 归一化操作符。
     * <p>
     * 返回 {@code null} 表示「按区间语义处理」，有两种来源：
     * <ul>
     *   <li>无 operator / BETWEEN —— 设计语义，保持原有区间行为；</li>
     *   <li>非法 operator（任务 4.1.2）—— 记录 WARN 后按区间语义处理，<b>不静默</b>。</li>
     * </ul>
     * </p>
     *
     * @param operator 原始操作符（可为 null / 空白 / 大小写混写）
     * @return 归一化后的操作符常量，或 {@code null}（区间语义）
     */
    private String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return null;
        }
        String normalized = operator.trim().toUpperCase(Locale.ROOT);
        if (FilterCondition.OP_BETWEEN.equals(normalized)) {
            return null;
        }
        return switch (normalized) {
            case FilterCondition.OP_GT, FilterCondition.OP_GTE,
                 FilterCondition.OP_LT, FilterCondition.OP_LTE,
                 FilterCondition.OP_EQ -> normalized;
            default -> {
                log.warn("非法的过滤操作符，按区间语义处理（不静默退化为等值）: operator={}", operator);
                yield null;
            }
        };
    }

    /**
     * 解析单值操作符（GT/GTE/LT/LTE/EQ）对应的比较值。
     * 优先取 {@code value}，其次 {@code minValue}，最后 {@code maxValue}。
     *
     * @return 解析结果；无法解析时返回 {@code null}
     */
    private BigDecimal parseSingleNumber(FilterCondition c) {
        String raw = firstNonBlank(c.getValue(), c.getMinValue(), c.getMaxValue());
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception e) {
            log.warn("数值条件解析失败: field={}, raw={}", c.getFieldName(), raw);
            return null;
        }
    }

    /** 解析日期单值（GT/GTE/LT/LTE/EQ）。 */
    private LocalDate parseSingleDate(FilterCondition c) {
        String raw = firstNonBlank(c.getValue(), c.getMinValue(), c.getMaxValue());
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            log.warn("日期条件解析失败: field={}, raw={}", c.getFieldName(), raw);
            return null;
        }
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private BigDecimal parseMinNumber(FilterCondition c) {
        try {
            return new BigDecimal(c.getMinValue() != null ? c.getMinValue() : c.getValue());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal parseMaxNumber(FilterCondition c) {
        try {
            return new BigDecimal(c.getMaxValue() != null ? c.getMaxValue() : c.getValue());
        } catch (Exception e) {
            return new BigDecimal("999999999");
        }
    }

    private LocalDate parseStartDate(FilterCondition c) {
        try {
            String date = c.getMinValue() != null ? c.getMinValue() : c.getValue();
            return LocalDate.parse(date);
        } catch (Exception e) {
            return LocalDate.of(2000, 1, 1);
        }
    }

    private LocalDate parseEndDate(FilterCondition c) {
        try {
            String date = c.getMaxValue() != null ? c.getMaxValue() : c.getValue();
            return LocalDate.parse(date);
        } catch (Exception e) {
            return LocalDate.of(2099, 12, 31);
        }
    }
}
