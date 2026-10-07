package org.example.agent_qr.dataquality.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.dataquality.entity.QualityRuleConfig;
import org.example.agent_qr.dataquality.mapper.QualityRuleConfigMapper;
import org.example.agent_qr.dataquality.rule.CompletenessRule;
import org.example.agent_qr.dataquality.rule.DeduplicationRule;
import org.example.agent_qr.dataquality.rule.EncodingRule;
import org.example.agent_qr.dataquality.rule.FormatRule;
import org.example.agent_qr.dataquality.rule.LengthRule;
import org.example.agent_qr.dataquality.rule.QualityRule;
import org.example.agent_qr.dataquality.rule.RuleConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QualityRuleService} 测试（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 覆盖：规则 CRUD 的参数校验（非法配置被拒绝）、启用过滤与优先级排序、
 * 以及实体 → {@link RuleConfig} 的转换口径。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QualityRuleServiceTest {

    @Mock
    private QualityRuleConfigMapper ruleConfigMapper;

    private QualityRuleService service;

    /**
     * 初始化 {@link QualityRuleConfig} 的 MyBatis-Plus 元数据（TableInfo）。
     * <p>
     * {@code loadActiveRules} 构造 {@link LambdaQueryWrapper} 并在断言中读取
     * {@code getSqlSegment()}（用于验证 SQL 里确有 enabled 过滤与 priority 排序），
     * 而 lambda 列名解析依赖 TableInfo 缓存。该缓存原先<b>只由同 JVM 内的实库测试间接初始化</b>——
     * 单跑本类或 MySQL 不可达（实库测试被 Assumptions 跳过）时，
     * 会抛 {@code MybatisPlusException: can not find lambda cache for this entity}。
     * 这里显式初始化，使本类不依赖执行顺序与外部数据库。
     * </p>
     */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), QualityRuleConfig.class);
    }

    @BeforeEach
    void setUp() {
        List<QualityRule> implementations = List.of(
                new CompletenessRule(), new EncodingRule(), new FormatRule(),
                new LengthRule(), new DeduplicationRule());
        service = new QualityRuleService(ruleConfigMapper, implementations);
    }

    // ==================== CRUD ====================

    @Test
    @DisplayName("新建规则：落库并回填默认启用状态与优先级")
    void createRule_shouldPersistWithDefaults() {
        QualityRuleConfig config = rule("字段非空检查", "completeness");
        config.setTargetFields("email,name");
        when(ruleConfigMapper.insert(any(QualityRuleConfig.class))).thenReturn(1);

        QualityRuleConfig created = service.createRule(config);

        assertThat(created.getEnabled()).isTrue();
        assertThat(created.getPriority()).isEqualTo(QualityRuleService.DEFAULT_PRIORITY);
        assertThat(created.getTargetFields()).isEqualTo("email,name");
        verify(ruleConfigMapper).insert(config);
    }

    @Test
    @DisplayName("查询 / 更新 / 删除：走 Mapper 且更新前做存在性校验")
    void updateAndDelete_shouldCheckExistence() {
        QualityRuleConfig existing = rule("格式检查", "format");
        existing.setId(7L);
        when(ruleConfigMapper.selectById(7L)).thenReturn(existing);
        when(ruleConfigMapper.updateById(any(QualityRuleConfig.class))).thenReturn(1);
        when(ruleConfigMapper.deleteById(7L)).thenReturn(1);

        QualityRuleConfig update = rule("格式检查（改）", "format");
        QualityRuleConfig updated = service.updateRule(7L, update);
        assertThat(updated.getId()).isEqualTo(7L);

        service.deleteRule(7L);
        verify(ruleConfigMapper).updateById(update);
        verify(ruleConfigMapper).deleteById(7L);
    }

    @Test
    @DisplayName("更新不存在的规则被拒绝")
    void updateRule_shouldRejectMissingRule() {
        when(ruleConfigMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateRule(99L, rule("x", "completeness")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verify(ruleConfigMapper, times(0)).updateById(any(QualityRuleConfig.class));
    }

    @Test
    @DisplayName("启停切换：只改 enabled 并落库")
    void setEnabled_shouldToggleFlag() {
        QualityRuleConfig existing = rule("编码检查", "encoding");
        existing.setId(3L);
        existing.setEnabled(true);
        when(ruleConfigMapper.selectById(3L)).thenReturn(existing);
        when(ruleConfigMapper.updateById(any(QualityRuleConfig.class))).thenReturn(1);

        QualityRuleConfig updated = service.setEnabled(3L, false);

        assertThat(updated.getEnabled()).isFalse();
        verify(ruleConfigMapper).updateById(existing);
    }

    // ==================== 参数校验（非法规则配置被拒绝） ====================

    @Test
    @DisplayName("非法规则配置被拒绝：未知规则类型")
    void createRule_shouldRejectUnknownRuleType() {
        assertThatThrownBy(() -> service.createRule(rule("脚本规则", "groovy-script")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的规则类型");
        verify(ruleConfigMapper, times(0)).insert(any(QualityRuleConfig.class));
    }

    @Test
    @DisplayName("非法规则配置被拒绝：规则名为空 / 超长")
    void createRule_shouldRejectBlankOrTooLongName() {
        assertThatThrownBy(() -> service.createRule(rule("  ", "completeness")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("规则名称不能为空");

        assertThatThrownBy(() -> service.createRule(rule("x".repeat(129), "completeness")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("长度不能超过");
    }

    @Test
    @DisplayName("非法规则配置被拒绝：格式规则的正则表达式不可编译")
    void createRule_shouldRejectInvalidRegex() {
        QualityRuleConfig config = rule("格式检查", "format");
        config.setTargetFields("email");
        config.setParams(Map.of("pattern", "[unclosed"));

        assertThatThrownBy(() -> service.createRule(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法正则表达式");
    }

    @Test
    @DisplayName("非法规则配置被拒绝：长度规则缺少目标字段 / 缺少阈值 / 区间倒置")
    void createRule_shouldRejectInvalidLengthRule() {
        QualityRuleConfig noTarget = rule("长度检查", "length");
        noTarget.setParams(Map.of("minLength", 1));
        assertThatThrownBy(() -> service.createRule(noTarget))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须指定目标字段");

        QualityRuleConfig noThreshold = rule("长度检查", "length");
        noThreshold.setTargetFields("name");
        noThreshold.setParams(Map.of());
        assertThatThrownBy(() -> service.createRule(noThreshold))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("minLength 或 maxLength");

        QualityRuleConfig inverted = rule("长度检查", "length");
        inverted.setTargetFields("name");
        inverted.setParams(Map.of("minLength", 10, "maxLength", 3));
        assertThatThrownBy(() -> service.createRule(inverted))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能大于");
    }

    @Test
    @DisplayName("非法规则配置被拒绝：编码规则使用了不支持的字符集")
    void createRule_shouldRejectUnsupportedCharset() {
        QualityRuleConfig config = rule("编码检查", "encoding");
        config.setParams(Map.of("charset", "NO-SUCH-CHARSET"));

        assertThatThrownBy(() -> service.createRule(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的字符集");
    }

    // ==================== 动态加载 ====================

    @Test
    @DisplayName("★ 只加载启用的规则，并按优先级升序（停用规则不参与质检）")
    void loadActiveRules_shouldQueryOnlyEnabled_andConvertConfig() {
        QualityRuleConfig enabled = rule("字段非空", "completeness");
        enabled.setTargetFields("email, name");
        enabled.setParams(Map.of("k", "v"));
        when(ruleConfigMapper.selectList(any(Wrapper.class))).thenReturn(List.of(enabled));

        List<RuleConfig> active = service.loadActiveRules();

        assertThat(active).hasSize(1);
        assertThat(active.get(0).ruleType()).isEqualTo("completeness");
        assertThat(active.get(0).targetFields()).containsExactly("email", "name");
        assertThat(active.get(0).params()).containsEntry("k", "v");

        // SQL 条件里必须带 enabled 过滤与优先级排序
        ArgumentCaptor<LambdaQueryWrapper<QualityRuleConfig>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ruleConfigMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("enabled");
        assertThat(sqlSegment).contains("priority");
    }

    @Test
    @DisplayName("支持的规则类型来自实现类（新增实现即自动可配置）")
    void supportedTypes_shouldComeFromImplementations() {
        assertThat(service.supportedTypes())
                .containsExactlyInAnyOrder(
                        "completeness", "uniqueness", "format", "encoding", "length");
    }

    @Test
    @DisplayName("目标字段解析：去空白 / 去空项 / 去重保序")
    void parseTargetFields_shouldNormalize() {
        assertThat(QualityRuleService.parseTargetFields(" email , name ,,email "))
                .containsExactly("email", "name");
        assertThat(QualityRuleService.parseTargetFields(null)).isEmpty();
        assertThat(QualityRuleService.parseTargetFields("  ")).isEmpty();
    }

    // ==================== 辅助 ====================

    /**
     * 构造规则配置（默认启用）。
     */
    private static QualityRuleConfig rule(String name, String type) {
        QualityRuleConfig config = new QualityRuleConfig();
        config.setRuleName(name);
        config.setRuleType(type);
        config.setEnabled(true);
        config.setParams(new HashMap<>());
        return config;
    }
}
