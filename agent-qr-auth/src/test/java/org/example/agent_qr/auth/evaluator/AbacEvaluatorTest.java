package org.example.agent_qr.auth.evaluator;

import org.example.agent_qr.auth.principal.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AbacEvaluator#canModifyUser} 单元测试（批次 03 · 任务 3.6，问题 39-B2）。
 * <p>
 * 拦截的核心缺陷：实现偏离设计 §3.2.9 —— 原实现"本人放行 + 编辑他人需职级与密级<b>双高于</b>目标，
 * admin 不直通"，导致 admin 无法管理用户（除非其职级恰好高于目标），"用户管理"功能实际不可用。
 * </p>
 *
 * @author agent-qr
 */
class AbacEvaluatorTest {

    private final AbacEvaluator evaluator = new AbacEvaluator();

    private UserPrincipal user(Long id, String role, String title, Integer clearance) {
        UserPrincipal principal = new UserPrincipal();
        principal.setUserId(id);
        principal.setUsername("u" + id);
        principal.setRole(role);
        principal.setTitle(title);
        principal.setClearanceLevel(clearance);
        principal.setDepartment("HR");
        principal.setAllowedDomains(List.of("HR"));
        return principal;
    }

    @Test
    @DisplayName("★ admin 可修改任意用户（这条用例能拦住原缺陷：admin 不直通导致用户管理不可用）")
    void canModifyUser_shouldAllowAdmin_whenTargetIsOtherUser() {
        // 目标用户的职级与密级都高于 admin —— 原实现会拒绝，设计要求放行
        UserPrincipal admin = user(1L, "admin", null, null);
        UserPrincipal director = user(2L, "user", "director", 3);

        assertThat(evaluator.canModifyUser(admin, 2L)).isTrue();
        assertThat(evaluator.canModifyUser(admin, 999L)).isTrue();
        assertThat(director.getTitle()).isNotBlank();
    }

    @Test
    @DisplayName("admin 修改自己也被允许（字段级防自提权由 Controller 负责）")
    void canModifyUser_shouldAllowAdmin_whenTargetIsSelf() {
        UserPrincipal admin = user(1L, "admin", "director", 3);

        assertThat(evaluator.canModifyUser(admin, 1L)).isTrue();
    }

    @Test
    @DisplayName("普通用户可以修改自己")
    void canModifyUser_shouldAllowUser_whenTargetIsSelf() {
        UserPrincipal normal = user(7L, "user", "employee", 1);

        assertThat(evaluator.canModifyUser(normal, 7L)).isTrue();
    }

    @Test
    @DisplayName("★ 普通用户修改他人被拒绝（即使其职级/密级更高——旧规则的『双高于』判定已废除）")
    void canModifyUser_shouldRejectUser_whenTargetIsOther() {
        UserPrincipal manager = user(7L, "user", "director", 3);

        assertThat(evaluator.canModifyUser(manager, 8L)).isFalse();
        // 旧实现会因"职级与密级都高于目标"而放行——该行为属偏离设计的错误，必须杜绝
        assertThat(evaluator.canModifyUser(manager, 9L)).isFalse();
    }

    @Test
    @DisplayName("role 为 null（异常凭证）不得被当作 admin 直通")
    void canModifyUser_shouldReject_whenRoleIsNull() {
        UserPrincipal anonymous = user(7L, null, "director", 3);

        assertThat(evaluator.canModifyUser(anonymous, 8L)).isFalse();
    }
}
