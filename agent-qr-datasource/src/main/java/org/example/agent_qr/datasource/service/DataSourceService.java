package org.example.agent_qr.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.common.event.DataSourceDeletedEvent;
import org.example.agent_qr.common.event.DataSyncCompletedEvent;
import org.example.agent_qr.datasource.connector.DataSourceConnector;
import org.example.agent_qr.datasource.dto.ConnectionTestResult;
import org.example.agent_qr.datasource.dto.SyncContext;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.entity.SyncRecord;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.datasource.mapper.SyncRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源管理服务。
 * <p>
 * 提供数据源配置的 CRUD 操作、连通性测试和同步触发。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class DataSourceService {

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private SyncRecordMapper syncRecordMapper;

    @Autowired
    private Map<String, DataSourceConnector> connectorMap;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // ==================== CRUD ====================

    /**
     * 创建数据源配置。
     */
    public DataSourceConfig create(DataSourceConfig config) {
        dataSourceMapper.insert(config);
        log.info("数据源配置已创建: id={}, sourceName={}, sourceType={}",
                config.getId(), config.getSourceName(), config.getSourceType());
        return config;
    }

    /**
     * 根据 ID 查询数据源配置。
     */
    @Transactional(readOnly = true)
    public DataSourceConfig getById(Long id) {
        DataSourceConfig config = dataSourceMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("数据源配置不存在: id=" + id);
        }
        return config;
    }

    /**
     * 查询所有数据源配置。
     */
    @Transactional(readOnly = true)
    public List<DataSourceConfig> listAll() {
        return dataSourceMapper.selectList(new LambdaQueryWrapper<>());
    }

    /**
     * 查询所有活跃的数据源配置。
     */
    @Transactional(readOnly = true)
    public List<DataSourceConfig> listActive() {
        return dataSourceMapper.selectAllActive();
    }

    /**
     * 更新数据源配置。
     */
    public DataSourceConfig update(DataSourceConfig config) {
        dataSourceMapper.updateById(config);
        log.info("数据源配置已更新: id={}", config.getId());
        return config;
    }

    /**
     * 删除数据源配置，并发布事件触发级联清理。
     * <p>
     * 级联清理（由 knowledge 模块监听 {@code DataSourceDeletedEvent} 异步执行）：
     * <ol>
     *   <li>软删除关联切片（kb_chunk.deleted = 1）</li>
     *   <li>移除 ChromaDB 向量</li>
     *   <li>移除 BM25 索引</li>
     *   <li>清理 kb_chunk_structured 元数据</li>
     * </ol>
     * </p>
     */
    public void delete(Long id) {
        // 先发布事件，再物理删除（listener 通过 datasourceId 查询关联切片）
        eventPublisher.publishEvent(new DataSourceDeletedEvent(id));
        dataSourceMapper.deleteById(id);
        log.info("数据源配置已删除: id={}", id);
    }

    // ==================== 连通性测试 ====================

    /**
     * 测试数据源连通性。
     *
     * @param id 数据源配置 ID
     * @return 连通性测试结果
     */
    @SuppressWarnings("unchecked")
    public ConnectionTestResult testConnection(Long id) {
        DataSourceConfig config = getById(id);
        DataSourceConnector connector = getConnector(config.getSourceType());

        // 解析连接配置 JSON
        Map<String, Object> connConfig;
        try {
            connConfig = parseJson(config.getConnectionConfig());
        } catch (Exception e) {
            return ConnectionTestResult.fail("连接配置 JSON 解析失败: " + e.getMessage());
        }

        return connector.testConnection(connConfig);
    }

    // ==================== 同步触发 ====================

    /**
     * 触发数据源同步。
     *
     * @param id 数据源配置 ID
     * @return 同步结果
     */
    @SuppressWarnings("unchecked")
    public SyncResult triggerSync(Long id) {
        DataSourceConfig config = getById(id);
        DataSourceConnector connector = getConnector(config.getSourceType());

        Map<String, Object> connConfig;
        try {
            connConfig = parseJson(config.getConnectionConfig());
        } catch (Exception e) {
            // 记录同步失败历史
            SyncRecord failRecord = new SyncRecord();
            failRecord.setDatasourceId(config.getId());
            failRecord.setSyncStrategy(config.getSyncStrategy());
            failRecord.setTotalRows(0);
            failRecord.setNextCursor(null);
            failRecord.setStatus(SyncRecord.STATUS_FAILED);
            failRecord.setErrorMsg("连接配置 JSON 解析失败: " + e.getMessage());
            failRecord.setSyncTime(LocalDateTime.now());
            failRecord.setCreateTime(LocalDateTime.now());
            syncRecordMapper.insert(failRecord);
            throw new BusinessException("连接配置 JSON 解析失败: " + e.getMessage());
        }

        // 将 entity 级别的 cursorField/lastCursor 注入 connConfig，供 Connector 使用
        if (config.getCursorField() != null && !config.getCursorField().isBlank()) {
            connConfig.put("cursorField", config.getCursorField());
        }
        if (config.getLastCursor() != null && !config.getLastCursor().isBlank()) {
            connConfig.put("lastCursor", config.getLastCursor());
        }

        SyncContext context = new SyncContext(config.getId(), connConfig);

        try {
            SyncResult result;
            if (DataSourceConfig.SYNC_INCREMENTAL.equals(config.getSyncStrategy())
                    && config.getLastCursor() != null
                    && !config.getLastCursor().isBlank()) {
                result = connector.incrementalSync(context, config.getLastCursor());
            } else {
                result = connector.fullSync(context);
            }

            // 更新同步结果
            dataSourceMapper.updateSyncResult(config.getId(),
                    result.getNextCursor(), result.getTotalRows(), LocalDateTime.now());
            dataSourceMapper.updateStatus(config.getId(), DataSourceConfig.STATUS_ACTIVE);

            // 记录同步成功历史
            SyncRecord successRecord = new SyncRecord();
            successRecord.setDatasourceId(config.getId());
            successRecord.setSyncStrategy(config.getSyncStrategy());
            successRecord.setTotalRows(result.getTotalRows());
            successRecord.setNextCursor(result.getNextCursor());
            successRecord.setStatus(SyncRecord.STATUS_SUCCESS);
            successRecord.setErrorMsg(null);
            successRecord.setSyncTime(LocalDateTime.now());
            successRecord.setCreateTime(LocalDateTime.now());
            syncRecordMapper.insert(successRecord);

            log.info("数据源同步完成: id={}, sourceName={}, totalRows={}, nextCursor={}",
                    id, config.getSourceName(), result.getTotalRows(), result.getNextCursor());

            // 发布同步完成事件 → 触发数据质量检查
            eventPublisher.publishEvent(new DataSyncCompletedEvent(
                    config.getId(), config.getSourceName(),
                    result.getRawData(), context.getSyncBatchId()));
            log.info("数据同步完成事件已发布: datasourceId={}, batchId={}, rows={}",
                    config.getId(), context.getSyncBatchId(), result.getTotalRows());

            return result;
        } catch (Exception e) {
            log.error("数据源同步执行失败: id={}, sourceName={}", id, config.getSourceName(), e);
            // 记录同步失败历史
            SyncRecord failRecord = new SyncRecord();
            failRecord.setDatasourceId(config.getId());
            failRecord.setSyncStrategy(config.getSyncStrategy());
            failRecord.setTotalRows(0);
            failRecord.setNextCursor(null);
            failRecord.setStatus(SyncRecord.STATUS_FAILED);
            failRecord.setErrorMsg(e.getMessage());
            failRecord.setSyncTime(LocalDateTime.now());
            failRecord.setCreateTime(LocalDateTime.now());
            syncRecordMapper.insert(failRecord);
            throw e;
        }
    }

    // ==================== 字段检测 ====================

    /**
     * 检测指定表的字段（列名）列表。
     * 直接接收连接配置 JSON 和表名，不需要已保存的数据源 ID。
     *
     * @param connectionConfigJson 连接配置 JSON 字符串
     * @param tableName            表名
     * @return 字段名列表
     */
    @SuppressWarnings("unchecked")
    public List<String> detectColumns(String connectionConfigJson, String tableName) {
        Map<String, Object> config = parseJson(connectionConfigJson);
        DataSourceConnector connector = getConnector("JDBC");
        return connector.detectColumns(config, tableName);
    }

    // ==================== 分页查询 ====================

    /**
     * 分页查询数据源配置列表，支持按 domain 筛选。
     *
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     * @param domain 业务域筛选（可选，为空则不筛选）
     * @return 包含 total、page、size、records 的分页结果
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listByPage(int page, int size, String domain) {
        IPage<DataSourceConfig> ipage = new Page<>(page, size);
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<>();
        if (domain != null && !domain.isBlank()) {
            wrapper.eq(DataSourceConfig::getDomain, domain);
        }
        wrapper.orderByDesc(DataSourceConfig::getCreateTime);
        IPage<DataSourceConfig> result = dataSourceMapper.selectPage(ipage, wrapper);

        Map<String, Object> pageResult = new HashMap<>();
        pageResult.put("total", result.getTotal());
        pageResult.put("page", page);
        pageResult.put("size", size);
        pageResult.put("records", result.getRecords());
        return pageResult;
    }

    /**
     * 查询数据源同步历史，按同步时间倒序分页。
     *
     * @param datasourceId 数据源 ID
     * @param page         页码（从 1 开始）
     * @param size         每页条数
     * @return 包含 total、page、size、records 的分页结果
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSyncHistory(Long datasourceId, int page, int size) {
        // 确认数据源存在
        getById(datasourceId);

        int offset = (page - 1) * size;
        List<SyncRecord> records = syncRecordMapper.selectByDatasourceIdPaged(datasourceId, offset, size);
        long total = syncRecordMapper.countByDatasourceId(datasourceId);

        Map<String, Object> pageResult = new HashMap<>();
        pageResult.put("total", total);
        pageResult.put("page", page);
        pageResult.put("size", size);
        pageResult.put("records", records);
        return pageResult;
    }

    /**
     * 根据类型获取对应的连接器。
     */
    private DataSourceConnector getConnector(String sourceType) {
        DataSourceConnector connector = connectorMap.get(sourceType);
        if (connector == null) {
            // 尝试通过类型名查找（Spring Bean 命名约定）
            String beanName = sourceType.substring(0, 1).toLowerCase()
                    + sourceType.substring(1) + "Connector";
            connector = connectorMap.values().stream()
                    .filter(c -> c.getType().equalsIgnoreCase(sourceType))
                    .findFirst()
                    .orElse(null);
        }
        if (connector == null) {
            throw new BusinessException("不支持的数据源类型: " + sourceType);
        }
        return connector;
    }

    /**
     * 简单 JSON 解析（将 JSON 字符串转为 Map）。
     * 实际项目中建议使用 Jackson ObjectMapper。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        // 使用 Spring 自带的 Jackson 或简单处理
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException("JSON 解析失败: " + e.getMessage());
        }
    }
}
