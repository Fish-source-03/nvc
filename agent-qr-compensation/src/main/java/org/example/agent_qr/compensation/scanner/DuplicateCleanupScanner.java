package org.example.agent_qr.compensation.scanner;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.rag.mapper.ChunkStructuredMapper;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 重复数据定时清理器 — 兜底安全网。
 * <p>
 * 每天凌晨 3 点扫描并清理两类重复切片：
 * <ol>
 *   <li><b>record_hash 精确重复</b>：同一数据源下 record_hash 相同的切片，保留最早创建的</li>
 *   <li><b>内容近似重复</b>：历史数据（无 record_hash）按 MD5(content) 分组，保留最早创建的</li>
 * </ol>
 * 对每个待删除切片执行级联清理：软删除 → BM25 移除 → 结构化元数据清理 → ChromaDB 向量删除。
 * </p>
 * <p>
 * 与 <b>{@code DeduplicationRule}</b>（前置拦截，位于 agent-qr-data-quality 模块）配合形成两层防线：
 * 前置拦截阻止新重复入库 → 定时扫描兜底清理历史残留。
 * </p>
 * <p>
 * ⚠️ 批次 09 · 任务 9.5.4：此处原为 {@code {@link DeduplicationRule}}，但该类在本模块的
 * 编译期可见范围内并不存在（agent-qr-compensation 不依赖 agent-qr-data-quality，
 * 而 knowledge 也不依赖它），javadoc 链接无法解析、点击即失效。
 * 改为纯文本而非补 import——补 import 会为一句注释引入一条 compensation → data-quality
 * 的<b>编译期依赖</b>，把"注释可读性"与"模块依赖"绑定，代价明显不划算。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class DuplicateCleanupScanner {

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired(required = false)
    private ChromaRetriever chromaRetriever;

    @Autowired(required = false)
    private BM25Retriever bm25Retriever;

    @Autowired(required = false)
    private ChunkStructuredMapper chunkStructuredMapper;

    /** 每天凌晨 3 点执行 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanDuplicateChunks() {
        log.info("========== 定时去重清理开始 ==========");

        try {
            // Phase 1: 精确去重（有 record_hash 的重复）
            int exactCleaned = cleanDuplicatesByHash();

            // Phase 2: 内容近似去重（无 record_hash 的历史重复）
            int fuzzyCleaned = cleanDuplicatesByContent();

            int total = exactCleaned + fuzzyCleaned;
            if (total > 0) {
                log.info("========== 定时去重清理完成: 精确重复={}, 历史近似重复={}, 合计={} ==========",
                        exactCleaned, fuzzyCleaned, total);
            } else {
                log.info("========== 定时去重清理完成: 未发现重复数据 ==========");
            }
        } catch (Exception e) {
            log.error("定时去重清理异常", e);
        }
    }

    /**
     * Phase 1: 精确去重 — 按 record_hash 分组，保留每组 id 最小的切片。
     */
    private int cleanDuplicatesByHash() {
        List<Long> duplicateIds = chunkMapper.selectDuplicateChunkIdsByHash();
        if (duplicateIds.isEmpty()) {
            log.debug("精确去重: 未发现 record_hash 重复的切片");
            return 0;
        }

        log.info("精确去重: 发现 {} 个 record_hash 重复的切片待清理", duplicateIds.size());
        return performCascadeCleanup(duplicateIds);
    }

    /**
     * Phase 2: 内容近似去重 — 对无 record_hash 的历史数据，按 MD5(content) 分组。
     */
    private int cleanDuplicatesByContent() {
        List<Long> duplicateIds = chunkMapper.selectDuplicateChunkIdsByContent();
        if (duplicateIds.isEmpty()) {
            log.debug("内容近似去重: 未发现历史重复切片");
            return 0;
        }

        log.info("内容近似去重: 发现 {} 个历史重复切片待清理（无 record_hash，按 MD5(content) 匹配）",
                duplicateIds.size());
        return performCascadeCleanup(duplicateIds);
    }

    /**
     * 对给定的切片 ID 列表执行级联清理。
     * <p>
     * 清理链路：软删除切片 → BM25 索引移除 → 结构化元数据删除 → ChromaDB 向量删除。
     * 每个步骤独立 try-catch，单条失败不影响其他切片的清理。
     * </p>
     *
     * @param chunkIds 待删除的切片 ID 列表
     * @return 成功清理的数量
     */
    private int performCascadeCleanup(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }

        int softDeleted = 0;
        int bm25Cleaned = 0;
        int structuredCleaned = 0;
        int chromaCleaned = 0;

        // 收集需要清理 ChromaDB 的 chromaId
        Set<String> chromaIdsToDelete = new HashSet<>();

        for (Long chunkId : chunkIds) {
            // 1. 先查询切片信息（获取 chromaId）
            Chunk chunk = chunkMapper.selectById(chunkId);
            if (chunk == null) {
                continue;
            }

            // 2. 软删除切片（MyBatis-Plus @TableLogic → UPDATE SET deleted=1）
            try {
                chunkMapper.deleteById(chunkId);
                softDeleted++;
            } catch (Exception e) {
                log.warn("重复切片软删除失败: chunkId={}", chunkId, e);
                continue; // 软删除失败则跳过后续清理，避免孤立数据
            }

            // 3. 从 BM25 索引移除
            if (bm25Retriever != null) {
                try {
                    bm25Retriever.removeFromIndex(chunkId);
                    bm25Cleaned++;
                } catch (Exception e) {
                    log.warn("BM25 索引移除失败: chunkId={}", chunkId, e);
                }
            }

            // 4. 清理结构化元数据
            if (chunkStructuredMapper != null) {
                try {
                    chunkStructuredMapper.deleteByChunkId(chunkId);
                    structuredCleaned++;
                } catch (Exception e) {
                    log.warn("结构化元数据清理失败: chunkId={}", chunkId, e);
                }
            }

            // 5. 收集 ChromaDB 向量 ID（批量删除）
            if (chunk.getChromaId() != null && !chunk.getChromaId().isBlank()
                    && !"pending".equals(chunk.getChromaId())) {
                chromaIdsToDelete.add(chunk.getChromaId());
            }
        }

        // 6. 批量清理 ChromaDB 向量
        if (chromaRetriever != null && !chromaIdsToDelete.isEmpty()) {
            try {
                chromaRetriever.deleteByIds(List.copyOf(chromaIdsToDelete));
                chromaCleaned = chromaIdsToDelete.size();
            } catch (Exception e) {
                log.warn("ChromaDB 批量向量删除失败: count={}", chromaIdsToDelete.size(), e);
            }
        }

        log.info("级联清理统计: 软删除={}, BM25={}, 结构化元数据={}, ChromaDB向量={}",
                softDeleted, bm25Cleaned, structuredCleaned, chromaCleaned);
        return softDeleted;
    }
}
