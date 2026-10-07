package org.example.agent_qr.dataquality.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QualityReport} 接口序列化契约测试（批次 10 · 任务 10.4.4，问题 26）。
 * <p>
 * 修复的缺陷：{@code failedIndices} 是<b>内存过滤用的中间状态</b>，
 * 未加 {@code @JsonIgnore} 时会随报告详情接口暴露内部索引集合。
 * 本测试锁定"响应中不含 failedIndices，但含 failures（及明细内的 recordIndices）"。
 * </p>
 * <p>
 * 注意：{@code failedIndices} 本身不能删除——{@code DataQualityService.filterPassedData}
 * 依赖它过滤被阻断的数据，因此这里仅断言它不出现在 JSON 中。
 * </p>
 *
 * @author agent-qr
 */
class QualityReportSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("★ failedIndices 不随接口暴露，failures 明细正常序列化（含 recordIndices）")
    void serialize_shouldHideFailedIndices_butKeepFailures() throws Exception {
        QualityFailure failure = new QualityFailure("完整性", 1, "内容字段为空");
        failure.setRuleType("completeness");
        failure.addRecordIndex(2);

        QualityReport report = new QualityReport("batch-1", 3, 1, 2, 1.0 / 3, false, List.of(failure));
        report.setFailedIndices(Set.of(1, 2));
        report.setCheckTime(LocalDateTime.now());

        String json = objectMapper.writeValueAsString(report);

        assertThat(json)
                .as("内部过滤用的索引集合不得暴露")
                .doesNotContain("failedIndices");
        assertThat(json).contains("failures");
        assertThat(json).contains("recordIndices");

        QualityReport parsed = objectMapper.readValue(json, QualityReport.class);
        assertThat(parsed.getFailures()).hasSize(1);
        assertThat(parsed.getFailures().get(0).getRecordIndices()).containsExactly(1, 2);
        assertThat(parsed.getFailures().get(0).getRecordCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("failures 为空时序列化为空数组（读取端不拿到 null）")
    void serialize_shouldWriteEmptyFailuresArray() throws Exception {
        QualityReport report = new QualityReport("batch-2", 1, 1, 0, 1.0, false, List.of());

        String json = objectMapper.writeValueAsString(report);
        QualityReport parsed = objectMapper.readValue(json, QualityReport.class);

        assertThat(json).contains("\"failures\":[]");
        assertThat(parsed.getFailures()).isEmpty();
    }
}
