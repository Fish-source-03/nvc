package org.example.agent_qr.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.rag.IndexableText;
import org.example.agent_qr.common.rag.IndexableTextProvider;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link IndexableTextProvider} 实现 — 封装 ChunkMapper 查询。
 * <p>
 * 将 MyBatis Mapper 查询结果适配为 {@link IndexableText} 接口，
 * 供 rag 模块的 BM25Retriever 通过接口注入使用，避免循环依赖。
 * 查询时自动过滤：
 * <ul>
 *   <li>已软删除的记录（deleted = 0）</li>
 *   <li>非活跃数据源的切片（仅保留 datasourceId 为 null 或数据源状态为 ACTIVE 的切片）</li>
 * </ul>
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkIndexableTextProvider implements IndexableTextProvider {

    private final ChunkMapper chunkMapper;
    private final DataSourceMapper dataSourceMapper;

    @Override
    public List<IndexableText> findAllIndexable() {
        // 过滤软删除记录：仅加载未删除的切片
        LambdaQueryWrapper<Chunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Chunk::getDeleted, 0);
        List<Chunk> chunks = chunkMapper.selectList(wrapper);

        // 获取所有活跃数据源 ID，用于过滤非活跃数据源的切片
        Set<Long> activeDsIds = activeDatasourceIds();
        log.debug("BM25 索引构建：活跃数据源 ID 集合 size={}", activeDsIds.size());

        // 过滤：datasourceId 为 null（文档上传管线）或 数据源状态为 ACTIVE
        List<IndexableText> filtered = chunks.stream()
                .filter(c -> c.getDatasourceId() == null || activeDsIds.contains(c.getDatasourceId()))
                .collect(Collectors.toCollection(ArrayList::new));

        log.info("BM25 索引构建：全量切片={}, 过滤后={}", chunks.size(), filtered.size());
        return filtered;
    }

    /**
     * 按键集分页获取待索引切片（批次 07 · 任务 7.3.3，BM25 索引分页构建）。
     * <p>
     * 与 {@link #findAllIndexable()} <b>同一过滤口径</b>（软删除 + 活跃数据源），
     * 但过滤条件下推到 SQL 并以 {@code id > afterId ORDER BY id LIMIT n} 逐页读取——
     * 几十万条切片也不会一次性进内存。不用 OFFSET：构建期间数据变化会让 OFFSET 跳行。
     * </p>
     *
     * @param afterId 上一页的最大 ID（首页传 0）
     * @param limit   单页上限
     * @return 按 ID 升序的一页切片（已过滤），无更多数据时为空列表
     */
    @Override
    public List<IndexableText> findIndexablePage(long afterId, int limit) {
        Set<Long> activeDsIds = activeDatasourceIds();

        LambdaQueryWrapper<Chunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Chunk::getDeleted, 0)
                .gt(Chunk::getId, afterId);
        if (activeDsIds.isEmpty()) {
            wrapper.isNull(Chunk::getDatasourceId);
        } else {
            wrapper.and(w -> w.isNull(Chunk::getDatasourceId)
                    .or().in(Chunk::getDatasourceId, activeDsIds));
        }
        wrapper.orderByAsc(Chunk::getId)
                .last("LIMIT " + Math.max(1, limit));

        return new ArrayList<>(chunkMapper.selectList(wrapper));
    }

    /**
     * 查询活跃数据源 ID 集合。
     *
     * @return 活跃数据源 ID 集合
     */
    private Set<Long> activeDatasourceIds() {
        return dataSourceMapper.selectAllActive().stream()
                .map(DataSourceConfig::getId)
                .collect(Collectors.toSet());
    }
}
