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
     * 按文档 ID 查询该文档的所有切片，按索引升序排列。
     *
     * @param documentId 文档 ID
     * @return 切片列表
     */
    @Select("SELECT * FROM kb_chunk WHERE document_id = #{documentId} ORDER BY chunk_index")
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
     * {@code status} / {@code create_time} 交给数据库默认值，与方法签名保持一致。
     * </p>
     *
     * @param chunks 待插入切片（非空、非空列表）
     * @return 影响行数
     */
    @Insert("<script>" +
            "INSERT INTO kb_chunk (document_id, datasource_id, chunk_index, content, " +
            "char_count, chroma_id, record_hash, deleted) VALUES " +
            "<foreach collection='list' item='c' separator=','>" +
            "(#{c.documentId}, #{c.datasourceId}, #{c.chunkIndex}, #{c.content}, " +
            "#{c.charCount}, #{c.chromaId}, #{c.recordHash}, #{c.deleted})" +
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
     * 查询指定文档所有切片的 ChromaDB ID。
     */
    @Select("SELECT chroma_id FROM kb_chunk WHERE document_id = #{documentId}")
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
}
