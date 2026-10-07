package org.example.agent_qr.common.rag;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 可索引文本数据提供者接口 — 解耦 BM25Retriever 对 ChunkMapper 的直接依赖。
 * <p>
 * 定义在 agent-qr-common，由 knowledge 模块实现（包装 ChunkMapper），
 * rag 模块的 {@code BM25Retriever} 通过本接口获取待索引数据，
 * 遵循依赖倒置原则（DIP）。
 * </p>
 *
 * @author agent-qr
 * @see IndexableText
 */
@FunctionalInterface
public interface IndexableTextProvider {

    /**
     * 获取所有待索引的文本切片。
     *
     * @return 可索引文本列表
     */
    List<IndexableText> findAllIndexable();

    /**
     * 按键集（游标）分页获取待索引文本（批次 07 · 任务 7.3.3）。
     * <p>
     * BM25 索引构建不再一次性全量加载：数据同步场景切片量可达几十万条，
     * 一次性 {@code findAll} 会把全部内容读进内存。实现方应提供
     * {@code id > afterId ORDER BY id LIMIT limit} 的按键集查询
     * （不要用 OFFSET —— 构建过程中数据可能变化，OFFSET 会跳行）。
     * </p>
     * <p>
     * 默认实现退化为"全量加载后过滤"，仅为兼容旧实现的兜底：
     * 它<b>不能</b>降低内存占用，仅供测试/无分页能力的实现使用。
     * </p>
     *
     * @param afterId 上一页的最大 ID（首页传 0）
     * @param limit   单页上限
     * @return 按 ID 升序的一页文本；无更多数据时返回空列表
     */
    default List<IndexableText> findIndexablePage(long afterId, int limit) {
        return findAllIndexable().stream()
                .filter(text -> text != null && text.getId() != null && text.getId() > afterId)
                .sorted(Comparator.comparingLong(IndexableText::getId))
                .limit(limit)
                .collect(Collectors.toList());
    }
}
