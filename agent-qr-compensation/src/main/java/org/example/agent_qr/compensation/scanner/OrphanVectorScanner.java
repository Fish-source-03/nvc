package org.example.agent_qr.compensation.scanner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.rag.mapper.ChunkStructuredMapper;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 孤儿向量扫描器 — 最终兜底。
 * <p>
 * 每 <b>30 分钟</b>（设计 §10.1 决策 6：{@code fixedDelay = 1800000}）扫描并清理两类孤儿数据：
 * <ol>
 *   <li><b>Phase 1 · 孤儿向量</b>（ChromaDB → 对照 MySQL，设计 §10.2）：
 *       枚举 ChromaDB 中的全部向量，凡其切片在 MySQL 中已<b>软删或物理删除</b>者，
 *       即为孤儿向量并清理；</li>
 *   <li><b>Phase 2 · 数据源级残留</b>（MySQL 侧）：已删除/非活跃数据源关联的切片、
 *       BM25 索引与结构化元数据。</li>
 * </ol>
 * </p>
 *
 * <h3>批次 08 · 任务 8.3 修复说明（问题 29）</h3>
 * <p>
 * 原实现的扫描方向<b>恰好漏掉了它唯一的应用场景</b>：它以
 * {@code selectAllReadyChunks()}（SQL 带 {@code status = 'READY' AND deleted = 0}）
 * 为输入，而文档删除流程<b>已先把切片软删</b>——于是"MySQL 删成功 + Chroma 删失败"
 * 这一失败态下的切片永远进不了扫描集合，残留向量永远发现不了。
 * 现改为设计要求的 <b>ChromaDB → MySQL</b> 方向（任务 8.3.1），复用批次 07 ·
 * 任务 7.0.11 已落地的枚举能力（{@link ChromaRetriever#enumerateAllVectors()}），
 * 判定"Chroma 有而 MySQL 没有"的向量。
 * </p>
 * <p>
 * <b>为什么不再复用 {@code selectByDocumentId}</b>：任务 8.1 刚给它加上
 * {@code deleted = 0}（软删切片不得漏出），而孤儿扫描恰恰需要看到"已软删"的切片——
 * 因此新增专用查询 {@link ChunkMapper#selectLiveChunkIds(java.util.List)}
 * 做差集判定，<b>不修改</b> {@code selectByDocumentId} / {@code selectAllReadyChunks}。
 * </p>
 * <p>
 * <b>计数语义</b>（任务 8.3.3）：{@code cleaned} 只在
 * {@link ChromaRetriever#deleteByIds(List)} 返回实际条数时累加，
 * 失败批次不计入；发现数与实际清理数不一致时输出 WARN，
 * 不再出现"日志显示清理 N 条、实际一条没删"的虚高。
 * </p>
 * <p>
 * <b>安全性（不误删）</b>：删除集合的<b>唯一来源</b>是 ChromaDB 枚举结果，
 * 且必须同时满足"元数据可判定归属 + MySQL 中确已不存在"。ChromaDB 不可达时
 * 枚举返回空列表 → 本轮不产生任何删除；MySQL 查询失败时异常上抛 → 同样不删除。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class OrphanVectorScanner {

    /**
     * 扫描周期（毫秒）：30 分钟（设计 §10.1 决策 6）。
     * <p>原值为 300000（5 分钟），与设计不符，任务 8.3.4 按设计对齐。</p>
     */
    static final long SCAN_INTERVAL_MS = 1_800_000L;

    /** 批量查询/删除的批大小 */
    static final int BATCH_SIZE = 500;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired(required = false)
    private ChromaRetriever chromaRetriever;

    @Autowired(required = false)
    private BM25Retriever bm25Retriever;

    @Autowired(required = false)
    private ChunkStructuredMapper chunkStructuredMapper;

    /** 最近一轮发现的孤儿向量数（可观测指标） */
    private volatile int lastDiscoveredOrphans;

    /** 最近一轮实际清理的孤儿向量数（可观测指标；任务 8.3.3） */
    private volatile int lastCleanedOrphans;

    /**
     * 每 30 分钟扫描并清理孤儿向量（文档级 + 数据源级）。
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    public void scanAndCleanOrphanVectors() {
        log.info("开始孤儿向量扫描...");

        if (chromaRetriever == null) {
            log.warn("ChromaRetriever 未初始化，跳过孤儿向量扫描");
            return;
        }

        try {
            // ========== Phase 1: 孤儿向量清理（ChromaDB → MySQL，设计 §10.2） ==========
            cleanOrphanVectors();

            // ========== Phase 2: 数据源级残留清理（MySQL 侧） ==========
            cleanDatasourceOrphans();

            log.info("孤儿向量扫描完成: 发现孤儿向量={}, 实际清理={}",
                    lastDiscoveredOrphans, lastCleanedOrphans);
        } catch (Exception e) {
            log.error("孤儿向量扫描异常", e);
        }
    }

    /** 最近一轮发现的孤儿向量数（测试与监控用） */
    int lastDiscoveredOrphans() {
        return lastDiscoveredOrphans;
    }

    /** 最近一轮实际清理的孤儿向量数（测试与监控用） */
    int lastCleanedOrphans() {
        return lastCleanedOrphans;
    }

    /**
     * Phase 1: 以 ChromaDB 为驱动扫描孤儿向量（任务 8.3.1）。
     * <p>
     * 判定规则（按元数据归属依次判定）：
     * <ol>
     *   <li>有 {@code chunk_id} → 该切片若不在 {@code kb_chunk} 的<b>存活集合</b>中
     *       （已软删或已物理删除）即为孤儿；<b>这是覆盖
     *       "MySQL 删成功 + Chroma 删失败"的关键路径</b>；</li>
     *   <li>无 {@code chunk_id} 但有 {@code document_id} → 文档已不存在/已软删即为孤儿；</li>
     *   <li>无 {@code chunk_id} 但有 {@code datasource_id} → 数据源已不存在/非活跃即为孤儿；</li>
     *   <li>三项元数据全无 → <b>无法判定，保留不删</b>（宁可不清理也不误删）。</li>
     * </ol>
     * </p>
     */
    private void cleanOrphanVectors() {
        List<ChromaRetriever.ChromaVectorRecord> vectors = chromaRetriever.enumerateAllVectors();
        if (vectors.isEmpty()) {
            lastDiscoveredOrphans = 0;
            lastCleanedOrphans = 0;
            log.info("ChromaDB 未枚举到向量记录（或服务不可达），本轮无孤儿向量");
            return;
        }

        // MySQL 侧存活集合：一次查询覆盖全部 chunkId（分批 IN），失败会抛异常 → 本轮不删除
        Set<Long> liveChunkIds = loadLiveChunkIds(vectors.stream()
                .map(ChromaRetriever.ChromaVectorRecord::chunkId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        Map<Long, Boolean> documentAliveCache = new HashMap<>();
        Set<Long> activeDatasourceIds = null;
        List<String> orphanVectorIds = new ArrayList<>();
        int unjudgeable = 0;

        for (ChromaRetriever.ChromaVectorRecord record : vectors) {
            if (record.chunkId() != null) {
                if (!liveChunkIds.contains(record.chunkId())) {
                    orphanVectorIds.add(record.vectorId());
                }
            } else if (record.documentId() != null) {
                if (!isDocumentAlive(record.documentId(), documentAliveCache)) {
                    orphanVectorIds.add(record.vectorId());
                }
            } else if (record.datasourceId() != null) {
                if (activeDatasourceIds == null) {
                    activeDatasourceIds = loadActiveDatasourceIds();
                }
                if (!activeDatasourceIds.contains(record.datasourceId())) {
                    orphanVectorIds.add(record.vectorId());
                }
            } else {
                unjudgeable++;
            }
        }

        lastDiscoveredOrphans = orphanVectorIds.size();
        if (unjudgeable > 0) {
            log.warn("有 {} 条向量缺少 chunk_id/document_id/datasource_id 元数据，无法判定归属，已保留不删",
                    unjudgeable);
        }
        if (orphanVectorIds.isEmpty()) {
            lastCleanedOrphans = 0;
            log.info("未发现孤儿向量（ChromaDB 向量数={}）", vectors.size());
            return;
        }

        int cleaned = removeOrphanVectors(orphanVectorIds);
        lastCleanedOrphans = cleaned;
        if (cleaned < orphanVectorIds.size()) {
            log.warn("孤儿向量清理未完全成功: 发现={}, 实际清理={}，差额将在下一轮扫描重试",
                    orphanVectorIds.size(), cleaned);
        } else {
            log.info("孤儿向量清理完成: 发现={}, 实际清理={}", orphanVectorIds.size(), cleaned);
        }
    }

    /**
     * 批量删除孤儿向量（分批调用，据实计数）。
     *
     * @param orphanVectorIds 孤儿向量 id 列表（来自 ChromaDB 枚举）
     * @return 实际清理条数（失败批次不计入）
     */
    private int removeOrphanVectors(List<String> orphanVectorIds) {
        int cleaned = 0;
        for (int i = 0; i < orphanVectorIds.size(); i += BATCH_SIZE) {
            List<String> batch = orphanVectorIds.subList(i, Math.min(i + BATCH_SIZE, orphanVectorIds.size()));
            try {
                cleaned += chromaRetriever.deleteByIds(batch);
            } catch (Exception e) {
                log.warn("孤儿向量批量清理失败: batchSize={}, error={}", batch.size(), e.getMessage());
            }
        }
        return cleaned;
    }

    /**
     * 查询给定切片 ID 中仍然存活（未软删）的子集（分批 IN）。
     * <p>查询失败时异常上抛 —— 宁可本轮不清理，也不能因查询失败而误删全部向量。</p>
     *
     * @param chunkIds ChromaDB 侧出现的切片 ID 集合
     * @return MySQL 中存活（{@code deleted = 0}）的切片 ID
     */
    private Set<Long> loadLiveChunkIds(Collection<Long> chunkIds) {
        Set<Long> live = new HashSet<>();
        if (chunkIds == null || chunkIds.isEmpty()) {
            return live;
        }
        List<Long> all = new ArrayList<>(chunkIds);
        for (int i = 0; i < all.size(); i += BATCH_SIZE) {
            List<Long> batch = all.subList(i, Math.min(i + BATCH_SIZE, all.size()));
            List<Long> found = chunkMapper.selectLiveChunkIds(batch);
            if (found != null) {
                live.addAll(found);
            }
        }
        return live;
    }

    /**
     * 判断文档是否仍存活（存在且未软删）。
     * <p>{@code selectById} 走 MyBatis-Plus 自动 SQL，{@code @TableLogic} 会自动追加
     * {@code deleted = 0}，因此已软删文档返回 {@code null}。</p>
     *
     * @param documentId 文档 ID
     * @param cache      文档存活缓存（同轮扫描内复用，避免重复查询）
     * @return true 表示文档仍存在且未删除
     */
    private boolean isDocumentAlive(Long documentId, Map<Long, Boolean> cache) {
        return cache.computeIfAbsent(documentId, id -> documentMapper.selectById(id) != null);
    }

    /** 当前活跃（可选）数据源 ID 集合。 */
    private Set<Long> loadActiveDatasourceIds() {
        List<DataSourceConfig> activeSources = dataSourceMapper.selectAllActive();
        return activeSources == null ? Set.of()
                : activeSources.stream().map(DataSourceConfig::getId).collect(Collectors.toSet());
    }

    /**
     * Phase 2: 清理数据源级残留（chunks 的 datasourceId 指向不存在/非活跃数据源）。
     * <p>
     * 覆盖场景：
     * <ul>
     *   <li>数据源已被物理删除，但关联切片/索引/元数据残留</li>
     *   <li>数据源状态为 INACTIVE/ERROR，其切片不应再参与检索</li>
     * </ul>
     * </p>
     * <p>
     * <b>批次 08 · 任务 8.3</b>：本阶段只负责 <b>MySQL 侧</b>残留（切片软删、BM25、结构化元数据）。
     * ChromaDB 侧的向量清理已由 Phase 1 统一覆盖（按 {@code datasource_id} 元数据判定活跃性，
     * 且能覆盖"切片已软删但向量残留"的场景），故不再在此重复调用
     * {@code deleteByMetadata}——避免二次删除导致的计数为 0 的误导性日志。
     * </p>
     */
    private void cleanDatasourceOrphans() {
        try {
            // 1. 获取所有活跃数据源 ID
            Set<Long> activeDsIds = loadActiveDatasourceIds();
            log.debug("活跃数据源 ID 集合: size={}", activeDsIds.size());

            // 2. 查询所有 datasource_id 不为空的未删除切片
            List<Chunk> dsChunks = chunkMapper.selectList(
                    new LambdaQueryWrapper<Chunk>()
                            .isNotNull(Chunk::getDatasourceId)
                            .eq(Chunk::getDeleted, 0));

            if (dsChunks.isEmpty()) {
                return;
            }

            // 3. 找出孤儿：datasourceId 不在活跃数据源集合中的切片
            List<Chunk> orphanChunks = dsChunks.stream()
                    .filter(c -> c.getDatasourceId() != null && !activeDsIds.contains(c.getDatasourceId()))
                    .toList();

            if (orphanChunks.isEmpty()) {
                return;
            }

            log.info("发现数据源孤儿切片: count={}", orphanChunks.size());

            int softDeleted = 0;
            int bm25Cleaned = 0;
            int structuredCleaned = 0;

            for (Chunk chunk : orphanChunks) {
                // 4. 软删除切片（MyBatis-Plus @TableLogic → UPDATE SET deleted=1）
                try {
                    chunkMapper.deleteById(chunk.getId());
                    softDeleted++;
                } catch (Exception e) {
                    log.warn("数据源孤儿切片软删除失败: chunkId={}", chunk.getId(), e);
                }

                // 5. 从 BM25 索引移除
                if (bm25Retriever != null) {
                    try {
                        bm25Retriever.removeFromIndex(chunk.getId());
                        bm25Cleaned++;
                    } catch (Exception e) {
                        log.warn("BM25 索引移除失败: chunkId={}", chunk.getId(), e);
                    }
                }

                // 6. 清理结构化元数据
                if (chunkStructuredMapper != null) {
                    try {
                        chunkStructuredMapper.deleteByChunkId(chunk.getId());
                        structuredCleaned++;
                    } catch (Exception e) {
                        log.warn("结构化元数据清理失败: chunkId={}", chunk.getId(), e);
                    }
                }
            }

            log.info("数据源孤儿清理完成: 切片软删除={}, BM25={}, 结构化元数据={}（ChromaDB 侧由 Phase 1 统一处理）",
                    softDeleted, bm25Cleaned, structuredCleaned);
        } catch (Exception e) {
            log.error("数据源孤儿扫描异常", e);
        }
    }
}
