package org.example.agent_qr.etl.converter;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.etl.entity.FieldMapping;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 结构化数据转换器。
 * <p>
 * 将字段映射后的结构化数据转换为自然语言段落，
 * 按字段优先级排序并应用模板生成可读文本。
 * </p>
 * <p>
 * <b>批次 09 · 任务 9.7.3（问题 43）</b>：段落标题改为优先取表注释
 * （设计 §17.6 的 {@code title = metadata.table_comment || "数据记录"}）——
 * 即映射记录中的 {@code _table_comment}；无该字段时<b>回退到既有行为</b>
 * （{@code 【数据源名】}），保证既有数据源的输出不变。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class StructuredDataConverter {

    /** 表注释元数据键（设计 §17.6 的段落标题来源） */
    static final String META_TABLE_COMMENT = "_table_comment";

    /**
     * 将映射后的记录转换为自然语言段落。
     * <p>
     * 输出格式示例：
     * "【HR数据库】员工张三，所属部门为研发部，月薪15,000元，入职日期为2024年1月15日。"
     * </p>
     *
     * @param mappedRecord  字段映射后的标准记录（canonicalField → value，可含 {@code _table_comment} 元数据）
     * @param fieldMappings 字段映射配置列表（含模板、优先级等信息）
     * @param sourceName    数据源名称（表注释缺失时的段落标题）
     * @return 自然语言段落
     */
    public String convert(Map<String, Object> mappedRecord,
                          List<FieldMapping> fieldMappings,
                          String sourceName) {
        if (mappedRecord == null || mappedRecord.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 段落标题（任务 9.7.3）：表注释优先，缺失时回退到【数据源名】
        sb.append(resolveTitle(mappedRecord, sourceName));

        // 按优先级排序字段映射
        List<FieldMapping> sortedMappings = fieldMappings.stream()
                .filter(m -> FieldMapping.STATUS_ACTIVE.equals(m.getStatus()))
                .sorted(Comparator.comparingInt(FieldMapping::getPriority))
                .toList();

        int fieldCount = 0;
        for (FieldMapping mapping : sortedMappings) {
            Object value = mappedRecord.get(mapping.getCanonicalField());
            if (value == null || value.toString().isBlank()) {
                continue;
            }

            // 应用模板生成自然语言
            String text = applyTemplate(mapping, value);
            sb.append(text);

            fieldCount++;
            if (fieldCount < sortedMappings.size()) {
                sb.append("，");
            }
        }

        // 兜底：无 FieldMapping 配置时，遍历所有字段生成文本
        if (fieldCount == 0) {
            int i = 0;
            int totalFields = mappedRecord.size();
            for (Map.Entry<String, Object> entry : mappedRecord.entrySet()) {
                Object value = entry.getValue();
                // _table_comment 已作为标题输出，不再重复进入正文
                if (META_TABLE_COMMENT.equals(entry.getKey())
                        || value == null || value.toString().isBlank()) {
                    continue;
                }
                sb.append(entry.getKey()).append("为").append(value);
                i++;
                if (i < totalFields) {
                    sb.append("，");
                }
            }
        }

        sb.append("。");
        return sb.toString();
    }

    /**
     * 解析段落标题：表注释（{@code _table_comment}）优先，否则 {@code 【数据源名】}。
     *
     * @param mappedRecord 映射后的记录
     * @param sourceName   数据源名称
     * @return 形如 {@code 【表注释】} 或 {@code 【数据源名】}
     */
    private String resolveTitle(Map<String, Object> mappedRecord, String sourceName) {
        Object tableComment = mappedRecord.get(META_TABLE_COMMENT);
        if (tableComment != null && !tableComment.toString().isBlank()) {
            return "【" + tableComment.toString().trim() + "】";
        }
        return "【" + (sourceName != null ? sourceName : "数据源") + "】";
    }

    /**
     * 应用字段模板生成自然语言片段。
     * <p>
     * 模板格式："{字段中文名}为{值}{单位}"。
     * 若未配置模板，使用默认格式。
     * </p>
     */
    private String applyTemplate(FieldMapping mapping, Object value) {
        String displayName = mapping.getDisplayName() != null
                ? mapping.getDisplayName()
                : mapping.getCanonicalField();
        String unit = mapping.getUnit() != null ? mapping.getUnit() : "";

        if (mapping.getTemplate() != null && !mapping.getTemplate().isBlank()) {
            return mapping.getTemplate()
                    .replace("{字段中文名}", displayName)
                    .replace("{值}", value.toString())
                    .replace("{单位}", unit);
        }

        // 默认模板
        return displayName + "为" + value + unit;
    }
}
