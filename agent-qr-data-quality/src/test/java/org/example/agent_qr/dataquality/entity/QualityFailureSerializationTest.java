package org.example.agent_qr.dataquality.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QualityFailure} DTO 的序列化/反序列化契约测试
 * （批次 10 · 任务 10.4.2 / 10.4.3，问题 26）。
 * <p>
 * 该 DTO 随 {@code quality_report.failures} JSON 列持久化（MyBatis-Plus
 * {@code JacksonTypeHandler}），因此 JSON 形态是它的持久化契约：
 * </p>
 * <ul>
 *   <li>新写入的明细必须带 {@code recordIndices}（具体失败记录索引）与 {@code recordCount}（总数）；</li>
 *   <li>必须能读取<b>旧版 JSON</b>（只有 {@code recordIndex}）——库中已有历史报告；</li>
 *   <li>索引列表有上限，防止高基数失败明细撑爆 JSON 列。</li>
 * </ul>
 *
 * @author agent-qr
 */
class QualityFailureSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("★ 序列化/反序列化往返正确（保留 recordIndices 与 recordCount）")
    void roundTrip_shouldKeepRecordIndicesAndCount() throws Exception {
        QualityFailure failure = new QualityFailure("完整性", 3, "内容字段为空");
        failure.setRuleType("completeness");
        for (int i = 4; i < 7; i++) {
            failure.addRecordIndex(i);
        }

        String json = objectMapper.writeValueAsString(failure);
        QualityFailure parsed = objectMapper.readValue(json, QualityFailure.class);

        assertThat(parsed.getRuleName()).isEqualTo("完整性");
        assertThat(parsed.getRuleType()).isEqualTo("completeness");
        assertThat(parsed.getReason()).isEqualTo("内容字段为空");
        assertThat(parsed.getRecordIndex()).isEqualTo(3);
        assertThat(parsed.getRecordIndices()).containsExactly(3, 4, 5, 6);
        assertThat(parsed.getRecordCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("★ 兼容旧版 JSON（只有 recordIndex，无 recordIndices/recordCount）")
    void deserialize_shouldAcceptLegacyJson() throws Exception {
        String legacyJson = """
                {"reason":"内容字段为空（检查字段: [content] 均为空）","ruleName":"完整性","recordIndex":7}
                """;

        QualityFailure parsed = objectMapper.readValue(legacyJson, QualityFailure.class);

        assertThat(parsed.getRuleName()).isEqualTo("完整性");
        assertThat(parsed.getRecordIndex()).isEqualTo(7);
        assertThat(parsed.getRecordIndices()).isNotNull();
        assertThat(parsed.getRuleType()).isNull();
    }

    @Test
    @DisplayName("未知字段不导致反序列化失败（向前兼容）")
    void deserialize_shouldIgnoreUnknownFields() throws Exception {
        QualityFailure parsed = objectMapper.readValue(
                "{\"ruleName\":\"格式\",\"reason\":\"x\",\"futureField\":123}",
                QualityFailure.class);

        assertThat(parsed.getRuleName()).isEqualTo("格式");
    }

    @Test
    @DisplayName("索引列表上限：超出部分只计入 recordCount（体积保护）")
    void addRecordIndex_shouldCapIndexList() {
        QualityFailure failure = new QualityFailure("完整性", 0, "内容为空");

        for (int i = 1; i < QualityFailure.MAX_RECORD_INDICES + 500; i++) {
            failure.addRecordIndex(i);
        }

        assertThat(failure.getRecordIndices()).hasSize(QualityFailure.MAX_RECORD_INDICES);
        assertThat(failure.getRecordCount()).isEqualTo(QualityFailure.MAX_RECORD_INDICES + 500);
        assertThat(failure.getRecordIndex()).isZero();
    }

    @Test
    @DisplayName("反序列化后仍可继续追加索引（列表可为空但不可为 null）")
    void addRecordIndex_shouldWorkAfterDeserialization() throws Exception {
        QualityFailure parsed = objectMapper.readValue(
                "{\"ruleName\":\"格式\",\"reason\":\"x\",\"recordIndices\":null,\"recordCount\":0}",
                QualityFailure.class);

        parsed.addRecordIndex(5);

        assertThat(parsed.getRecordIndices()).containsExactly(5);
        assertThat(parsed.getRecordCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ R43：detail 参与往返；普通明细的 omittedKindCount 不写进 JSON（null 语义）")
    void roundTrip_shouldKeepDetail_andOmitOmittedKindCountOnNormalEntries() throws Exception {
        QualityFailure failure = new QualityFailure("长度", 0, "字段长度小于最小长度 10");
        failure.setRuleType("length");
        failure.setDetail("字段 'name' 长度 1");

        String json = objectMapper.writeValueAsString(failure);
        QualityFailure parsed = objectMapper.readValue(json, QualityFailure.class);

        assertThat(parsed.getReason())
                .as("reason 是模板（不含具体取值）")
                .isEqualTo("字段长度小于最小长度 10");
        assertThat(parsed.getDetail()).isEqualTo("字段 'name' 长度 1");
        assertThat(json)
                .as("omittedKindCount 仅截断汇总条使用，普通明细为 null 不得出现")
                .doesNotContain("omittedKindCount");
    }

    @Test
    @DisplayName("★ R43②：截断汇总条的 JSON 同时带 recordCount（失败记录数）与 omittedKindCount（种类数）")
    void serialize_truncationEntry_shouldCarryBothCounts() throws Exception {
        QualityFailure truncated = new QualityFailure("(明细截断)", 0, "明细超过上限");
        truncated.setRuleType("truncated");
        truncated.setRecordCount(100);
        truncated.setOmittedKindCount(50);

        String json = objectMapper.writeValueAsString(truncated);
        QualityFailure parsed = objectMapper.readValue(json, QualityFailure.class);

        assertThat(parsed.getRecordCount()).isEqualTo(100);
        assertThat(parsed.getOmittedKindCount()).isEqualTo(50);
        assertThat(parsed.getRuleType()).isEqualTo("truncated");
    }

    @Test
    @DisplayName("null 字段不写进 JSON（@JsonInclude(NON_NULL)）")
    void serialize_shouldOmitNulls() throws Exception {
        QualityFailure failure = new QualityFailure("完整性", 0, "x");
        failure.setRuleType(null);

        String json = objectMapper.writeValueAsString(failure);

        assertThat(json).doesNotContain("ruleType");
        assertThat(objectMapper.readValue(json, QualityFailure.class).getRecordIndices())
                .containsExactly(0);
    }
}
