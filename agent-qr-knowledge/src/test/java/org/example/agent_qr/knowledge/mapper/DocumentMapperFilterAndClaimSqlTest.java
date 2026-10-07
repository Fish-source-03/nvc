package org.example.agent_qr.knowledge.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentMapper} 手写 SQL 的审计测试（批次 09 · 任务 9.3 + 9.6）。
 * <p>
 * 两处改动都落在注解 SQL 上，靠"跑一遍业务"很难覆盖到边界条件（软删过滤、条件更新的
 * WHERE 子句），因此用反射把 SQL 契约固化：
 * </p>
 * <ul>
 *   <li><b>9.3</b>：筛选查询必须带 {@code deleted = 0}（{@code @TableLogic} 对手写 SQL 不生效，
 *       少了它会把已软删文档返回给前端——同问题 27 的同类坑）且两个筛选条件都在 SQL 中；</li>
 *   <li><b>9.6</b>：抢占必须<b>有条件</b>（{@code status <> 'DELETING'}），
 *       写成无条件更新就等于没有并发保护；同时 {@code updateStatus} 的语义不得被改动。</li>
 * </ul>
 *
 * @author agent-qr
 */
class DocumentMapperFilterAndClaimSqlTest {

    // ==================== 9.3 列表筛选 SQL ====================

    @Test
    @DisplayName("★ 筛选查询必须过滤已软删文档（deleted = 0）")
    void selectPageByFilter_mustFilterSoftDeletedRows() {
        String sql = selectSqlOf("selectPageByFilter");

        assertThat(sql)
                .as("手写 SQL 不带 deleted = 0 会把已删除文档返回给前端")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("★ 筛选查询必须同时具备 domain 与 sensitivity_level 两个动态条件")
    void selectPageByFilter_mustContainBothFilterConditions() {
        String sql = selectSqlOf("selectPageByFilter");

        assertThat(sql)
                .as("问题 33 断裂 1：这两个条件是前端筛选控件的落点")
                .contains("domain = #{domain}")
                .contains("sensitivity_level = #{sensitivityLevel}");
    }

    @Test
    @DisplayName("筛选参数缺省时不得产出恒假条件（否则不传参数会查不到数据）")
    void selectPageByFilter_mustBeOptional() {
        String sql = selectSqlOf("selectPageByFilter");

        assertThat(sql).contains("<if").doesNotContain("<otherwise");
    }

    // ==================== 9.6 条件更新 SQL ====================

    @Test
    @DisplayName("★ 抢占删除必须带状态条件（status <> 'DELETING'），否则等于无并发保护")
    void claimDeleting_mustBeConditional() {
        String sql = updateSqlOf("claimDeleting");

        assertThat(sql)
                .contains("SET status = 'DELETING'")
                .contains("status != 'DELETING'")
                .contains("id = #{id}")
                .as("并发保护的关键：一条 SQL 内完成'检查 + 置位'")
                .isNotEqualTo("UPDATE kb_document SET status = 'DELETING' WHERE id = #{id}");
    }

    @Test
    @DisplayName("★ 抢占条件必须显式排除已软删文档（避免与软删请求互相覆盖）")
    void claimDeleting_mustExcludeSoftDeletedRows() {
        assertThat(updateSqlOf("claimDeleting")).contains("deleted = 0");
    }

    @Test
    @DisplayName("★ updateStatus 的签名与语义保持不变（其他调用方依赖）")
    void updateStatus_mustRemainUnchanged() throws NoSuchMethodException {
        Method method = DocumentMapper.class.getMethod("updateStatus", Long.class, String.class);

        assertThat(method.getReturnType()).isEqualTo(int.class);
        assertThat(updateSqlOf("updateStatus"))
                .as("既有调用方（上传/解析链路）依赖无条件更新的语义")
                .isEqualTo("UPDATE kb_document SET status = #{status} WHERE id = #{id}");
    }

    @Test
    @DisplayName("claimDeleting 的签名固定为 (Long) → int")
    void claimDeleting_signature() throws NoSuchMethodException {
        Method method = DocumentMapper.class.getMethod("claimDeleting", Long.class);

        assertThat(method.getReturnType()).isEqualTo(int.class);
    }

    // ==================== 反射辅助 ====================

    private static String selectSqlOf(String methodName) {
        for (Method method : DocumentMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select != null && method.getName().equals(methodName)) {
                return String.join(" ", select.value());
            }
        }
        throw new AssertionError("未找到带 @Select 的方法: " + methodName);
    }

    private static String updateSqlOf(String methodName) {
        for (Method method : DocumentMapper.class.getDeclaredMethods()) {
            Update update = method.getAnnotation(Update.class);
            if (update != null && method.getName().equals(methodName)) {
                return String.join(" ", update.value());
            }
        }
        throw new AssertionError("未找到带 @Update 的方法: " + methodName);
    }
}
