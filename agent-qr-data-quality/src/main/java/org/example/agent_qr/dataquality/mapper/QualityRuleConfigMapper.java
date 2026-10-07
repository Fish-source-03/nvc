package org.example.agent_qr.dataquality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.agent_qr.dataquality.entity.QualityRuleConfig;

/**
 * 质检规则配置 MyBatis-Plus Mapper（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 对应 {@code quality_rule} 表。查询统一走 {@link BaseMapper} 的方法（配合
 * {@code LambdaQueryWrapper}），以复用实体上的 {@code autoResultMap = true}
 * ——{@code params} JSON 列依赖它触发 {@code JacksonTypeHandler}。
 * </p>
 *
 * @author agent-qr
 */
@Mapper
public interface QualityRuleConfigMapper extends BaseMapper<QualityRuleConfig> {
}
