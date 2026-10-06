package org.example.agent_qr.rag.filter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.agent_qr.rag.entity.ChunkStructured;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.FilterConditionExtractor.FieldDefinition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 切片结构化字段 Mapper，提供基于结构化字段的过滤查询。
 * <p>
 * 所有 SQL 的 {@code LIMIT} 由调用方通过 {@code limit} 参数决定：
 * <ul>
 *   <li>语义检索路径：{@code StructuredFilterService.CANDIDATE_LIMIT}（500，沿用历史语义）；</li>
 *   <li>聚合查询路径（批次 04 · 任务 4.4）：{@code agent-qr.aggregation.max-chunk-ids}（默认 2000）。</li>
 * </ul>
 * LIMIT 使用绑定参数（{@code LIMIT #{limit}}）而非硬编码常量，避免为两条路径复制整套 SQL。
 * </p>
 * <p>
 * 比较语义（批次 04 · 任务 4.1，问题 20）：
 * {@code selectChunkIdsByNumberRange} / {@code selectChunkIdsByDateRange} 为<b>闭区间</b>
 * （{@code >= min AND <= max}），开区间语义由 {@code Gt}/{@code Gte}/{@code Lt}/{@code Lte}
 * 系列方法显式表达，不再用「区间退化」的写法模拟。
 * </p>
 *
 * @author agent-qr
 */
@Mapper
public interface ChunkStructuredFilterMapper extends BaseMapper<ChunkStructured> {

    // ==================== 数值型 ====================

    /**
     * 按数值闭区间过滤切片 ID（无 operator 时的区间语义，与历史行为一致）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND numeric_value >= #{min} AND numeric_value <= #{max} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByNumberRange(@Param("fieldName") String fieldName,
                                           @Param("min") BigDecimal min,
                                           @Param("max") BigDecimal max,
                                           @Param("limit") int limit);

    /**
     * 按数值严格大于过滤切片 ID（operator = GT）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND numeric_value > #{value} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByNumberGt(@Param("fieldName") String fieldName,
                                        @Param("value") BigDecimal value,
                                        @Param("limit") int limit);

    /**
     * 按数值大于等于过滤切片 ID（operator = GTE）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND numeric_value >= #{value} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByNumberGte(@Param("fieldName") String fieldName,
                                         @Param("value") BigDecimal value,
                                         @Param("limit") int limit);

    /**
     * 按数值严格小于过滤切片 ID（operator = LT）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND numeric_value < #{value} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByNumberLt(@Param("fieldName") String fieldName,
                                        @Param("value") BigDecimal value,
                                        @Param("limit") int limit);

    /**
     * 按数值小于等于过滤切片 ID（operator = LTE）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND numeric_value <= #{value} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByNumberLte(@Param("fieldName") String fieldName,
                                         @Param("value") BigDecimal value,
                                         @Param("limit") int limit);

    // ==================== 日期型 ====================

    /**
     * 按日期闭区间过滤切片 ID（无 operator 时的区间语义，与历史行为一致）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND date_value >= #{start} AND date_value <= #{end} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByDateRange(@Param("fieldName") String fieldName,
                                         @Param("start") LocalDate start,
                                         @Param("end") LocalDate end,
                                         @Param("limit") int limit);

    /**
     * 按日期严格晚于过滤切片 ID（operator = GT）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND date_value > #{value} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByDateGt(@Param("fieldName") String fieldName,
                                      @Param("value") LocalDate value,
                                      @Param("limit") int limit);

    /**
     * 按日期不早于过滤切片 ID（operator = GTE）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND date_value >= #{value} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByDateGte(@Param("fieldName") String fieldName,
                                       @Param("value") LocalDate value,
                                       @Param("limit") int limit);

    /**
     * 按日期严格早于过滤切片 ID（operator = LT）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND date_value < #{value} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByDateLt(@Param("fieldName") String fieldName,
                                      @Param("value") LocalDate value,
                                      @Param("limit") int limit);

    /**
     * 按日期不晚于过滤切片 ID（operator = LTE）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND date_value <= #{value} " +
            "ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByDateLte(@Param("fieldName") String fieldName,
                                       @Param("value") LocalDate value,
                                       @Param("limit") int limit);

    // ==================== 枚举 / 字符串 ====================

    /**
     * 按字符串值精确匹配切片 ID（ENUM / STRING）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND field_value = #{value} ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByStringValue(@Param("fieldName") String fieldName,
                                           @Param("value") String value,
                                           @Param("limit") int limit);

    // ==================== 业务域 ====================

    /**
     * 按业务域查询切片 ID（用于域过滤，数据同步管线）。
     */
    @Select("SELECT DISTINCT chunk_id FROM kb_chunk_structured WHERE domain = #{domain} ORDER BY chunk_id LIMIT #{limit}")
    List<Long> selectChunkIdsByDomain(@Param("domain") String domain,
                                      @Param("limit") int limit);

    /**
     * 按文档 domain 查询切片 ID（用于域过滤，文档上传管线）。
     * JOIN kb_chunk + kb_document，仅返回未删除的切片。
     */
    @Select("SELECT c.id FROM kb_chunk c INNER JOIN kb_document d ON c.document_id = d.id " +
            "WHERE d.domain = #{domain} AND c.deleted = 0 AND d.deleted = 0 ORDER BY c.id LIMIT #{limit}")
    List<Long> selectChunkIdsByDocumentDomain(@Param("domain") String domain,
                                              @Param("limit") int limit);

    // ==================== 聚合查询路径（批次 04 · 任务 4.4） ====================

    /**
     * 按切片 ID 批量取回切片内容（聚合路径专用）。
     * <p>
     * 聚合路径需要把「全部匹配记录」交给 LLM，而 agent-qr-rag 模块不依赖 agent-qr-knowledge，
     * 无法复用 {@code ChunkMapper#selectBatchIds}，因此在结构化过滤 Mapper 中直接查询 kb_chunk。
     * 仅返回未删除的切片；{@code documentId} / {@code similarity} 由调用方补齐。
     * </p>
     *
     * @param ids 切片 ID 列表（调用方保证非空，空列表会导致 IN () 语法错误）
     * @return 切片内容列表（按 id 升序）
     */
    @Select("<script>SELECT c.id AS chunkId, c.content AS content, d.title AS documentTitle " +
            "FROM kb_chunk c LEFT JOIN kb_document d ON c.document_id = d.id " +
            "WHERE c.deleted = 0 AND c.id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY c.id</script>")
    List<RetrievedDocument> selectChunkContentsByIds(@Param("ids") List<Long> ids);

    // ==================== 字段定义（批次 04 · 任务 4.3，供 FilterConditionExtractor 使用） ====================

    /**
     * 查询指定域下的所有可用字段定义（去重）。
     * 用于 FilterConditionExtractor 构建 LLM Prompt 中的字段列表。
     *
     * @param domain 业务域
     * @return 字段定义列表（{@code enumValues} 为 null，需由调用方二次查询）
     */
    @Select("SELECT DISTINCT field_name AS fieldName, field_type AS fieldType " +
            "FROM kb_chunk_structured WHERE domain = #{domain} " +
            "ORDER BY field_name")
    List<FieldDefinition> selectDistinctFieldsByDomain(@Param("domain") String domain);

    /**
     * 查询指定域下某枚举字段的所有可选值。
     *
     * @param fieldName 字段名
     * @param domain    业务域
     * @return 枚举值列表（去重，最多 100 个）
     */
    @Select("SELECT DISTINCT field_value FROM kb_chunk_structured " +
            "WHERE field_name = #{fieldName} AND domain = #{domain} AND field_type = 'ENUM' " +
            "ORDER BY field_value LIMIT 100")
    List<String> selectEnumValues(@Param("fieldName") String fieldName,
                                  @Param("domain") String domain);
}
