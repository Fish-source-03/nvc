package org.example.agent_qr.auth.util;

import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtUtil} 安全测试（批次 11 · 任务 11.2.2，安全类补漏）。
 * <p>
 * 令牌的<b>不可伪造性</b>是整套鉴权的地基：只要签名校验能被绕过，
 * 后续所有 ABAC 规则都形同虚设。本类直击该地基，并锁定 Access Token 的
 * ABAC claim 完整性（问题 07 的同类回归面）：
 * </p>
 * <ul>
 *   <li>签名被篡改 / 换密钥签发的令牌 → 一律拒绝；</li>
 *   <li>过期令牌 → 拒绝；</li>
 *   <li>畸形字符串 → 拒绝且不抛异常（过滤链不得 500）；</li>
 *   <li>Access Token 必须携带全部 ABAC 属性，且 {@code role} 是用户的真实角色
 *       （问题 07 的书写错误正是"两个分支字面量相同"导致管理员降权）；</li>
 *   <li>Refresh Token 只承载 {@code userId}/{@code tokenType}，不带 ABAC 属性。</li>
 * </ul>
 * <p>
 * 与 {@code RefreshTokenServiceTest}（刷新流程，真实 JwtUtil）互补：
 * 前者验证"刷新后属性不丢"，本类验证"令牌本身不可伪造"。
 * </p>
 *
 * @author agent-qr
 */
class JwtUtilTest {

    /** 测试用签名密钥（非生产值）。 */
    private static final String TEST_SECRET = "agent-qr-test-secret-key-0123456789-abcdefghij";

