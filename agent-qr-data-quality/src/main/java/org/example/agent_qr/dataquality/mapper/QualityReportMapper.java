package org.example.agent_qr.dataquality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import org.example.agent_qr.dataquality.entity.QualityReport;

/**
 * 质检报告 MyBatis-Plus Mapper。
 * <p>
 * 对应 quality_report 表，failures 字段由 JacksonTypeHandler 自动处理 JSON 序列化。
 * </p>
 *
 * @author agent-qr
 */
@Mapper
public interface QualityReportMapper extends BaseMapper<QualityReport> {

    /**
     * 根据批次 ID 查询质检报告。
     * <p>
     * <b>★ R42（批次 10 独立验证证实，随批次 10 第二批修复）</b>：必须显式指定
     * {@code @ResultMap("mybatis-plus_QualityReport")}。原因是 MyBatis-Plus 3.5.5
     * <b>不会</b>给自定义 {@code @Select} 方法套用实体的 {@code autoResultMap}
     * （{@code MybatisMapperAnnotationBuilder} 不处理该注解）——结果是
     * {@code quality_report.failures} 这个 JSON 列不被 {@code JacksonTypeHandler} 处理，
     * 详情接口（{@code GET /api/dataquality/reports/{batchId}}）读回的 failures
     * <b>恒为空列表</b>（列表接口走 BaseMapper 的自动 resultMap，因此正常）。
     * </p>
     * <p>
     * 选择"补 {@code @ResultMap}"而非"改走 BaseMapper wrapper 查询"的理由：
     * ① 修复面最小（1 行），不动 Service 的调用契约；
     * ② 保留自定义 SQL 的可读性与 batch_id 索引语义；
     * ③ 该 resultMap 由 MyBatis-Plus 依据 {@code @TableName(autoResultMap = true)} 生成，
     * 实体注解一旦缺失会在启动期直接报错（fail-fast），不会静默退化。
     * </p>
     *
     * @param batchId 同步批次 ID
     * @return 质检报告（failures 已由 JacksonTypeHandler 反序列化），未找到返回 null
     */
    @ResultMap("mybatis-plus_QualityReport")
    @Select("SELECT * FROM quality_report WHERE batch_id = #{batchId}")
    QualityReport selectByBatchId(@Param("batchId") String batchId);
}
