package org.example.agent_qr.dataquality.checker;

import org.example.agent_qr.dataquality.context.RuleExecutionContext;
import org.example.agent_qr.dataquality.mapper.QualityRuleConfigMapper;
import org.example.agent_qr.dataquality.rule.CompletenessRule;
import org.example.agent_qr.dataquality.rule.DeduplicationRule;
import org.example.agent_qr.dataquality.rule.EncodingRule;
import org.example.agent_qr.dataquality.rule.FormatRule;
import org.example.agent_qr.dataquality.rule.LengthRule;
import org.example.agent_qr.dataquality.rule.QualityRule;
import org.example.agent_qr.dataquality.service.QualityRuleService;
import org.example.agent_qr.dataquality.util.CharsetDetector;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 质检引擎的 Spring 装配与规则类型注册测试（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 项目内没有任何 {@code @SpringBootTest}（全仓库零命中），因此这里用一个<b>最小上下文</b>
 * 验证改造后的构造器注入能被 Spring 正确装配：{@code DataQualityChecker} 需要
 * {@code List<QualityRule>}（规则类型注册表）与 {@code QualityRuleService}（动态规则来源）。
 * </p>
 * <p>
 * 同时锁定"规则类型编码"契约：五个实现类的 {@code getType()} 必须互不重复，
 * 否则 {@code DataQualityChecker} 的注册表会静默覆盖、{@code quality_rule.rule_type} 会指向错实现。
 * </p>
 *
 * @author agent-qr
 */
@SpringJUnitConfig(DataQualityWiringTest.WiringConfig.class)
class DataQualityWiringTest {

    @Autowired
    private DataQualityChecker checker;

    @Autowired
    private QualityRuleService qualityRuleService;

    @Autowired
    private List<QualityRule> rules;

    @Test
    @DisplayName("★ Spring 能装配改造后的 DataQualityChecker（构造器注入 List<QualityRule> + QualityRuleService）")
    void contextShouldWireCheckerWithDynamicRuleSource() {
        assertThat(checker).isNotNull();
        assertThat(qualityRuleService).isNotNull();
        assertThat(rules).hasSize(5);
    }

    @Test
    @DisplayName("★ 规则类型编码唯一且覆盖五类（重复会导致注册表静默覆盖）")
    void ruleTypes_shouldBeUniqueAndComplete() {
        Set<String> types = rules.stream().map(QualityRule::getType).collect(Collectors.toSet());

        assertThat(types).hasSize(rules.size());
        assertThat(types).containsExactlyInAnyOrder(
                "completeness", "uniqueness", "format", "encoding", "length");
    }

    @Test
    @DisplayName("服务层支持的类型与实现类类型一致")
    void serviceTypes_shouldMatchImplementations() {
        assertThat(qualityRuleService.supportedTypes())
                .containsExactlyInAnyOrderElementsOf(
                        rules.stream().map(QualityRule::getType).collect(Collectors.toSet()));
    }

    /**
     * 最小装配：真实规则实现 + 真实服务（Mapper 用 Mock 顶替，不连数据库）。
     */
    @Configuration
    static class WiringConfig {

        @Bean
        QualityRuleConfigMapper qualityRuleConfigMapper() {
            return mock(QualityRuleConfigMapper.class);
        }

        @Bean
        ChunkMapper chunkMapper() {
            return mock(ChunkMapper.class);
        }

        @Bean
        RuleExecutionContext ruleExecutionContext() {
            return new RuleExecutionContext();
        }

        @Bean
        CharsetDetector charsetDetector() {
            return new CharsetDetector();
        }

        @Bean
        CompletenessRule completenessRule() {
            return new CompletenessRule();
        }

        @Bean
        DeduplicationRule deduplicationRule() {
            return new DeduplicationRule();
        }

        @Bean
        EncodingRule encodingRule() {
            return new EncodingRule();
        }

        @Bean
        FormatRule formatRule() {
            return new FormatRule();
        }

        @Bean
        LengthRule lengthRule() {
            return new LengthRule();
        }

        @Bean
        QualityRuleService qualityRuleService(QualityRuleConfigMapper mapper,
                                              List<QualityRule> implementations) {
            return new QualityRuleService(mapper, implementations);
        }

        @Bean
        DataQualityChecker dataQualityChecker(List<QualityRule> implementations,
                                              QualityRuleService service) {
            return new DataQualityChecker(implementations, service);
        }
    }
}
