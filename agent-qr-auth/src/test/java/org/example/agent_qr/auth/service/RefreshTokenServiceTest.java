package org.example.agent_qr.auth.service;

import org.example.agent_qr.auth.dto.TokenPair;
import org.example.agent_qr.auth.entity.TokenRefresh;
import org.example.agent_qr.auth.mapper.TokenRefreshMapper;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.user.entity.SysUser;
import org.example.agent_qr.user.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RefreshTokenService} 单元测试（批次 03 · 任务 3.2，问题 07 —— 本批次最关键的一组）。
 * <p>
 * 拦截的核心缺陷：{@code refresh()} 构造空 {@code SysUser}，只回填 {@code id}/{@code username}，
 * 且 role 被 {@code x != null ? "user" : "user"}（两分支字面量相同）硬编码为 {@code "user"}。
 * 后果：管理员刷新一次令牌即降权、全部 ABAC 属性清零；被禁用用户凭旧 Refresh Token 仍可续期。
 * </p>
 * <p>
 * 本测试刻意使用<b>真实的 {@link JwtUtil}</b> 生成与解析令牌：断言直接落在
 * "新 Access Token 的 claim 内容"上，而不是只验证 mock 调用——这才是原缺陷的真实可观测面。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenServiceTest {

    /** 测试用签名密钥（非生产值）。 */
    private static final String TEST_SECRET = "agent-qr-test-secret-key-0123456789-abcdefghij";

    @Mock
    private TokenRefreshMapper tokenRefreshMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    private JwtUtil jwtUtil;
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "accessExpiration", 1800L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604800L);

        refreshTokenService = new RefreshTokenService();
        ReflectionTestUtils.setField(refreshTokenService, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(refreshTokenService, "tokenRefreshMapper", tokenRefreshMapper);
        ReflectionTestUtils.setField(refreshTokenService, "sysUserMapper", sysUserMapper);
    }

    /** 构造一个管理员用户（完整 ABAC 属性）。 */
    private SysUser adminUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("$2a$12$not-a-real-hash");
        user.setRole("admin");
        user.setStatus(1);
        user.setDepartment("RD");
        user.setClearanceLevel(3);
        user.setAllowedDomains("HR,FINANCE,RD");
        user.setTitle("director");
        return user;
    }

    /** 构造一个普通用户。 */
    private SysUser normalUser() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("chenming");
        user.setRole("user");
        user.setStatus(1);
        user.setDepartment("HR");
        user.setClearanceLevel(1);
        user.setAllowedDomains("HR");
        user.setTitle("employee");
        return user;
    }

    /** 让 DB 中存在一条未撤销的 Refresh Token 记录。 */
    private String storedRefreshTokenFor(SysUser user) {
        String refreshToken = jwtUtil.generateRefreshToken(user);
        TokenRefresh stored = new TokenRefresh();
        stored.setId(100L);
        stored.setUserId(user.getId());
        stored.setToken(refreshToken);
        stored.setRevoked(false);
        when(tokenRefreshMapper.selectByToken(refreshToken)).thenReturn(stored);
        return refreshToken;
    }

    @Test
    @DisplayName("★ 管理员刷新令牌后，新 Access Token 的 role 仍为 admin（这条用例能拦住原缺陷）")
    void refresh_shouldPreserveAdminRole() {
        SysUser admin = adminUser();
        when(sysUserMapper.selectById(1L)).thenReturn(admin);
        String refreshToken = storedRefreshTokenFor(admin);

        TokenPair pair = refreshTokenService.refresh(refreshToken);

        UserPrincipal principal = jwtUtil.parseUserPrincipal(pair.getAccessToken());
        assertThat(principal.getRole())
                .as("原缺陷：role 被三元表达式硬编码为 \"user\"，管理员刷新即降权")
                .isEqualTo("admin");
        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("★ 刷新后 UserPrincipal.isAdmin() 仍返回 true")
    void refresh_shouldKeepPrincipalAdmin() {
        SysUser admin = adminUser();
        when(sysUserMapper.selectById(1L)).thenReturn(admin);
        String refreshToken = storedRefreshTokenFor(admin);

        TokenPair pair = refreshTokenService.refresh(refreshToken);

        UserPrincipal principal = jwtUtil.parseUserPrincipal(pair.getAccessToken());
        assertThat(principal.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("★ 刷新后 department/clearanceLevel/allowedDomains/title 与刷新前一致")
    void refresh_shouldPreserveAbacAttributes() {
        SysUser admin = adminUser();
        when(sysUserMapper.selectById(1L)).thenReturn(admin);
        String refreshToken = storedRefreshTokenFor(admin);

        TokenPair pair = refreshTokenService.refresh(refreshToken);

        UserPrincipal principal = jwtUtil.parseUserPrincipal(pair.getAccessToken());
        assertThat(principal.getDepartment()).isEqualTo("RD");
        assertThat(principal.getClearanceLevel()).isEqualTo(3);
        assertThat(principal.getAllowedDomains()).containsExactly("HR", "FINANCE", "RD");
        assertThat(principal.getTitle()).isEqualTo("director");
        // 域判定依赖上述属性，属性齐全时不应再被 canQueryDomain 拒绝
        assertThat(principal.hasDomainAccess("FINANCE")).isTrue();
    }

    @Test
    @DisplayName("★ 已禁用用户刷新应被拒绝（不得凭旧 Refresh Token 继续获得令牌）")
    void refresh_shouldRejectWhenUserDisabled() {
        SysUser disabled = adminUser();
        disabled.setStatus(0);
        when(sysUserMapper.selectById(1L)).thenReturn(disabled);
        String refreshToken = storedRefreshTokenFor(disabled);

        assertThatThrownBy(() -> refreshTokenService.refresh(refreshToken))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403))
                .hasMessageContaining("禁用");

        // 拒绝时不得轮换旧令牌，也不得签发新令牌
        verify(tokenRefreshMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("用户已被删除时刷新应被拒绝")
    void refresh_shouldRejectWhenUserDeleted() {
        SysUser user = normalUser();
        when(sysUserMapper.selectById(7L)).thenReturn(null);
        String refreshToken = storedRefreshTokenFor(user);

        assertThatThrownBy(() -> refreshTokenService.refresh(refreshToken))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));

        verify(tokenRefreshMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("用户信息必须从数据库加载（不再是空 SysUser + 硬编码角色）")
    void refresh_shouldLoadFullUserFromDatabase() {
        SysUser normal = normalUser();
        when(sysUserMapper.selectById(7L)).thenReturn(normal);
        String refreshToken = storedRefreshTokenFor(normal);

        TokenPair pair = refreshTokenService.refresh(refreshToken);

        verify(sysUserMapper).selectById(7L);
        UserPrincipal principal = jwtUtil.parseUserPrincipal(pair.getAccessToken());
        assertThat(principal.getRole()).isEqualTo("user");
        assertThat(principal.getDepartment()).isEqualTo("HR");
        assertThat(principal.getTitle()).isEqualTo("employee");
    }

    @Test
    @DisplayName("刷新成功后旧 Refresh Token 被撤销（令牌轮换保持不变）")
    void refresh_shouldRotateOldToken() {
        SysUser normal = normalUser();
        when(sysUserMapper.selectById(7L)).thenReturn(normal);
        String refreshToken = storedRefreshTokenFor(normal);

        TokenPair pair = refreshTokenService.refresh(refreshToken);

        ArgumentCaptor<TokenRefresh> captor = ArgumentCaptor.forClass(TokenRefresh.class);
        verify(tokenRefreshMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRevoked()).isTrue();
        assertThat(captor.getValue().getToken()).isEqualTo(refreshToken);
        // 注：JWT 的 iat/exp 精度为秒，同一秒内签发的令牌字符串可能完全相同，
        // 因此这里断言"新 Refresh Token 仍属于同一用户且有效"，而不是断言字符串不同。
        assertThat(jwtUtil.validateToken(pair.getRefreshToken())).isTrue();
        assertThat(jwtUtil.parseUserPrincipal(pair.getRefreshToken()).getUserId()).isEqualTo(7L);
        assertThat(pair.getExpiresIn()).isEqualTo(1800L);
    }

    @Test
    @DisplayName("已撤销/不存在的 Refresh Token 不得换取新令牌")
    void refresh_shouldRejectWhenTokenRevokedOrMissing() {
        String refreshToken = jwtUtil.generateRefreshToken(normalUser());
        when(tokenRefreshMapper.selectByToken(refreshToken)).thenReturn(null);

        assertThatThrownBy(() -> refreshTokenService.refresh(refreshToken))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("撤销会将该用户全部 Refresh Token 置为 revoked（问题 08：登出必须吊销服务端令牌）")
    void revoke_shouldRevokeAllTokensOfUser() {
        when(tokenRefreshMapper.revokeByUserId(7L)).thenReturn(2);

        refreshTokenService.revoke(7L);

        verify(tokenRefreshMapper).revokeByUserId(7L);
    }

    @Test
    @DisplayName("★ 登出后再用旧 Refresh Token 刷新应被拒绝（token_refresh 查询带 revoked = 0 条件）")
    void refresh_shouldRejectOldToken_afterLogout() {
        SysUser user = normalUser();
        String oldRefreshToken = jwtUtil.generateRefreshToken(user);

        // 登出：撤销该用户全部 Refresh Token
        refreshTokenService.revoke(7L);
        verify(tokenRefreshMapper).revokeByUserId(7L);

        // 撤销后 selectByToken（SQL 含 AND revoked = 0）不再返回该记录
        when(tokenRefreshMapper.selectByToken(oldRefreshToken)).thenReturn(null);

        assertThatThrownBy(() -> refreshTokenService.refresh(oldRefreshToken))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401))
                .hasMessageContaining("撤销");

        verify(sysUserMapper, never()).selectById(any());
    }
}
