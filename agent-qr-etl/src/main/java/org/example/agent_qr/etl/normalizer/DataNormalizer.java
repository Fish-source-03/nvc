package org.example.agent_qr.etl.normalizer;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.etl.entity.CanonicalRecord;
import org.example.agent_qr.etl.entity.FieldMapping;
import org.example.agent_qr.etl.engine.FieldMappingEngine;
import org.example.agent_qr.etl.enums.DataType;
import org.example.agent_qr.etl.converter.StructuredDataConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 数据标准化器 — ETL 管道入口。
 * <p>
 * 对原始数据执行三步标准化流程：
 * <ol>
 *   <li>分类：根据数据类型特征判断 STRUCTURED/SEMI_STRUCTURED/UNSTRUCTURED</li>
 *   <li>映射：通过 FieldMappingEngine 将源字段映射为标准字段</li>
 *   <li>转换：通过 StructuredDataConverter 生成自然语言文本</li>
 * </ol>
 * 最终输出 CanonicalRecord 列表。
 * </p>
 *
 * <h3>批次 09 · 任务 9.7（问题 43）的三处修复</h3>
 * <ol>
 *   <li><b>9.7.1 半结构化路径</b>：原实现 {@code canonicalText = rawRecord.toString()}——
 *       用的是<b>未映射</b>的原始记录，且 {@code Map.toString()} 产出 {@code {name=张三}}
 *       这种<b>非法 JSON</b>。现改为 {@code extractSemiStructured(mappedRecord)} +
 *       Jackson 标准序列化（保留嵌套结构）；</li>
 *   <li><b>9.7.2 非结构化路径与质检对齐</b>：原实现硬编码 {@code _content → content → text}，
 *       而质检侧 {@code CompletenessRule} 的字段列表可由数据源 {@code content_fields} 覆盖。
 *       两者不一致时会出现"质检判通过、ETL 产出空 canonicalText"→ 空内容切片。
 *       现两侧读取同一配置项（{@code agent-qr.data-quality.content-fields}）。
 *       <p>
 *       <b>R38①（批次 11）——"字段顺序变化"确认为有意变更</b>：顺序不再由代码硬编码，
 *       而由该配置项决定（P2 生效值 {@code content,text,_content}，见
 *       {@code application-p2.yml}）。于是记录同时含 {@code _content} 与 {@code content} 时
 *       取 {@code content}（修复前 ETL 侧取 {@code _content}）。这是
 *       "ETL 与质检同源、优先级由配置唯一决定"的<b>有意</b>结果，
 *       不是疏忽；行为由 {@code DataNormalizerTest} 锁定，设计文档 §17.5 同步记录。
 *       </p></li>
 *   <li><b>9.7.3 {@code _table_comment}</b>：设计 §17.6 要求以表注释作为段落标题，
 *       原先该字段全仓无实现且会被字段映射丢弃（映射只保留已配置的 canonicalField）。
 *       现在显式保留该元数据键，由 {@link StructuredDataConverter} 用作标题。</li>
 * </ol>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class DataNormalizer {

    /** 表注释元数据键（设计 §17.6 的段落标题来源） */
    static final String META_TABLE_COMMENT = "_table_comment";

    /**
     * 全局默认内容字段列表 —— 与质检侧 {@code CompletenessRule} 读取<b>同一个配置项</b>
     * （{@code agent-qr.data-quality.content-fields}）。
     * <p>
     * 两侧必须同源：质检用它判断"内容非空"，ETL 用它提取正文；
     * 若各用各的清单，就会出现"质检通过但 ETL 提取为空"的组合缺陷（问题 43 依据 5）。
     * </p>
     * <p>
     * R38①：<b>顺序也由本配置决定</b>——P2 生效值 {@code content,text,_content}，
     * 故 {@code content} 与 {@code _content} 同时存在时取前者（有意变更，与质检侧一致）。
     * </p>
     */
    @Value("${agent-qr.data-quality.content-fields:content,text,_content}")
    private String globalContentFields = "content,text,_content";

    @Autowired
    private FieldMappingEngine fieldMappingEngine;

    @Autowired
    private StructuredDataConverter structuredDataConverter;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 对原始数据执行标准化处理。
     *
     * @param rawData 原始数据记录列表
     * @param config  数据源配置（含字段映射）
     * @param batchId 同步批次 ID
     * @return 标准化记录列表
     */
    @SuppressWarnings("unchecked")
    public List<CanonicalRecord> normalize(List<Map<String, Object>> rawData,
                                           DataSourceConfig config,
                                           String batchId) {
        List<CanonicalRecord> records = new ArrayList<>();

        if (rawData == null || rawData.isEmpty()) {
            return records;
        }

        // 解析字段映射配置
        List<FieldMapping> fieldMappings = parseFieldMappings(config.getFieldMapping());
        String sourceName = config.getSourceName();
        String domain = config.getDomain();

        for (Map<String, Object> rawRecord : rawData) {
            // 1. 分类
            DataType dataType = classify(rawRecord);

            // 2. 字段映射
            Map<String, Object> mappedRecord = fieldMappingEngine.apply(rawRecord, fieldMappings);

            // 2.5 保留表注释元数据（任务 9.7.3）：字段映射只保留已配置的 canonicalField，
            //     未映射的键会被丢弃 —— 若不在这一步保留，_table_comment 永远到不了转换器。
            carryTableComment(rawRecord, mappedRecord);

            // 3. 生成标准化文本
            String canonicalText;
            if (dataType == DataType.STRUCTURED) {
                canonicalText = structuredDataConverter.convert(mappedRecord, fieldMappings, sourceName);
            } else if (dataType == DataType.UNSTRUCTURED) {
                // 非结构化：按与质检侧同源的 content_fields 提取正文（任务 9.7.2）
                canonicalText = extractUnstructuredText(rawRecord, config);
            } else {
                // 半结构化：映射后的记录 + 标准 JSON 序列化（任务 9.7.1）
                canonicalText = extractSemiStructured(mappedRecord);
            }

            CanonicalRecord record = CanonicalRecord.builder()
                    .sourceSystem(sourceName)
                    .domain(domain)
                    .dataType(dataType)
                    .canonicalText(canonicalText)
                    .metadata(mappedRecord)
                    .datasourceId(config.getId())
                    .syncBatchId(batchId)
                    .build();

            records.add(record);
        }

        log.info("数据标准化完成: sourceName={}, totalRecords={}, batchId={}",
                sourceName, records.size(), batchId);
        return records;
    }

    /**
     * 分类：根据记录内容判断数据类型。
     * <p>
     * 含 _file_type 字段 → UNSTRUCTURED<br>
     * JDBC 数据源（多字段）→ STRUCTURED<br>
     * 含嵌套结构 → SEMI_STRUCTURED
     * </p>
     */
    private DataType classify(Map<String, Object> record) {
        // 文件类型标记 → 非结构化
        if (record.containsKey("_file_type") || record.containsKey("_file_key")) {
            return DataType.UNSTRUCTURED;
        }

        // 检查是否有嵌套对象（JSON）
        for (Object value : record.values()) {
            if (value instanceof Map || value instanceof List) {
                return DataType.SEMI_STRUCTURED;
            }
        }

        // 默认结构化
        return DataType.STRUCTURED;
    }

    /**
     * 从非结构化记录中提取文本内容（批次 09 · 任务 9.7.2）。
     * <p>
     * 字段来源与质检侧 {@code CompletenessRule} <b>完全同源</b>：
     * 优先数据源级 {@code DataSourceConfig.contentFields}，未配置时回退到全局默认值
     * （同一配置项 {@code agent-qr.data-quality.content-fields}）。
     * 修复前此处硬编码 {@code _content → content → text}——若某数据源配置
     * {@code content_fields=title,desc}，质检判"通过"的记录在这里会提取到空串，
     * 进而写入<b>空内容切片</b>污染向量库。
     * </p>
     *
     * @param record 原始记录（质检使用的也是这一层记录）
     * @param config 数据源配置（提供 content_fields）
     * @return 提取到的正文；全部字段为空时返回空串
     */
    private String extractUnstructuredText(Map<String, Object> record, DataSourceConfig config) {
        for (String field : resolveContentFields(config)) {
            Object value = record.get(field);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }

    /**
     * 解析生效的内容字段列表（与 {@code CompletenessRule} 同一优先级规则）。
     *
     * @param config 数据源配置（可为 null）
     * @return 字段名列表（按配置顺序）
     */
    private List<String> resolveContentFields(DataSourceConfig config) {
        String configured = config == null ? null : config.getContentFields();
        String effective = (configured == null || configured.isBlank()) ? globalContentFields : configured;
        return Arrays.stream(effective.split(","))
                .map(String::trim)
                .filter(field -> !field.isEmpty())
                .toList();
    }

    /**
     * 半结构化记录的文本表示（设计 §8.9.1 的 {@code extractSemiStructured(mapped)}，任务 9.7.1）。
     * <p>
     * 用 Jackson 序列化为<b>标准 JSON</b>并保留嵌套结构。修复前的
     * {@code rawRecord.toString()} 有两处错：
     * </p>
     * <ol>
     *   <li>用的是<b>未映射</b>的原始记录——字段映射（字典翻译、日期中文化、金额格式化）
     *       全部被跳过，源字段名与原始编码值直接进入向量文本；</li>
     *   <li>{@code Map.toString()} 产出 {@code {name=张三, age=25}}——<b>不是合法 JSON</b>，
     *       无法被解析、语义质量差。</li>
     * </ol>
     * <p>
     * 包级可见：便于同包测试直接校验"产出的文本是合法 JSON"。
     * </p>
     *
     * @param mappedRecord 字段映射后的记录
     * @return 标准 JSON 文本；入参为空时返回空串；
     *         序列化异常时记 ERROR 并返回 {@code {}}（保证"半结构化产出永远是合法 JSON"这一不变量，
     *         不退回 {@code Map.toString()}）
     */
    String extractSemiStructured(Map<String, Object> mappedRecord) {
        if (mappedRecord == null || mappedRecord.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(mappedRecord);
        } catch (JsonProcessingException e) {
            log.error("半结构化记录 JSON 序列化失败，产出空 JSON 对象（不退回 Map.toString 以免产生非法 JSON）: {}",
                    e.getMessage());
            return "{}";
        }
    }

    /**
     * 把 {@code _table_comment} 元数据保留到映射后的记录中（任务 9.7.3）。
     * <p>设计 §17.6 要求以表注释作为段落标题；该键属元数据而非字段，映射器不会保留它。</p>
     *
     * @param rawRecord    原始记录
     * @param mappedRecord 映射后的记录（原地补充）
     */
    private void carryTableComment(Map<String, Object> rawRecord, Map<String, Object> mappedRecord) {
        if (mappedRecord == null || mappedRecord.containsKey(META_TABLE_COMMENT)) {
            return;
        }
        Object tableComment = rawRecord.get(META_TABLE_COMMENT);
        if (tableComment != null) {
            mappedRecord.put(META_TABLE_COMMENT, tableComment);
        }
    }

    /**
     * 解析字段映射配置 JSON。
     */
    @SuppressWarnings("unchecked")
    private List<FieldMapping> parseFieldMappings(String fieldMappingJson) {
        if (fieldMappingJson == null || fieldMappingJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rawList = objectMapper.readValue(fieldMappingJson, List.class);
            List<FieldMapping> mappings = new ArrayList<>();
            for (Map<String, Object> raw : rawList) {
                FieldMapping fm = FieldMapping.builder()
                        .canonicalField((String) raw.get("canonicalField"))
                        .sourceField((String) raw.get("sourceField"))
                        .displayName((String) raw.get("displayName"))
                        .template((String) raw.get("template"))
                        .unit((String) raw.get("unit"))
                        .transformRule((String) raw.get("transformRule"))
                        .dictMapping((Map<String, String>) raw.get("dictMapping"))
                        .priority(raw.get("priority") != null ? ((Number) raw.get("priority")).intValue() : 99)
                        .status((String) raw.getOrDefault("status", FieldMapping.STATUS_ACTIVE))
                        .build();
                mappings.add(fm);
            }
            return mappings;
        } catch (Exception e) {
            log.error("字段映射配置解析失败: {}", e.getMessage());
            return List.of();
        }
    }
}