    /** 另一把密钥，用于构造"换密钥签发"的伪造令牌。 */
    private static final String OTHER_SECRET = "agent-qr-other-secret-key-9876543210-zyxwvutsrq";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86400000L);
        ReflectionTestUtils.setField(jwtUtil, "accessExpiration", 1800L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604800L);
    }

    private JwtUtil utilWithSecret(String secret, long accessExpirationSeconds) {
        JwtUtil other = new JwtUtil();
        ReflectionTestUtils.setField(other, "secret", secret);
        ReflectionTestUtils.setField(other, "expiration", 86400000L);
        ReflectionTestUtils.setField(other, "accessExpiration", accessExpirationSeconds);
        ReflectionTestUtils.setField(other, "refreshExpiration", 604800L);
        return other;
    }

    private SysUser adminUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("admin");
        user.setDepartment("RD");
        user.setClearanceLevel(3);
        user.setAllowedDomains("HR,FINANCE");
        user.setTitle("director");
        return user;
    }

    // ==================== 不可伪造性 ====================

    @Test
    @DisplayName("★ 签名被篡改的令牌必须被拒绝（伪造令牌不得放行）")
    void validateToken_shouldReject_whenSignatureTampered() {
        String token = jwtUtil.generateAccessToken(adminUser());
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        // 篡改签名段：解码后翻转首字节的最低位，再重新 base64url 编码 —— 字节必然改变，断言确定。
        // ⚠️ 不可用"替换末字符"的写法（原实现）：base64url 的**末字符仅承载 4 个有效位、低 2 位是填充位**，
        //    'A'→'B' 之类的替换可能解出**完全相同**的字节 → 令牌仍合法 → 断言随机失败
        //    （实测约 5.6% 概率 flaky；核验项 N1。生产 JWT 校验逻辑本身无误，纯属测试写法缺陷）。
        byte[] signatureBytes = java.util.Base64.getUrlDecoder().decode(parts[2]);
        signatureBytes[0] ^= 0x01;
        String tamperedSignature = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signatureBytes);
        String tampered = parts[0] + "." + parts[1] + "." + tamperedSignature;

        assertThat(jwtUtil.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("★ 载荷被篡改（自行改写 role 为 admin）必须被拒绝")
    void validateToken_shouldReject_whenPayloadTampered() {
        SysUser normalUser = adminUser();
        normalUser.setRole("user");
        String token = jwtUtil.generateAccessToken(normalUser);
        String[] parts = token.split("\\.");

        // 用原签名 + 改写后的载荷：模拟"越权者自行把 role 改成 admin"的伪造尝试
        String payloadJson = new String(
                java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
        String tamperedJson = payloadJson.replace("\"role\":\"user\"", "\"role\":\"admin\"");
        assertThat(tamperedJson).as("载荷改写未生效，检查 claim 序列化格式").contains("\"role\":\"admin\"");
        String tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThat(jwtUtil.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("★ 换密钥签发的令牌必须被拒绝（密钥不可替换）")
    void validateToken_shouldReject_whenSignedWithAnotherKey() {
        String foreignToken = utilWithSecret(OTHER_SECRET, 1800L).generateAccessToken(adminUser());

        assertThat(jwtUtil.validateToken(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("★ 过期令牌必须被拒绝")
    void validateToken_shouldReject_whenExpired() {
        // 有效期 -60 秒 → 签发即过期
        String expired = utilWithSecret(TEST_SECRET, -60L).generateAccessToken(adminUser());

        assertThat(jwtUtil.validateToken(expired)).isFalse();
    }

    @Test
    @DisplayName("畸形令牌不抛异常，一律判为无效（过滤链不得 500）")
    void validateToken_shouldReturnFalse_forMalformedInput() {
        assertThat(jwtUtil.validateToken("not-a-jwt")).isFalse();
        assertThat(jwtUtil.validateToken("a.b.c")).isFalse();
        assertThat(jwtUtil.validateToken("")).isFalse();
    }

    // ==================== claim 契约 ====================

    @Test
    @DisplayName("★ Access Token 携带完整 ABAC 属性且 role 为真实角色（问题 07 回归面）")
    void accessToken_shouldCarryRealRoleAndAllAbacAttributes() {
        String token = jwtUtil.generateAccessToken(adminUser());

        assertThat(jwtUtil.validateToken(token)).isTrue();
        UserPrincipal principal = jwtUtil.parseUserPrincipal(token);
        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getUsername()).isEqualTo("admin");
        assertThat(principal.getRole()).isEqualTo("admin");
        assertThat(principal.isAdmin()).isTrue();
        assertThat(principal.getDepartment()).isEqualTo("RD");
        assertThat(principal.getClearanceLevel()).isEqualTo(3);
        assertThat(principal.getAllowedDomains()).containsExactly("HR", "FINANCE");
        assertThat(principal.getTitle()).isEqualTo("director");
    }

    @Test
    @DisplayName("普通用户的 role 不得被写成 admin（分层鉴权的基线）")
    void accessToken_shouldKeepPlainUserRole() {
        SysUser user = adminUser();
        user.setRole("user");

        UserPrincipal principal = jwtUtil.parseUserPrincipal(jwtUtil.generateAccessToken(user));

        assertThat(principal.getRole()).isEqualTo("user");
        assertThat(principal.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("无 ABAC 属性时 allowedDomains 为空列表而非 null（域鉴权依赖其可枚举）")
    void parseUserPrincipal_shouldNormalizeMissingAttributes() {
        SysUser bare = new SysUser();
        bare.setId(2L);
        bare.setUsername("bob");
        bare.setRole("user");

        UserPrincipal principal = jwtUtil.parseUserPrincipal(jwtUtil.generateAccessToken(bare));

        assertThat(principal.getAllowedDomains()).isEmpty();
        assertThat(principal.getDepartment()).isNull();
        assertThat(principal.getClearanceLevel()).isNull();
    }

    @Test
    @DisplayName("Refresh Token 只承载 userId 与 tokenType，不携带 ABAC 属性")
    void refreshToken_shouldCarryIdentityOnly() {
        String token = jwtUtil.generateRefreshToken(adminUser());

        assertThat(jwtUtil.validateToken(token)).isTrue();
        UserPrincipal principal = jwtUtil.parseUserPrincipal(token);
        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getUsername()).isEqualTo("admin");
        assertThat(principal.getRole()).isNull();
        assertThat(principal.getAllowedDomains()).isEmpty();
    }
}
