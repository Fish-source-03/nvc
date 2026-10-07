package org.example.agent_qr.etl.normalizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.etl.converter.StructuredDataConverter;
import org.example.agent_qr.etl.engine.FieldMappingEngine;
import org.example.agent_qr.etl.entity.CanonicalRecord;
import org.example.agent_qr.etl.enums.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ETL 标准化器的分类路径测试（批次 09 · 任务 9.7，问题 43）。
 * <p>
 * <b>拦截的缺陷</b>：
 * </p>
 * <ol>
 *   <li>半结构化路径用 {@code rawRecord.toString()}——<b>未映射</b>的记录 +
 *       {@code {name=张三}} 这种<b>非法 JSON</b>；</li>
 *   <li>非结构化路径硬编码 {@code _content/content/text}，与质检侧可配置的
 *       {@code content_fields} 不一致 → "质检通过但产出空 canonicalText"；</li>
 *   <li>设计 §17.6 的 {@code _table_comment}（段落标题）全仓无实现。</li>
 * </ol>
 * <p>
 * 类分类逻辑（{@code classify}）未改动：仍按"是否含 {@code _file_type}/{@code _file_key}"
 * 与"是否含嵌套结构"判定，不读 {@code config.sourceType}——该偏差按任务要求记录在批次报告中，
 * 由批次 11 统一回填设计文档（不在此处改代码）。
 * </p>
 *
 * @author agent-qr
 */
class DataNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DataNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new DataNormalizer();
        ReflectionTestUtils.setField(normalizer, "fieldMappingEngine", new FieldMappingEngine());
        ReflectionTestUtils.setField(normalizer, "structuredDataConverter", new StructuredDataConverter());
        ReflectionTestUtils.setField(normalizer, "objectMapper", objectMapper);
        // 与质检侧 globalContentFields 的默认值一致
        ReflectionTestUtils.setField(normalizer, "globalContentFields", "content,text,_content");
    }

    // ==================== 9.7.1 半结构化路径 ====================

    @Test
    @DisplayName("★ 半结构化记录的 canonicalText 是合法 JSON 且字段已映射（问题 43 的原始缺陷）")
    void normalize_semiStructured_shouldProduceValidJsonWithMappedFields() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("emp_name", "张三");
        raw.put("age", 25);
        // 嵌套结构 → 触发 SEMI_STRUCTURED 分类
        raw.put("extra", Map.of("level", "P6"));

        DataSourceConfig config = config("""
                [
                  {"canonicalField":"employeeName","sourceField":"emp_name","displayName":"员工姓名","priority":1,"status":"ACTIVE"},
                  {"canonicalField":"age","sourceField":"age","displayName":"年龄","priority":2,"status":"ACTIVE"},
                  {"canonicalField":"extra","sourceField":"extra","priority":3,"status":"ACTIVE"}
                ]
                """, null);

        CanonicalRecord record = normalizer.normalize(List.of(raw), config, "batch-1").get(0);

        assertThat(record.getDataType()).isEqualTo(DataType.SEMI_STRUCTURED);
        String text = record.getCanonicalText();

        // 必须可被 JSON 解析器解析（Map.toString() 会在此抛异常）
        Map<String, Object> parsed = parseJson(text);
        assertThat(parsed)
                .as("字段映射后的键（emp_name → employeeName）必须生效")
                .containsEntry("employeeName", "张三")
                .containsEntry("age", 25);
        assertThat(parsed.get("extra")).as("嵌套结构必须保留").isEqualTo(Map.of("level", "P6"));
        assertThat(text)
                .as("Map.toString() 的产物形如 {emp_name=张三, age=25}，不是合法 JSON")
                .doesNotContain("=")
                .contains("\"employeeName\":\"张三\"");
        assertThat(record.getMetadata()).containsEntry("employeeName", "张三");
    }

    @Test
    @DisplayName("★ 半结构化：嵌套 Map / List 结构被完整保留")
    void normalize_semiStructured_shouldKeepNestedStructures() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("title", "订单");
        raw.put("items", List.of(Map.of("sku", "A1", "qty", 2), Map.of("sku", "B2", "qty", 1)));

        CanonicalRecord record = normalizer.normalize(List.of(raw), config(null, null), "batch-2").get(0);

        assertThat(record.getDataType()).isEqualTo(DataType.SEMI_STRUCTURED);
        Map<String, Object> parsed = parseJson(record.getCanonicalText());
        assertThat((List<?>) parsed.get("items")).hasSize(2);
        assertThat((Map<String, Object>) ((List<?>) parsed.get("items")).get(0)).containsEntry("sku", "A1");
    }

    @Test
    @DisplayName("半结构化：无字段映射配置时原样序列化（回归）")
    void normalize_semiStructured_withoutMapping_shouldSerializeRawKeys() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "李四");
        raw.put("tags", List.of("a", "b"));

        CanonicalRecord record = normalizer.normalize(List.of(raw), config(null, null), "batch-3").get(0);

        Map<String, Object> parsed = parseJson(record.getCanonicalText());
        assertThat(parsed).containsEntry("name", "李四");
    }

    // ==================== 9.7.2 非结构化路径与质检对齐 ====================

    @Test
    @DisplayName("★ 质检通过的非结构化记录不产出空 canonicalText（content_fields=title,desc）")
    void normalize_unstructured_shouldHonorConfiguredContentFields() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("_file_type", "pdf");
        raw.put("_file_key", "docs/a.pdf");
        raw.put("title", "年度报告");
        raw.put("desc", "2025 年度经营情况说明");

        CanonicalRecord record = normalizer.normalize(
                List.of(raw), config(null, "title,desc"), "batch-4").get(0);

        assertThat(record.getDataType()).isEqualTo(DataType.UNSTRUCTURED);
        assertThat(record.getCanonicalText())
                .as("质检按 title,desc 判通过 → ETL 必须从同一组字段取到正文，否则产生空内容切片")
                .isEqualTo("年度报告");
    }

    @Test
    @DisplayName("★ 配置的 content_fields 全部为空 → 不做隐式兜底（与质检判定一致）")
    void normalize_unstructured_shouldStayEmpty_whenConfiguredFieldsAreBlank() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("_file_type", "pdf");
        raw.put("title", "   ");
        raw.put("desc", "");
        // 未被配置的字段即使有内容也不应被误用
        raw.put("content", "实际内容");

        CanonicalRecord record = normalizer.normalize(
                List.of(raw), config(null, "title,desc"), "batch-5").get(0);

        assertThat(record.getCanonicalText()).isEmpty();
    }

    @Test
    @DisplayName("未配置 content_fields → 回退全局默认 content,text,_content（回归）")
    void normalize_unstructured_shouldFallbackToGlobalDefaults() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("_file_type", "txt");
        raw.put("_content", "S3 文件正文");

        CanonicalRecord record = normalizer.normalize(List.of(raw), config(null, null), "batch-6").get(0);

        assertThat(record.getCanonicalText()).isEqualTo("S3 文件正文");
    }

    @Test
    @DisplayName("content 与 text 均在时按默认顺序取 content（与质检的字段顺序同源）")
    void normalize_unstructured_shouldPickFirstNonBlankByOrder() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("_file_type", "txt");
        raw.put("text", "text 内容");
        raw.put("content", "content 内容");

        CanonicalRecord record = normalizer.normalize(List.of(raw), config(null, null), "batch-7").get(0);

        assertThat(record.getCanonicalText()).isEqualTo("content 内容");
    }

    // ==================== 9.7.3 _table_comment ====================

    @Test
    @DisplayName("★ 有 _table_comment 时作为段落标题（设计 §17.6）")
    void normalize_structured_shouldUseTableCommentAsTitle() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("emp_name", "王五");
        raw.put("_table_comment", "员工信息表");

        CanonicalRecord record = normalizer.normalize(List.of(raw), config("""
                [{"canonicalField":"employeeName","sourceField":"emp_name","displayName":"员工姓名","priority":1,"status":"ACTIVE"}]
                """, null), "batch-8").get(0);

        assertThat(record.getDataType()).isEqualTo(DataType.STRUCTURED);
        assertThat(record.getCanonicalText())
                .startsWith("【员工信息表】")
                .contains("员工姓名");
    }

    @Test
    @DisplayName("无 _table_comment 时回退既有标题格式【数据源名】（回归）")
    void normalize_structured_shouldFallbackToSourceName() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("emp_name", "王五");

        CanonicalRecord record = normalizer.normalize(List.of(raw), config("""
                [{"canonicalField":"employeeName","sourceField":"emp_name","displayName":"员工姓名","priority":1,"status":"ACTIVE"}]
                """, null), "batch-9").get(0);

        assertThat(record.getCanonicalText()).startsWith("【测试数据源】");
    }

    // ==================== 9.7.1 回归：结构化路径 ====================

    @Test
    @DisplayName("★ 结构化记录处理结果与修复前一致（回归：标题 + 模板 + 优先级）")
    void normalize_structured_shouldKeepLegacyOutput() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("emp_name", "张三");
        raw.put("dept", "D01");

        String mapping = """
                [
                  {"canonicalField":"employeeName","sourceField":"emp_name","displayName":"员工姓名","priority":1,"status":"ACTIVE"},
                  {"canonicalField":"dept","sourceField":"dept","displayName":"所属部门","priority":2,
                   "dictMapping":{"D01":"研发部"},"status":"ACTIVE"}
                ]
                """;

        CanonicalRecord record = normalizer.normalize(List.of(raw), config(mapping, null), "batch-10").get(0);

        assertThat(record.getDataType()).isEqualTo(DataType.STRUCTURED);
        assertThat(record.getCanonicalText())
                .as("既有输出格式不变：字典翻译、模板、优先级排序")
                .isEqualTo("【测试数据源】员工姓名为张三，所属部门为研发部。");
        assertThat(record.getMetadata()).containsEntry("dept", "研发部");
    }

    @Test
    @DisplayName("空输入 / null 输入返回空列表（边界不变）")
    void normalize_shouldHandleEmptyInput() {
        assertThat(normalizer.normalize(null, config(null, null), "b")).isEmpty();
        assertThat(normalizer.normalize(List.of(), config(null, null), "b")).isEmpty();
    }

    // ==================== 辅助 ====================

    private Map<String, Object> parseJson(String text) throws Exception {
        return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
        });
    }

    private static DataSourceConfig config(String fieldMappingJson, String contentFields) {
        DataSourceConfig config = new DataSourceConfig();
        config.setId(1L);
        config.setSourceName("测试数据源");
        config.setDomain("HR");
        config.setFieldMapping(fieldMappingJson);
        config.setContentFields(contentFields);
        return config;
    }
}
