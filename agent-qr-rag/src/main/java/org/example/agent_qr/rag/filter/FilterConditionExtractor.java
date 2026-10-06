package org.example.agent_qr.rag.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.filter.mapper.ChunkStructuredFilterMapper;
import org.example.agent_qr.rag.provider.LLMProvider;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * LLM 结构化过滤条件提取器（批次 04 · 任务 4.3，问题 12）。
 * <p>
 * 从用户自然语言问题中提取结构化过滤条件（数值、日期、枚举），
 * 转为 {@link FilterCondition} 列表，交由 {@link StructuredFilterService} 做 SQL 前置过滤。
 * </p>
 * <p>
 * <b>灰度开关</b>：{@code agent-qr.filter.llm-extract.enabled}，<b>默认 false</b>——
 * 未开启时 {@link #extract} 直接返回空列表，行为与修复前完全一致（回归安全）。
 * </p>
 * <p>
 * <b>降级策略（任何异常都不得阻塞问答主流程）</b>：
 * <ul>
 *   <li>开关关闭 / 域为空 / 域内无结构化字段 → 空列表（无过滤条件，走原链路）</li>
 *   <li>LLM 调用异常或超时 → 空列表</li>
 *   <li>JSON 解析失败 → 空列表</li>
 *   <li>单条条件校验不通过 → 丢弃该条，其余保留</li>
 * </ul>
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class FilterConditionExtractor {

    /** Jackson 解析 LLM 输出：字段名/类型由本类校验，未知字段直接忽略（LLM 常带多余字段） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String FILTER_SYSTEM_PROMPT_TEMPLATE = """
            你是一个结构化查询条件提取器。从用户问题中提取过滤条件。

            可用字段定义（JSON数组，每个字段包含fieldName/fieldType/enumValues）：
            {availableFields}

            输出规则：
            1. 只输出 JSON 数组，不要任何额外文字
            2. 每个条件对象必须包含：fieldName, fieldType, operator, value
            3. 一个字段最多产生一个条件
            4. 如果用户问题中没有明确的结构化过滤意图（没有提到具体数值、日期、或枚举值），输出空数组 []
            5. operator 取值：
               - NUMBER: EQ, GT, GTE, LT, LTE, BETWEEN
               - DATE: EQ, GT, GTE, LT, LTE, BETWEEN
               - ENUM/STRING: EQ
            6. BETWEEN 时必须同时提供 minValue 和 maxValue
            7. 数值必须是纯数字（不要单位、不要千分位）；日期格式为 yyyy-MM-dd
            8. 枚举值必须精确匹配可用字段中的 enumValues
            9. 大数单位需要换算：1万 = 10000、100万 = 1000000

            Few-shot 示例：
            问题："去年研发部金额超过100万的采购合同有哪些？"
            可用字段：[{"fieldName":"amount","fieldType":"NUMBER","enumValues":[]},
                      {"fieldName":"dept","fieldType":"ENUM","enumValues":["研发部","财务部","销售部"]},
                      {"fieldName":"signDate","fieldType":"DATE","enumValues":[]}]
            输出：[{"fieldName":"dept","fieldType":"ENUM","operator":"EQ","value":"研发部"},
                  {"fieldName":"amount","fieldType":"NUMBER","operator":"GT","value":"1000000"},
                  {"fieldName":"signDate","fieldType":"DATE","operator":"BETWEEN","minValue":"2025-01-01","maxValue":"2025-12-31"}]

            问题："请介绍一下公司的考勤制度"
            可用字段：[{"fieldName":"dept","fieldType":"ENUM","enumValues":["HR","研发部"]}]
            输出：[]
            """;

    @Autowired
    private ProviderFactory providerFactory;

    @Autowired
    private ChunkStructuredFilterMapper chunkStructuredFilterMapper;

    /** 灰度开关（默认关闭：未开启时行为与修复前完全一致） */
    @Value("${agent-qr.filter.llm-extract.enabled:false}")
    private boolean enabled;

    /** LLM 提取调用超时（秒），超时即降级为「无过滤条件」 */
    @Value("${agent-qr.filter.llm-extract.timeout-seconds:5}")
    private int timeoutSeconds;

    /** 已知操作符集合（用于产出端的合法性校验，见任务 4.1.2） */
    private static final Set<String> KNOWN_OPERATORS = Set.of(
            FilterCondition.OP_EQ, FilterCondition.OP_GT, FilterCondition.OP_GTE,
            FilterCondition.OP_LT, FilterCondition.OP_LTE, FilterCondition.OP_BETWEEN);

    /**
     * 从用户自然语言问题中提取结构化过滤条件。
     *
     * @param query  用户原始问题
     * @param domain 域路由匹配的业务域（如 "HR"）
     * @return 过滤条件列表；未启用、无字段定义、调用失败或解析失败时返回空列表（绝不抛异常）
     */
    public List<FilterCondition> extract(String query, String domain) {
        if (!enabled) {
            log.debug("LLM 过滤条件提取未启用（agent-qr.filter.llm-extract.enabled=false），跳过");
            return List.of();
        }
        if (domain == null || domain.isBlank()) {
            log.debug("域为空，跳过过滤条件提取: query={}", query);
            return List.of();
        }

        // 1. 查询该域的可用字段定义（含枚举值）
        List<FieldDefinition> availableFields = getAvailableFields(domain);
        if (availableFields.isEmpty()) {
            log.debug("域 {} 无可用结构化字段，跳过提取", domain);
            return List.of();
        }

        // 2. 调用 LLM（带超时，任何异常都降级为无过滤条件）
        String llmResponse;
        try {
            llmResponse = callLlm(query, availableFields);
        } catch (TimeoutException e) {
            log.warn("LLM 过滤条件提取超时（{}s），降级为无过滤条件: domain={}, query={}",
                    timeoutSeconds, domain, query);
            return List.of();
        } catch (Exception e) {
            log.warn("LLM 过滤条件提取调用失败，降级为无过滤条件: domain={}, query={}", domain, query, e);
            return List.of();
        }

        // 3. 解析 JSON
        List<FilterCondition> rawConditions;
        try {
            rawConditions = parseResponse(llmResponse);
        } catch (Exception e) {
            log.warn("LLM 过滤条件 JSON 解析失败，降级为无过滤条件: response={}", llmResponse, e);
            return List.of();
        }

        // 4. 校验（字段名 / 类型 / 枚举值 / 操作符）
        List<FilterCondition> validConditions = validate(rawConditions, availableFields);
        log.info("结构化过滤条件提取完成: domain={}, query={}, raw={}, valid={}",
                domain, query, rawConditions.size(), validConditions.size());
        return validConditions;
    }

    /**
     * 调用 LLM 提取条件（虚拟线程 + 超时兜底，避免慢模型阻塞问答）。
     *
     * @throws TimeoutException 超过 {@code timeout-seconds} 未返回
     */
    private String callLlm(String query, List<FieldDefinition> availableFields) throws Exception {
        String systemPrompt = FILTER_SYSTEM_PROMPT_TEMPLATE.replace("{availableFields}",
                OBJECT_MAPPER.writeValueAsString(availableFields));
        List<ChatMessage> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(query));

        LLMProvider llmProvider = providerFactory.getLLMProvider();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<String> future = executor.submit(() -> llmProvider.generate(messages));
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 查询指定域下的可用结构化字段定义（ENUM 字段补充可选值列表）。
     */
    private List<FieldDefinition> getAvailableFields(String domain) {
        List<FieldDefinition> fields;
        try {
            fields = chunkStructuredFilterMapper.selectDistinctFieldsByDomain(domain);
        } catch (Exception e) {
            log.warn("查询可用字段失败，降级为无过滤条件: domain={}", domain, e);
            return List.of();
        }
        if (fields == null) {
            return List.of();
        }
        for (FieldDefinition field : fields) {
            if ("ENUM".equals(field.getFieldType())) {
                try {
                    field.setEnumValues(chunkStructuredFilterMapper.selectEnumValues(
                            field.getFieldName(), domain));
                } catch (Exception e) {
                    log.debug("查询枚举值失败: field={}, domain={}", field.getFieldName(), domain, e);
                }
            }
        }
        return fields;
    }

    /**
     * 清理 LLM 响应中的非 JSON 内容，解析为 {@link FilterCondition} 列表。
     * <p>兼容被 markdown 代码块包裹的响应。</p>
     */
    List<FilterCondition> parseResponse(String llmResponse) throws Exception {
        if (llmResponse == null || llmResponse.isBlank()) {
            throw new IllegalArgumentException("LLM 响应为空");
        }
        String json = llmResponse.trim();
        // 移除 ```json ... ``` / ``` ... ``` 包裹
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline > 0) {
                json = json.substring(firstNewline + 1).trim();
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3).trim();
            }
        }
        if (json.isEmpty()) {
            throw new IllegalArgumentException("LLM 响应中没有 JSON 内容");
        }
        List<FilterCondition> conditions = OBJECT_MAPPER.readValue(
                json, new TypeReference<List<FilterCondition>>() {});
        return conditions == null ? List.of() : conditions;
    }

    /**
     * 校验并归一化提取出的条件（任务 4.3 步骤 4 + 任务 4.1.2 的产出端保障）。
     * <p>
     * 校验项：字段名合法 → 字段类型一致 → 操作符合法（类型相关）→ 值/区间完整且可解析
     * → ENUM 值必须命中真实枚举集合。任一失败即丢弃该条（其余保留）。
     * </p>
     */
    List<FilterCondition> validate(List<FilterCondition> conditions, List<FieldDefinition> validFields) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }

        Map<String, String> fieldTypeMap = validFields.stream()
                .filter(f -> f.getFieldName() != null)
                .collect(Collectors.toMap(FieldDefinition::getFieldName, f ->
                        f.getFieldType() == null ? "" : f.getFieldType(), (a, b) -> a));
        Map<String, Set<String>> enumValueMap = new HashMap<>();
        for (FieldDefinition f : validFields) {
            if (f.getEnumValues() != null && !f.getEnumValues().isEmpty()) {
                enumValueMap.put(f.getFieldName(), new HashSet<>(f.getEnumValues()));
            }
        }

        List<FilterCondition> valid = new ArrayList<>();
        for (FilterCondition c : conditions) {
            if (c == null || c.getFieldName() == null || !fieldTypeMap.containsKey(c.getFieldName())) {
                log.debug("过滤条件字段名无效，丢弃: {}", c);
                continue;
            }
            String expectedType = fieldTypeMap.get(c.getFieldName());
            if (c.getFieldType() == null || !expectedType.equals(c.getFieldType().toUpperCase(Locale.ROOT))) {
                log.debug("过滤条件类型不匹配，丢弃: expected={}, actual={}", expectedType, c.getFieldType());
                continue;
            }
            if (c.getFieldType() == null) {
                continue;
            }
            FilterCondition normalized = normalizeAndValidate(c, expectedType, enumValueMap);
            if (normalized != null) {
                valid.add(normalized);
            }
        }
        return valid;
    }

    /**
     * 单条条件的操作符归一化 + 值校验。
     *
     * @return 归一化后的条件；校验不通过返回 {@code null}（丢弃该条）
     */
    private FilterCondition normalizeAndValidate(FilterCondition c, String fieldType,
                                                 Map<String, Set<String>> enumValueMap) {
        String operator = c.getOperator() == null ? "" : c.getOperator().trim().toUpperCase(Locale.ROOT);

        // 1. 操作符缺失时的归一化（避免产出"静默退化为等值"的条件）
        if (operator.isEmpty()) {
            if (isRangeComplete(c)) {
                operator = FilterCondition.OP_BETWEEN;
            } else if (hasText(c.getValue())) {
                operator = FilterCondition.OP_EQ;
            } else {
                log.debug("过滤条件缺少操作符与有效值，丢弃: {}", c);
                return null;
            }
        }

        // 2. 操作符合法性（按字段类型）
        if (!KNOWN_OPERATORS.contains(operator)) {
            log.warn("过滤条件操作符非法，丢弃: field={}, operator={}", c.getFieldName(), c.getOperator());
            return null;
        }
        if (("ENUM".equals(fieldType) || "STRING".equals(fieldType))
                && !FilterCondition.OP_EQ.equals(operator)) {
            log.warn("字符串/枚举字段只支持 EQ，丢弃: field={}, operator={}", c.getFieldName(), operator);
            return null;
        }

        // 3. 值 / 区间校验
        if (FilterCondition.OP_BETWEEN.equals(operator)) {
            if (!isRangeComplete(c)) {
                log.warn("BETWEEN 条件缺少 minValue/maxValue，丢弃: field={}", c.getFieldName());
                return null;
            }
            if (!isParseable(c.getMinValue(), fieldType) || !isParseable(c.getMaxValue(), fieldType)) {
                log.warn("BETWEEN 条件边界值无法解析，丢弃: field={}, min={}, max={}",
                        c.getFieldName(), c.getMinValue(), c.getMaxValue());
                return null;
            }
        } else {
            if (!hasText(c.getValue())) {
                log.warn("过滤条件缺少 value，丢弃: field={}, operator={}", c.getFieldName(), operator);
                return null;
            }
            if (!isParseable(c.getValue(), fieldType)) {
                log.warn("过滤条件值无法解析，丢弃: field={}, operator={}, value={}",
                        c.getFieldName(), operator, c.getValue());
                return null;
            }
        }

        // 4. ENUM 值必须命中真实枚举集合
        if ("ENUM".equals(fieldType)) {
            Set<String> validValues = enumValueMap.get(c.getFieldName());
            if (validValues != null && !validValues.contains(c.getValue())) {
                log.debug("枚举值无效，丢弃: field={}, value={}, valid={}",
                        c.getFieldName(), c.getValue(), validValues);
                return null;
            }
        }

        return FilterCondition.builder()
                .fieldName(c.getFieldName())
                .fieldType(fieldType)
                .operator(operator)
                .value(trimToNull(c.getValue()))
                .minValue(trimToNull(c.getMinValue()))
                .maxValue(trimToNull(c.getMaxValue()))
                .build();
    }

    private boolean isRangeComplete(FilterCondition c) {
        return hasText(c.getMinValue()) && hasText(c.getMaxValue());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return value == null ? null : (value.isBlank() ? null : value.trim());
    }

    /** 值是否可解析为字段类型要求的形态。 */
    private boolean isParseable(String value, String fieldType) {
        if (!hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        try {
            if ("NUMBER".equals(fieldType)) {
                new BigDecimal(trimmed);
            } else if ("DATE".equals(fieldType)) {
                LocalDate.parse(trimmed);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 结构化字段定义，用于构建 LLM 提取 Prompt 中的字段描述
     * （方案文档 §4.4；按「同文件内」的方式实现，避免新增计划外文件）。
     */
    @Data
    public static class FieldDefinition {

        /** 字段名 */
        private String fieldName;

        /** 字段类型：NUMBER / DATE / ENUM / STRING */
        private String fieldType;

        /** 枚举值列表（仅 ENUM 类型有值） */
        private List<String> enumValues;
    }
}
