package org.example.agent_qr.knowledge.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 软删过滤的手写 SQL 审计测试（批次 08 · 任务 8.1，问题 27）。
 * <p>
 * <b>拦截的缺陷</b>：{@code ChunkMapper.selectByDocumentId} 是手写 {@code @Select}，
 * 而 MyBatis-Plus 的 {@code @TableLogic} <b>只对自动生成的 SQL 生效</b>——
 * 手写 SQL 不追加 {@code deleted = 0} 时会把已软删切片一并返回。
 * 该坑"看起来对、只有查询时才暴露"，且极易在新增查询方法时复发，
 * 因此这里用<b>反射扫描</b>把规则固化下来：只要本接口新增一条未带软删条件的
 * {@code @Select}，本测试即失败。
 * </p>
 * <p>
 * <b>为什么是 @Select</b>：SELECT 是"读"的入口，漏过滤 = 把已删除数据泄漏给调用方
 * （问题 27 的实际后果）。按主键更新的 {@code @Update}（如 {@code updateStatus}）
 * 与物理删除 {@code @Delete}（如 {@code deleteByDocumentId}）不属于本规则范围。
 * </p>
 *
 * @author agent-qr
 */
class ChunkMapperSoftDeleteSqlTest {

    /**
     * 文档 Mapper 中经审计确认缺少软删条件、但<b>不在本批次可改文件范围内</b>的语句。
     * <p>
     * 批次 08 · 任务 8.1.2 要求对 {@code ChunkMapper} / {@code DocumentMapper} 的
     * 全部手写 {@code @Select} 做同类排查。审计结果：
     * </p>
     * <ul>
     *   <li>{@code ChunkMapper} —— 已修复（{@code selectByDocumentId} +
     *       {@code selectChromaIdsByDocumentId}），其余语句本已带条件，无例外；</li>
     *   <li>{@code DocumentMapper.selectTypeDistribution} ——
     *       {@code SELECT file_type, COUNT(*) FROM kb_document GROUP BY file_type}
     *       统计的是<b>含已软删文档</b>的全表分布，属同类缺陷；
     *       但 {@code DocumentMapper} 不在批次 08「涉及文件」清单内，
     *       故按"发现范围外文件需停下报告"的要求<b>未修改</b>，仅在此登记，
     *       使本测试在<b>新增</b>未过滤查询时仍能报警。</li>
     * </ul>
     */
    private static final Set<String> DOCUMENT_MAPPER_KNOWN_GAPS =
            Set.of("selectTypeDistribution");

    @Test
    @DisplayName("★ ChunkMapper 全部手写 @Select 都必须带软删条件 deleted = 0")
    void everyHandWrittenSelectInChunkMapper_mustFilterSoftDeletedRows() {
        Map<String, String> gaps = selectsLackingSoftDeleteCondition(ChunkMapper.class, Set.of());

        assertThat(gaps)
                .as("以下手写 @Select 未过滤已软删切片（@TableLogic 对手写 SQL 不生效）")
                .isEmpty();
    }

    @Test
    @DisplayName("★ selectByDocumentId 必须含 deleted = 0（问题 27 的原始缺陷点）")
    void selectByDocumentId_mustFilterSoftDeletedRows() {
        String sql = sqlOf(ChunkMapper.class, "selectByDocumentId");

        assertThat(sql)
                .as("GET /api/knowledge/documents/{id}/chunks 依赖该查询，缺过滤会把已删文档的切片返回给用户")
                .contains("document_id = #{documentId}")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("★ selectChromaIdsByDocumentId 必须含 deleted = 0（8.1.2 同类排查）")
    void selectChromaIdsByDocumentId_mustFilterSoftDeletedRows() {
        assertThat(sqlOf(ChunkMapper.class, "selectChromaIdsByDocumentId")).contains("deleted = 0");
    }

    @Test
    @DisplayName("★ 既有过滤语义不得被本次修复改反（回归护栏）")
    void existingFilteringQueries_mustKeepTheirSoftDeleteCondition() {
        assertThat(sqlOf(ChunkMapper.class, "selectAllReadyChunks"))
                .as("任务 8.3 明令不得修改 selectAllReadyChunks")
                .contains("deleted = 0")
                .contains("status = 'READY'");
        assertThat(sqlOf(ChunkMapper.class, "selectReadyChunksPaged")).contains("deleted = 0");
        assertThat(sqlOf(ChunkMapper.class, "selectByDatasourceId")).contains("deleted = 0");
    }

    @Test
    @DisplayName("★ 新增的 selectLiveChunkIds（任务 8.3）必须只返回存活切片，且不改动 selectByDocumentId")
    void selectLiveChunkIds_mustFilterSoftDeletedRows() {
        assertThat(sqlOf(ChunkMapper.class, "selectLiveChunkIds"))
                .contains("deleted = 0")
                .contains("id IN");

        // 回退风险护栏：任务 8.3 不得修改 selectByDocumentId / selectAllReadyChunks
        assertThat(sqlOf(ChunkMapper.class, "selectByDocumentId"))
                .as("selectByDocumentId 一旦被 8.3 改回（去掉 deleted=0），孤儿扫描会回退 8.1 的修复")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("★ DocumentMapper 同类排查：新增未过滤的 @Select 会使本测试失败")
    void documentMapper_selects_mustNotAddNewUnfilteredStatements() {
        Map<String, String> gaps =
                selectsLackingSoftDeleteCondition(DocumentMapper.class, DOCUMENT_MAPPER_KNOWN_GAPS);

        assertThat(gaps)
                .as("DocumentMapper 出现新的未过滤 @Select（已登记的范围外历史缺口：%s）",
                        DOCUMENT_MAPPER_KNOWN_GAPS)
                .isEmpty();
    }

    // ==================== 反射辅助 ====================

    /**
     * 收集接口中"手写 SQL 未出现 deleted 字样"的 {@code @Select} 方法。
     *
     * @param mapper       目标 Mapper 接口
     * @param knownGaps    已知缺口（方法名），登记后不计入失败
     * @return 方法名 → SQL 文本
     */
    private static Map<String, String> selectsLackingSoftDeleteCondition(Class<?> mapper, Set<String> knownGaps) {
        Map<String, String> gaps = new LinkedHashMap<>();
        for (Method method : mapper.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null || knownGaps.contains(method.getName())) {
                continue;
            }
            String sql = String.join(" ", select.value());
            if (!sql.toLowerCase().contains("deleted")) {
                gaps.put(method.getName(), sql);
            }
        }
        return gaps;
    }

    private static String sqlOf(Class<?> mapper, String methodName) {
        for (Method method : mapper.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getAnnotation(Select.class) != null) {
                return String.join(" ", method.getAnnotation(Select.class).value());
            }
        }
        throw new AssertionError("未找到带 @Select 的方法: " + mapper.getSimpleName() + "#" + methodName);
    }

    /** 供文档用：列出接口中的全部 {@code @Select} 方法名（审计留痕） */
    @SuppressWarnings("unused")
    private static List<String> selectMethodNames(Class<?> mapper) {
        List<String> names = new ArrayList<>();
        for (Method method : mapper.getDeclaredMethods()) {
            if (method.getAnnotation(Select.class) != null) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /** 供文档用：接口全部 {@code @Select} 方法名（去重排序） */
    static Set<String> auditedSelectMethods(Class<?> mapper) {
        return new TreeSet<>(selectMethodNames(mapper));
    }
}
