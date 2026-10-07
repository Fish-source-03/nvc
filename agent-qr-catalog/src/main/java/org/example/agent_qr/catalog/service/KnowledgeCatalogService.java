package org.example.agent_qr.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.catalog.entity.CatalogTree;
import org.example.agent_qr.catalog.entity.DomainNode;
import org.example.agent_qr.catalog.entity.EntityNode;
import org.example.agent_qr.catalog.entity.SourceNode;
import org.example.agent_qr.common.event.DataETLedEvent;
import org.example.agent_qr.common.event.DataQualityPassedEvent;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识目录服务。
 * <p>
 * 构建三级目录树（域 → 数据源 → 实体），
 * 仅依赖 agent-qr-datasource，不依赖 agent-qr-knowledge（避免循环依赖）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class KnowledgeCatalogService {

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 获取完整的三级目录树。
     * <p>
     * 一级：按 domain 分组的 DomainNode<br>
     * 二级：每个域下的数据源 SourceNode<br>
     * 三级：每个数据源下的实体 EntityNode（基于数据源元数据构建）
     * </p>
     *
     * @return 目录树
     */
    @Transactional(readOnly = true)
    public CatalogTree getCatalogTree() {
        List<DataSourceConfig> activeSources = dataSourceMapper.selectAllActive();

        // 按 domain 分组
        Map<String, List<DataSourceConfig>> domainGroups = activeSources.stream()
                .collect(Collectors.groupingBy(
                        ds -> ds.getDomain() != null ? ds.getDomain() : "COMMON",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<DomainNode> domains = new ArrayList<>();
        for (Map.Entry<String, List<DataSourceConfig>> entry : domainGroups.entrySet()) {
            String domainName = entry.getKey();
            List<DataSourceConfig> sources = entry.getValue();

            List<SourceNode> sourceNodes = new ArrayList<>();
            for (DataSourceConfig ds : sources) {
                List<EntityNode> entities = getEntitiesForSource(ds);
                SourceNode sourceNode = new SourceNode(
                        ds.getId(),
                        ds.getSourceName(),
                        ds.getSourceType(),
                        ds.getLastSyncAt(),
                        ds.getTotalSynced(),
                        entities
                );
                sourceNodes.add(sourceNode);
            }

            // 统计域下所有数据源的实体总数
            int totalEntities = sourceNodes.stream()
                    .mapToInt(s -> s.getEntities() != null ? s.getEntities().size() : 0)
                    .sum();
            DomainNode domainNode = new DomainNode(domainName, sources.size(), totalEntities, sourceNodes);
            domains.add(domainNode);
        }

        log.debug("目录树构建完成: {} 个域", domains.size());
        return new CatalogTree(domains);
    }

    /**
     * 获取知识目录统计概览。
     * <p>
     * 聚合统计域数量、数据源总数和实体总数。
     * </p>
     *
     * @return 统计数据 Map（totalDomains / totalSources / totalEntities）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        CatalogTree tree = getCatalogTree();
        int totalSources = 0;
        int totalEntities = 0;
        for (DomainNode domain : tree.getDomains()) {
            totalSources += domain.getSourceCount();
            totalEntities += domain.getTotalEntities();
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDomains", tree.getDomains().size());
        stats.put("totalSources", totalSources);
        stats.put("totalEntities", totalEntities);
        return stats;
    }

    /**
     * 获取数据源下的实体列表。
     * <p>
     * 基于数据源类型和名称构建实体节点，不再依赖 knowledge 模块。
     * </p>
     */
    private List<EntityNode> getEntitiesForSource(DataSourceConfig ds) {
        List<EntityNode> entities = new ArrayList<>();

        // 根据数据源类型推断实体
        String entityName = ds.getSourceName();
        String entityType = mapSourceTypeToEntityType(ds.getSourceType());

        // 优先使用质量检测通过数；若从未做过质检（totalPassed 为 NULL），回退到 totalSynced 以兼容旧数据
        int recordCount;
        if (ds.getTotalPassed() != null) {
            recordCount = ds.getTotalPassed();
        } else {
            recordCount = ds.getTotalSynced() != null ? ds.getTotalSynced() : 0;
        }
        entities.add(new EntityNode(entityName, entityType, recordCount, ds.getLastSyncAt()));

        return entities;
    }

    /**
     * 将数据源类型映射为实体类型。
     */
    private String mapSourceTypeToEntityType(String sourceType) {
        if (sourceType == null) {
            return EntityNode.TYPE_TABLE;
        }
        return switch (sourceType.toUpperCase()) {
            case "JDBC" -> EntityNode.TYPE_TABLE;
            case "REST" -> EntityNode.TYPE_API;
            case "S3" -> EntityNode.TYPE_FILE;
            default -> EntityNode.TYPE_TABLE;
        };
    }

    /**
     * 监听数据质量通过事件，触发 ETL 处理。
     *
     * @param event 质量通过事件
     */
    @Async("indexBuilderExecutor")
    @EventListener
    public void onDataQualityPassed(DataQualityPassedEvent event) {
        log.info("收到质量通过事件: batchId={}, passedDataCount={}",
                event.getSyncBatchId(),
                event.getPassedData() != null ? event.getPassedData().size() : 0);

        try {
            // 质量通过后，发布 ETL 完成事件 → 触发目录索引更新
            // 目录索引为惰性计算（下次调用 getCatalogTree 时重建），
            // 这里仅记录日志并发布下游事件
            int entityCount = event.getPassedData() != null ? event.getPassedData().size() : 0;
            eventPublisher.publishEvent(new DataETLedEvent(
                    null,   // domain 由下游自行推断
                    null,   // sourceName 由下游自行推断
                    entityCount,
                    event.getSyncBatchId()
            ));
            log.info("ETL 完成事件已发布: batchId={}, entityCount={}",
                    event.getSyncBatchId(), entityCount);
        } catch (Exception e) {
            log.error("质量通过事件处理失败: batchId={}, error={}",
                    event.getSyncBatchId(), e.getMessage(), e);
        }
    }

    /**
     * 监听 ETL 完成事件，更新目录索引。
     *
     * @param event ETL 完成事件
     */
    @Async("indexBuilderExecutor")
    @EventListener
    public void onDataETLed(DataETLedEvent event) {
        log.info("收到 ETL 完成事件，更新目录索引: domain={}, sourceName={}, entityCount={}",
                event.getDomain(), event.getSourceName(), event.getEntityCount());
        // 目录索引更新为惰性计算（下次调用 getCatalogTree 时重建），
        // 这里仅记录日志，实际索引在 getCatalogTree() 中实时构建。
    }
}
