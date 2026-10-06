package org.example.agent_qr.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.agent_qr.rag.entity.ChunkStructured;

import java.util.List;

/**
 * 切片结构化字段 Mapper，提供 kb_chunk_structured 表的 CRUD 操作。
 * <p>
 * 数据同步管线使用：将 ETL 标准化后的结构化元数据（数值、日期、枚举等）
 * 写入此表，支持检索时的 MySQL B+ 树前置过滤。
 * </p>
 *
 * @author agent-qr
 */
@Mapper
public interface ChunkStructuredMapper extends BaseMapper<ChunkStructured> {

    /**
     * 批量插入结构化字段（批次 05 · 任务 5.2.2）。
     * <p>
     * 原实现按"每条记录的每个字段"逐条 {@link BaseMapper#insert}（一条记录 N 个字段
     * = N 次 SQL 往返），批量后每次 1000 行。调用方需自行分批。
     * </p>
     *
     * @param list 待插入的结构化字段列表（非空、非空列表）
     * @return 影响行数
     */
    @Insert("<script>" +
            "INSERT INTO kb_chunk_structured " +
            "(chunk_id, domain, field_name, field_value, numeric_value, date_value, field_type) VALUES " +
            "<foreach collection='list' item='s' separator=','>" +
            "(#{s.chunkId}, #{s.domain}, #{s.fieldName}, #{s.fieldValue}, " +
            "#{s.numericValue}, #{s.dateValue}, #{s.fieldType})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<ChunkStructured> list);

    /**
     * 按切片 ID 查询所有结构化字段。
     *
     * @param chunkId 切片 ID
     * @return 结构化字段列表
     */
    @Select("SELECT * FROM kb_chunk_structured WHERE chunk_id = #{chunkId}")
    List<ChunkStructured> selectByChunkId(@Param("chunkId") Long chunkId);

    /**
     * 按切片 ID 删除所有结构化字段。
     *
     * @param chunkId 切片 ID
     * @return 受影响的行数
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM kb_chunk_structured WHERE chunk_id = #{chunkId}")
    int deleteByChunkId(@Param("chunkId") Long chunkId);
}
