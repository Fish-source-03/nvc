package org.example.agent_qr.dataquality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.dataquality.entity.QualityRuleConfig;
import org.example.agent_qr.dataquality.mapper.QualityRuleConfigMapper;
import org.example.agent_qr.dataquality.rule.LengthRule;
import org.example.agent_qr.dataquality.rule.QualityRule;
import org.example.agent_qr.dataquality.rule.RuleConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 质检规则配置服务（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 承担两件事：
 * </p>
 * <ol>
 *   <li><b>规则 CRUD</b>——供 {@code DataQualityController} 的规则管理端点调用，
 *       写入前做参数校验（规则类型必须是已实现的类型、正则必须可编译、长度区间必须自洽等）；</li>
 *   <li><b>规则动态加载</b>——{@link #loadActiveRules()} 在<b>每次质检开始时</b>从
 *       {@code quality_rule} 表读取启用的规则（按优先级升序），转换为 {@link RuleConfig}
 *       交给 {@code DataQualityChecker} 分派执行。
 *       <b>不做缓存</b>，因此"规则变更下次质检即生效"，无需重启。</li>
 * </ol>
 * <p>
 * 规则类型清单由 Spring 注入的 {@link QualityRule} 实现类推导（{@code getType()}），
 * 新增规则实现即自动成为可配置类型——不需要在本类维护一份平行清单。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class QualityRuleService {

    /** 默认优先级（未指定时使用；数值小者先执行） */
    public static final int DEFAULT_PRIORITY = 100;

    /** 规则名称最大长度（与 quality_rule.rule_name 列宽一致） */
    private static final int MAX_RULE_NAME_LENGTH = 128;

    /** 目标字段列表最大长度（与 quality_rule.target_fields 列宽一致） */
    private static final int MAX_TARGET_FIELDS_LENGTH = 512;

    private final QualityRuleConfigMapper ruleConfigMapper;

    /** 已实现的规则类型：类型编码 → 实现（同时是校验白名单） */
    private final Map<String, QualityRule> ruleTypes;

    public QualityRuleService(QualityRuleConfigMapper ruleConfigMapper,
                              List<QualityRule> ruleImplementations) {
        this.ruleConfigMapper = ruleConfigMapper;
        this.ruleTypes = ruleImplementations.stream()
                .collect(Collectors.toUnmodifiableMap(
                        QualityRule::getType, Function.identity(), (a, b) -> a));
        log.info("质检规则类型已注册: {}", ruleTypes.keySet());
    }

    /**
     * 查询全部规则（按优先级升序、ID 升序）。
     *
     * @return 规则配置列表（含禁用规则）
     */
    public List<QualityRuleConfig> listRules() {
        return ruleConfigMapper.selectList(activeQuery());
    }

    /**
     * 按 ID 查询规则。
     *
     * @param id 规则 ID
     * @return 规则配置
     * @throws BusinessException 规则不存在时抛出
     */
    public QualityRuleConfig getRule(Long id) {
        QualityRuleConfig config = ruleConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("质检规则不存在: id=" + id);
        }
        return config;
    }

    /**
     * 新建规则。
     *
     * @param config 规则配置
     * @return 落库后的规则（含自增 ID）
     */
    @Transactional
    public QualityRuleConfig createRule(QualityRuleConfig config) {
        if (config == null) {
            throw new BusinessException("规则配置不能为空");
        }
        config.setId(null);
        validate(config);
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        if (config.getPriority() == null) {
            config.setPriority(DEFAULT_PRIORITY);
        }
        ruleConfigMapper.insert(config);
        log.info("质检规则已创建: id={}, name={}, type={}, enabled={}, priority={}",
                config.getId(), config.getRuleName(), config.getRuleType(),
                config.getEnabled(), config.getPriority());
        return config;
    }

    /**
     * 更新规则（含启用/停用切换）。
     *
     * @param id     规则 ID
     * @param config 新配置（id 以入参为准）
     * @return 更新后的规则
     */
    @Transactional
    public QualityRuleConfig updateRule(Long id, QualityRuleConfig config) {
        if (config == null) {
            throw new BusinessException("规则配置不能为空");
        }
        getRule(id); // 存在性校验
        config.setId(id);
        validate(config);
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        if (config.getPriority() == null) {
            config.setPriority(DEFAULT_PRIORITY);
        }
        ruleConfigMapper.updateById(config);
        log.info("质检规则已更新: id={}, name={}, type={}, enabled={}",
                id, config.getRuleName(), config.getRuleType(), config.getEnabled());
        return config;
    }

    /**
     * 删除规则。
     *
     * @param id 规则 ID
     */
    @Transactional
    public void deleteRule(Long id) {
        getRule(id); // 存在性校验
        ruleConfigMapper.deleteById(id);
        log.info("质检规则已删除: id={}", id);
    }

    /**
     * 加载启用的规则，供质检引擎执行。
     * <p>
     * ★ 生效时机（任务 10.1.5）：每次质检<b>实时查询</b>，不做进程内缓存——
     * 规则的新增 / 修改 / 启停在下一次质检时即生效，无需重启服务。
     * 理由：质检是批次级低频操作，单次 SELECT 的成本相对整批数据处理可忽略，
     * 换取的是"配置与行为永远一致"，避免缓存失效逻辑带来的隐蔽不一致。
     * </p>
     *
     * @return 启用规则的运行期配置（按优先级升序）；无启用规则时返回空列表
     */
    public List<RuleConfig> loadActiveRules() {
        List<QualityRuleConfig> enabledRules = ruleConfigMapper.selectList(
                activeQuery().eq(QualityRuleConfig::getEnabled, true));
        return enabledRules.stream().map(QualityRuleService::toRuleConfig).toList();
    }

    /**
     * 已实现的规则类型编码集合（供前端下拉/校验参考）。
     *
     * @return 类型编码列表
     */
    public List<String> supportedTypes() {
        return List.copyOf(ruleTypes.keySet());
    }

    /**
     * 启用/停用切换（前端 el-switch 专用，避免整规则回填）。
     *
     * @param id      规则 ID
     * @param enabled 是否启用
     * @return 更新后的规则
     */
    @Transactional
    public QualityRuleConfig setEnabled(Long id, boolean enabled) {
        QualityRuleConfig existing = getRule(id);
        existing.setEnabled(enabled);
        ruleConfigMapper.updateById(existing);
        log.info("质检规则启停状态已变更: id={}, enabled={}", id, enabled);
        return existing;
    }

    // ==================== 内部实现 ====================

    /**
     * 基础查询条件：按优先级升序（null 视为默认优先级）、ID 升序，保证执行顺序稳定。
     */
    private static LambdaQueryWrapper<QualityRuleConfig> activeQuery() {
        return new LambdaQueryWrapper<QualityRuleConfig>()
                .orderByAsc(QualityRuleConfig::getPriority)
                .orderByAsc(QualityRuleConfig::getId);
    }

    /**
     * 校验规则配置（非法配置在此被拒绝，不会落库）。
     *
     * @param config 规则配置
     * @throws BusinessException 校验不通过时抛出
     */
    private void validate(QualityRuleConfig config) {
        String ruleName = config.getRuleName();
        if (ruleName == null || ruleName.isBlank()) {
            throw new BusinessException("规则名称不能为空");
        }
        if (ruleName.length() > MAX_RULE_NAME_LENGTH) {
            throw new BusinessException("规则名称长度不能超过 " + MAX_RULE_NAME_LENGTH + " 个字符");
        }

        String ruleType = config.getRuleType();
        if (ruleType == null || ruleType.isBlank()) {
            throw new BusinessException("规则类型不能为空");
        }
        String normalizedType = ruleType.trim();
        if (!ruleTypes.containsKey(normalizedType)) {
            throw new BusinessException("不支持的规则类型: " + ruleType
                    + "（可选值: " + String.join(", ", ruleTypes.keySet()) + "）");
        }
        config.setRuleType(normalizedType);

        String targetFields = config.getTargetFields();
        if (targetFields != null) {
            if (targetFields.length() > MAX_TARGET_FIELDS_LENGTH) {
                throw new BusinessException("目标字段列表长度不能超过 " + MAX_TARGET_FIELDS_LENGTH + " 个字符");
            }
            config.setTargetFields(String.join(",", parseTargetFields(targetFields)));
        }

        Integer priority = config.getPriority();
        if (priority != null && priority < 0) {
            throw new BusinessException("优先级不能为负数");
        }

        Map<String, Object> params = config.getParams();
        if (params == null) {
            params = new LinkedHashMap<>();
        }

        switch (normalizedType) {
            case LengthRule.TYPE -> validateLengthRule(config, params);
            case "format" -> validateFormatRule(params);
            case "encoding" -> validateCharset(params);
            default -> {
                // 其余类型（completeness / uniqueness）无额外参数约束
            }
        }
    }

    /**
     * 长度规则的参数校验：必须有目标字段，阈值必须非负且区间自洽。
     */
    private void validateLengthRule(QualityRuleConfig config, Map<String, Object> params) {
        if (parseTargetFields(config.getTargetFields()).isEmpty()) {
            throw new BusinessException("长度规则必须指定目标字段（targetFields）");
        }
        Integer min = toInt(params.get(LengthRule.PARAM_MIN_LENGTH), "minLength");
        Integer max = toInt(params.get(LengthRule.PARAM_MAX_LENGTH), "maxLength");
        if (min == null && max == null) {
            throw new BusinessException("长度规则必须至少指定 minLength 或 maxLength");
        }
        if (min != null && min < 0) {
            throw new BusinessException("minLength 不能为负数");
        }
        if (max != null && max < 0) {
            throw new BusinessException("maxLength 不能为负数");
        }
        if (min != null && max != null && min > max) {
            throw new BusinessException("minLength 不能大于 maxLength");
        }
    }

    /**
     * 格式规则：若配置了正则，必须先通过编译校验（否则运行期每批次都会静默跳过）。
     */
    private void validateFormatRule(Map<String, Object> params) {
        Object pattern = params.get("pattern");
        if (pattern == null) {
            return;
        }
        String patternText = String.valueOf(pattern);
        if (patternText.isBlank()) {
            return;
        }
        try {
            Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            throw new BusinessException("非法正则表达式: " + e.getDescription());
        }
    }

    /**
     * 编码规则：若配置了字符集，必须是 JVM 支持的编码名。
     */
    private void validateCharset(Map<String, Object> params) {
        Object charset = params.get("charset");
        if (charset == null) {
            return;
        }
        String charsetName = String.valueOf(charset);
        if (charsetName.isBlank()) {
            return;
        }
        if (!Charset.isSupported(charsetName)) {
            throw new BusinessException("不支持的字符集: " + charsetName);
        }
    }

    /**
     * 把 params 中的值转为整数（非法值直接拒绝，而不是静默忽略）。
     */
    private static Integer toInt(Object value, String paramName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("参数 " + paramName + " 必须是整数: " + value);
        }
    }

    /**
     * 解析逗号分隔的目标字段列表（去空白、去空项、去重保序）。
     *
     * @param targetFields 逗号分隔的字段列表（可为 null）
     * @return 字段列表（可能为空）
     */
    public static List<String> parseTargetFields(String targetFields) {
        if (targetFields == null || targetFields.isBlank()) {
            return List.of();
        }
        return Arrays.stream(targetFields.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * 实体 → 运行期配置。
     *
     * @param config 规则配置实体
     * @return 运行期配置
     */
    private static RuleConfig toRuleConfig(QualityRuleConfig config) {
        return new RuleConfig(
                config.getRuleType(),
                parseTargetFields(config.getTargetFields()),
                config.getParams());
    }
}
