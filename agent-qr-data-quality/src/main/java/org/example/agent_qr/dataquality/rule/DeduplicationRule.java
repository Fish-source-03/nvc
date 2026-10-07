package org.example.agent_qr.dataquality.rule;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.util.FingerprintUtils;
import org.example.agent_qr.dataquality.entity.RuleResult;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 重复检测规则（规则类型 {@code uniqueness}） — 批次内去重 + 跨批次去重。
 * <p>
 * <b>关于类型编码</b>（批次 10 · 任务 10.1）：类型编码沿用规则管理页原型既有的
 * {@code uniqueness}（"唯一性"），语义即"检查记录是否重复"——避免为了改一个类型名
 * 去改动既有前端页面与文案。本规则<b>不消费</b>任何 {@code quality_rule} 参数，
 * 但可通过停用（enabled=0）从规则链中移除。
 * </p>
 * <p>
 * 使用 ThreadLocal 存储指纹集合，每次质量检查前通过 {@link #reset(Long)} 重置状态
 * 并从数据库预加载该数据源的所有历史 record_hash，检查后通过 {@link #clear()} 清理。
 * </p>
 * <p>
 * 去重策略：
 * <ol>
 *   <li>对每条记录计算 MD5(JSON.serialize(record)) 作为指纹</li>
 *   <li>先检查批次内指纹集合（同一同步批次内的重复）</li>
 *   <li>再检查跨批次指纹集合（与知识库已有数据比对）</li>
 *   <li>任一命中即标记为失败（重复数据）</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class DeduplicationRule implements QualityRule {

    /** 规则类型编码（沿用前端原型的"唯一性"取值，见类注释） */
    public static final String TYPE = "uniqueness";

    @Autowired
    private ChunkMapper chunkMapper;

    /** 批次内指纹集合（同一同步批次内的记录指纹） */
    private final ThreadLocal<Set<String>> intraBatchHashes =
            ThreadLocal.withInitial(HashSet::new);

    /** 跨批次指纹集合（从 kb_chunk 预加载的历史记录指纹） */
    private final ThreadLocal<Set<String>> crossBatchHashes =
            ThreadLocal.withInitial(HashSet::new);

    @Override
    public String getName() {
        return "重复检测";
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 每次质量检查前调用：重置批次内指纹 + 预加载跨批次指纹。
     *
     * @param datasourceId 数据源 ID
     */
    public void reset(Long datasourceId) {
        intraBatchHashes.get().clear();

        // 预加载该数据源所有历史 record_hash
        Set<String> cross = crossBatchHashes.get();
        cross.clear();
        List<String> existingHashes = chunkMapper.selectRecordHashesByDatasourceId(datasourceId);
        cross.addAll(existingHashes);
        log.info("去重规则已重置: datasourceId={}, 历史指纹数={}", datasourceId, cross.size());
    }

    /**
     * 清理 ThreadLocal，防止线程池中的线程复用导致内存泄漏。
     */
    public void clear() {
        intraBatchHashes.remove();
        crossBatchHashes.remove();
    }

    @Override
    public RuleResult evaluate(Map<String, Object> record) {
        String fp = FingerprintUtils.computeRecordFingerprint(record);

        // 先检查批次内重复
        Set<String> intra = intraBatchHashes.get();
        if (!intra.add(fp)) {
            return RuleResult.fail("数据重复：当前批次内存在相同记录");
        }

        // 再检查跨批次重复（与历史数据比对）
        Set<String> cross = crossBatchHashes.get();
        if (cross.contains(fp)) {
            return RuleResult.fail("数据重复：该记录已存在于知识库中");
        }

        return RuleResult.pass();
    }
}
