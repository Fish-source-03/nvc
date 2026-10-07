package org.example.agent_qr.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.agent_qr.knowledge.entity.Chunk;

import java.util.List;
import java.util.Map;

/**
 * 切片 Mapper 接口，提供切片表的基础 CRUD 及自定义 SQL 操作。
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，自动获得通用 CRUD 能力。
 * </p>
 *
 * @author agent-qr
 */
@Mapper
public interface ChunkMapper extends BaseMapper<Chunk> {

    /**
     * 按文档 ID 删除该文档的所有切片。
     *
     * @param documentId 文档 ID
     * @return 受影响的行数
     */
    @Delete("DELETE FROM kb_chunk WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按文档 ID 查询该文档的所有<b>未删除</b>切片，按索引升序排列。
     * <p>
     * ⚠️ <b>问题 27（批次 08 · 任务 8.1）</b>：本方法是手写 {@code @Select}，
     * MyBatis-Plus 的 {@link com.baomidou.mybatisplus.annotation.TableLogic}
     * <b>只对框架自动生成的 SQL 生效</b>，对手写 SQL 不追加 {@code deleted = 0}。
     * 原实现因此会把已软删（{@code deleted = 1}）的切片一并返回——
     * 该查询经 {@code DocumentQueryService.getChunks} →
     * {@code GET /api/knowledge/documents/{id}/chunks} 对外暴露，
     * 于是"文档已删除但切片仍可查看"。设计 §8.12 明确要求此处过滤
     * （{@code SELECT * FROM kb_chunk WHERE document_id = ? AND deleted = 0}）。
     * </p>
     *
     * @param documentId 文档 ID
     * @return 该文档未删除的切片列表（按 chunk_index 升序）
     */
    @Select("SELECT * FROM kb_chunk WHERE document_id = #{documentId} AND deleted = 0 ORDER BY chunk_index")
    List<Chunk> selectByDocumentId(@Param("documentId") Long documentId);

    // ==================== P2 新增方法 ====================

    /**
     * 批量插入切片（批次 05 · 任务 5.2.2）。
     * <p>
     * 单条多值 INSERT（{@code INSERT INTO ... VALUES (...),(...)}），
     * 替代数据同步 ETL 管线中原先的逐条 {@link BaseMapper#insert} —— 10 万条切片
     * 从 10 万次 SQL 往返降到 100 次。调用方需自行按 {@code BATCH_SIZE}（1000）分批。
     * </p>
     * <p>
     * {@code useGeneratedKeys} 会把自增主键回填到每个 {@link Chunk#getId()}，
     * 供随后写入 {@code kb_chunk_structured} 与向量化使用。
     * {@code create_time} 交给数据库默认值；{@code status} 自批次 07 · 任务 7.0.8 起
     * <b>由调用方显式传入</b>（{@code INDEXED}），不再依赖数据库默认值——
     * 默认值只在真正的"插入了但没给值"场景兜底，显式传入才能让状态语义可被单测锁定。
     * </p>
     *
     * @param chunks 待插入切片（非空、非空列表）
     * @return 影响行数
     */
    @Insert("<script>" +
            "INSERT INTO kb_chunk (document_id, datasource_id, chunk_index, content, " +
            "char_count, chroma_id, record_hash, deleted, status) VALUES " +
            "<foreach collection='list' item='c' separator=','>" +
            "(#{c.documentId}, #{c.datasourceId}, #{c.chunkIndex}, #{c.content}, " +
            "#{c.charCount}, #{c.chromaId}, #{c.recordHash}, #{c.deleted}, #{c.status})" +
            "</foreach>" +
            "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBatch(@Param("list") List<Chunk> chunks);

    /**
     * 软删除指定文档的所有切片。
     */
    @Update("UPDATE kb_chunk SET deleted = 1 WHERE document_id = #{documentId}")
    int softDeleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 查询指定文档所有<b>未删除</b>切片的 ChromaDB ID。
     * <p>
     * ⚠️ <b>批次 08 · 任务 8.1.2（同类排查）</b>：同为手写 {@code @Select}，
     * 原实现同样缺少 {@code deleted = 0}——已软删切片的向量引用 ID 会被带回，
     * 使删除链路对一个逻辑上已不存在的切片发起向量删除（幂等但语义错误）。
     * 调用方 {@code DocumentCommandService.requestDeleteDocument} 在<b>软删之前</b>
     * 收集待删向量 ID，因此过滤不会导致"漏删本应删除的向量"。
     * </p>
     *
     * @param documentId 文档 ID
     * @return 该文档未删除切片的 chroma_id 列表
     */
    @Select("SELECT chroma_id FROM kb_chunk WHERE document_id = #{documentId} AND deleted = 0")
    List<String> selectChromaIdsByDocumentId(@Param("documentId") Long documentId);

    /**
     * 查询所有就绪切片（P2：排除已删除）。
     */
    @Select("SELECT * FROM kb_chunk WHERE status = 'READY' AND deleted = 0")
    List<Chunk> selectAllReadyChunks();

    /**
     * 分页查询就绪切片（P2：用于 BM25 索引构建）。
     */
    @Select("SELECT * FROM kb_chunk WHERE status = 'READY' AND deleted = 0 LIMIT #{limit} OFFSET #{offset}")
    List<Chunk> selectReadyChunksPaged(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 按数据源 ID 查询所有未删除的切片。
     */
    @Select("SELECT * FROM kb_chunk WHERE datasource_id = #{datasourceId} AND deleted = 0")
    List<Chunk> selectByDatasourceId(@Param("datasourceId") Long datasourceId);

    // ==================== 批次 07 · 任务 7.0 新增：状态机与分批读取 ====================

    /**
     * 更新单条切片的状态（批次 07 · 任务 7.0a）。
     *
     * @param id     切片 ID
     * @param status 目标状态（见 {@link Chunk#STATUS_INDEXED} / {@link Chunk#STATUS_READY}）
     * @return 受影响行数
     */
    @Update("UPDATE kb_chunk SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 批量更新切片状态（批次 07 · 任务 7.0a）。
     * <p>向量化成功后一次性把整批切片置为 READY，避免逐条 SQL 往返。</p>
     *
     * @param ids    切片 ID 列表（非空）
     * @param status 目标状态
     * @return 受影响行数
     */
    @Update("<script>UPDATE kb_chunk SET status = #{status} WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int updateStatusByIds(@Param("ids") List<Long> ids, @Param("status") String status);

    /**
     * 按文档批量更新切片状态（批次 07 · 任务 7.0a）。
     *
     * @param documentId 文档 ID
     * @param status     目标状态
     * @return 受影响行数
     */
    @Update("UPDATE kb_chunk SET status = #{status} WHERE document_id = #{documentId} AND deleted = 0")
    int updateStatusByDocumentId(@Param("documentId") Long documentId, @Param("status") String status);

    /**
     * 按文档统计各状态的切片数量（批次 07 · 任务 7.0.4 文档状态聚合推导）。
     * <p>返回行形如 {@code {"status":"INDEXED","cnt":13}}；无切片时返回空列表。</p>
     *
     * @param documentId 文档 ID
     * @return 状态计数列表
     */
    @Select("SELECT status AS status, COUNT(*) AS cnt FROM kb_chunk " +
            "WHERE document_id = #{documentId} AND deleted = 0 GROUP BY status")
    List<Map<String, Object>> countByDocumentIdGroupByStatus(@Param("documentId") Long documentId);

    /**
     * 批量统计多个文档的各状态切片数量（批次 07 · 任务 7.0.4）。
     * <p>
     * 一次查询替代"每个文档查一次"，供文档列表页实时聚合推导使用。
     * 返回行形如 {@code {"documentId":9,"status":"READY","cnt":4}}。
     * </p>
     *
     * @param documentIds 文档 ID 列表（非空）
     * @return 按文档 + 状态分组的计数
     */
    @Select("<script>SELECT document_id AS documentId, status AS status, COUNT(*) AS cnt " +
            "FROM kb_chunk WHERE deleted = 0 AND document_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY document_id, status</script>")
    List<Map<String, Object>> countByDocumentIdsGroupByStatus(@Param("ids") List<Long> documentIds);

    /**
     * 分页读取某文档中<b>尚未向量化</b>的切片（批次 07 · 任务 7.0.7）。
     * <p>
     * <b>Keyset 分页</b>（{@code id > afterId}）而非 OFFSET：处理过程中会把这些切片
     * 置为 READY 从而退出过滤集，OFFSET 分页会因此跳行；keyset 分页只向 id 更大的方向
     * 推进，结果稳定。{@code afterId} 传 0 表示从头开始。
     * </p>
     *
     * @param documentId 文档 ID
     * @param afterId    上一批的最大切片 ID（首批传 0）
     * @param limit      单批上限
     * @return 待向量化切片（按 id 升序）
     */
    @Select("SELECT * FROM kb_chunk WHERE document_id = #{documentId} AND deleted = 0 " +
            "AND (status IS NULL OR status <> 'READY') AND id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<Chunk> selectPendingByDocumentIdAfterId(@Param("documentId") Long documentId,
                                                 @Param("afterId") long afterId,
                                                 @Param("limit") int limit);

    /**
     * 分页读取某数据源中<b>尚未向量化</b>的切片（批次 07 · 任务 7.0.7）。
     * <p>
     * 语义与 {@link #selectPendingByDocumentIdAfterId} 相同，用于数据同步链路
     * （数据同步产生的切片 {@code document_id} 为 NULL）。<b>必须分页</b>——
     * 大数据源同步场景单批可能产生几十万条切片，一次性加载会 OOM。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @param afterId      上一批的最大切片 ID（首批传 0）
     * @param limit        单批上限
     * @return 待向量化切片（按 id 升序）
     */
    @Select("SELECT * FROM kb_chunk WHERE datasource_id = #{datasourceId} AND deleted = 0 " +
            "AND (status IS NULL OR status <> 'READY') AND id > #{afterId} ORDER BY id LIMIT #{limit}")
    List<Chunk> selectPendingByDatasourceIdAfterId(@Param("datasourceId") Long datasourceId,
                                                   @Param("afterId") long afterId,
                                                   @Param("limit") int limit);

    /**
     * 软删除指定数据源的所有切片。
     */
    @Update("UPDATE kb_chunk SET deleted = 1 WHERE datasource_id = #{datasourceId}")
    int softDeleteByDatasourceId(@Param("datasourceId") Long datasourceId);

    /**
     * 按文档 domain 查询切片 ID 列表（用于域过滤，覆盖文档上传管线）。
     * JOIN kb_document 表按 domain 过滤，仅返回未删除的切片。
     */
    @Select("SELECT c.id FROM kb_chunk c INNER JOIN kb_document d ON c.document_id = d.id " +
            "WHERE d.domain = #{domain} AND c.deleted = 0 AND d.deleted = 0 LIMIT 500")
    List<Long> selectChunkIdsByDocumentDomain(@Param("domain") String domain);

    /**
     * 按数据源 ID 查询所有未删除切片的 record_hash（用于跨批次去重）。
     * 仅返回非空且非空字符串的哈希值。
     *
     * @param datasourceId 数据源 ID
     * @return 该数据源所有历史记录的 MD5 指纹列表
     */
    @Select("SELECT record_hash FROM kb_chunk WHERE datasource_id = #{datasourceId} " +
            "AND deleted = 0 AND record_hash IS NOT NULL AND record_hash != ''")
    List<String> selectRecordHashesByDatasourceId(@Param("datasourceId") Long datasourceId);

    /**
     * 查找有 record_hash 的重复切片 ID（保留每组中 id 最小的，返回其余）。
     * 用于定时去重清理 — 精确匹配 record_hash。
     *
     * @return 待删除的重复切片 ID 列表
     */
    @Select("SELECT c2.id FROM kb_chunk c2 " +
            "INNER JOIN (SELECT datasource_id, record_hash, MIN(id) as keep_id " +
            "           FROM kb_chunk WHERE deleted = 0 AND record_hash IS NOT NULL AND record_hash != '' " +
            "           GROUP BY datasource_id, record_hash HAVING COUNT(*) > 1) c1 " +
            "ON c2.datasource_id = c1.datasource_id AND c2.record_hash = c1.record_hash " +
            "AND c2.id != c1.keep_id AND c2.deleted = 0")
    List<Long> selectDuplicateChunkIdsByHash();

    /**
     * 查找历史数据（无 record_hash）中按 MD5(content) 分组重复的切片 ID。
     * 保留每组中 id 最小的，返回其余。用于定时去重清理 — 内容近似匹配。
     *
     * @return 待删除的重复切片 ID 列表
     */
    @Select("SELECT c2.id FROM kb_chunk c2 " +
            "INNER JOIN (SELECT datasource_id, MD5(content) as content_md5, MIN(id) as keep_id " +
            "           FROM kb_chunk WHERE deleted = 0 " +
            "           AND (record_hash IS NULL OR record_hash = '') " +
            "           AND datasource_id IS NOT NULL " +
            "           GROUP BY datasource_id, MD5(content) HAVING COUNT(*) > 1) c1 " +
            "ON c2.datasource_id = c1.datasource_id AND MD5(c2.content) = c1.content_md5 " +
            "AND c2.id != c1.keep_id AND c2.deleted = 0")
    List<Long> selectDuplicateChunkIdsByContent();

    // ==================== 批次 08 · 任务 8.3 新增（孤儿向量扫描，ChromaDB → MySQL 方向） ====================

    /**
     * 批量查询给定 ID 集合中<b>仍然存活</b>（{@code deleted = 0}）的切片 ID。
     * <p>
     * <b>为什么新增而不是复用</b>：孤儿向量扫描（任务 8.3）改为设计 §10.2 要求的
     * "ChromaDB → 对照 MySQL"方向后，需要判定"ChromaDB 中某向量对应的切片是否仍存在于 MySQL"。
     * 这一判定<b>必须由本方法提供</b>，因为：
     * <ul>
     *   <li>{@link #selectAllReadyChunks} 带 {@code status = 'READY'} 过滤，
     *       且任务 8.3 明令不得修改（其他链路依赖其语义）；</li>
     *   <li>{@link #selectByDocumentId} 是<b>按文档</b>查询，
     *       且已被任务 8.1 加上 {@code deleted = 0}（回退风险：孤儿扫描恰恰要覆盖
     *       "切片已软删但向量残留"的场景，绝不能再拿它当扫描输入）。</li>
     * </ul>
     * 本方法按 ID 批量 IN 查询、只返回存活 ID，由调用方自行做差集，
     * 对已软删与物理删除的切片<b>都</b>判定为孤儿。
     * </p>
     *
     * @param ids 待校验的切片 ID 列表（非空；调用方需自行分批，建议 ≤ 1000）
     * @return 其中仍然存活（{@code deleted = 0}）的切片 ID
     */
    @Select("<script>SELECT id FROM kb_chunk WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Long> selectLiveChunkIds(@Param("ids") List<Long> ids);
}
