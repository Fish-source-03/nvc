package org.example.agent_qr.user.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SysUser} 的序列化契约测试（批次 11 · 任务 11.2.2，问题 06 的单元级回归护栏）。
 * <p>
 * 本实体被 {@code /api/admin/users}、{@code /api/auth/info} 等接口<b>直接返回</b>，
 * 其 {@code password} 字段存放 BCrypt 口令哈希。序列化契约只有两条：
 * </p>
 * <ul>
 *   <li><b>出方向</b>：任何序列化都不得出现 {@code password}（否则口令哈希随响应外泄，
 *       可被离线爆破）；</li>
 *   <li><b>入方向</b>：反序列化同样忽略该字段（本实体从不作为请求体接收；
 *       忽略可避免外部 JSON 意外注入口令字段）。</li>
 * </ul>
 * <p>
 * 与 {@code AdminUsersAccessTest}（HTTP 响应层）互补：本用例直接锁定实体上的
 * {@code @JsonIgnore}，删掉注解时在最近的单元层即失败，无需启动 Web 上下文。
 * </p>
 *
 * @author agent-qr
 */
class SysUserSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("★ 序列化结果不含 password（口令哈希不得随实体外泄）")
    void serialize_shouldNeverExposePassword() throws Exception {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("$2a$10$abcdefghijklmnopqrstuv");
        user.setRole("user");

        String json = objectMapper.writeValueAsString(user);

        assertThat(json).doesNotContain("password");
        // 双重护栏：即便字段名被改写，BCrypt 哈希前缀也不得出现
        assertThat(json).doesNotContain("$2a$");
        // 其余字段必须照常序列化（避免"整个对象被忽略"式的过度修复）
        assertThat(json).contains("\"username\":\"alice\"");
        assertThat(json).contains("\"id\":7");
    }

    @Test
    @DisplayName("★ 反序列化忽略 password（外部 JSON 无法注入口令字段）")
    void deserialize_shouldIgnorePassword() throws Exception {
        String json = "{\"id\":7,\"username\":\"alice\",\"password\":\"$2a$10$injected\",\"role\":\"user\"}";

        SysUser user = objectMapper.readValue(json, SysUser.class);

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getRole()).isEqualTo("user");
        assertThat(user.getPassword()).isNull();
    }
}
