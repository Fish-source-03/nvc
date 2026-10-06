package org.example.agent_qr.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.dto.TokenPair;
import org.example.agent_qr.auth.entity.TokenRefresh;
import org.example.agent_qr.auth.mapper.TokenRefreshMapper;
import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.user.entity.SysUser;
import org.example.agent_qr.user.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Refresh Token 服务，负责双 Token 的签发、刷新和撤销。
 * <p>
 * 签发双 Token 时 Refresh Token 写入数据库；
 * 刷新时执行令牌轮换（旧 Token 撤销 + 新 Token 签发）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class RefreshTokenService {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenRefreshMapper tokenRefreshMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    /**
     * 签发双 Token（Access + Refresh）。
     *
     * @param user 用户实体
     * @return TokenPair 包含双 Token
     */
    @Transactional
    public TokenPair issueTokens(SysUser user) {
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        // Refresh Token 持久化
        TokenRefresh tokenRefresh = new TokenRefresh();
        tokenRefresh.setUserId(user.getId());
        tokenRefresh.setToken(refreshToken);
        tokenRefresh.setRevoked(false);
        tokenRefresh.setCreateTime(LocalDateTime.now());
        tokenRefresh.setExpireTime(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshExpiration()));
        tokenRefreshMapper.insert(tokenRefresh);

        log.info("双 Token 已签发: userId={}, username={}", user.getId(), user.getUsername());
        return new TokenPair(accessToken, refreshToken, jwtUtil.getAccessExpiration());
    }

    /**
     * 刷新 Token（令牌轮换）。
     * <p>
     * 验证 Refresh Token 有效性 → 查 DB 未撤销 → <b>从数据库重新加载完整用户</b>并校验状态 →
     * 删除旧 Refresh Token（轮换）→ 签发新令牌对。
     * </p>
     * <p>
     * ★ 问题 07 修复：旧实现构造空 {@code SysUser} 且把 role 硬编码为 {@code "user"}
     * （三元表达式两个分支字面量相同），导致管理员刷新一次令牌即降权、
     * 全部 ABAC 属性（department/clearanceLevel/allowedDomains/title）清零。
     * 现将取值来源改为数据库记录。
     * </p>
     *
     * @param refreshToken 当前的 Refresh Token
     * @return 新的 TokenPair
     */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        // 1. 验证 JWT 有效性
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new BusinessException(401, "Refresh Token 无效或已过期");
        }

        // 2. 查 DB 确认未撤销
        TokenRefresh stored = tokenRefreshMapper.selectByToken(refreshToken);
        if (stored == null) {
            throw new BusinessException(401, "Refresh Token 已被撤销或不存在");
        }

        // 3. 从数据库重新加载完整用户（★ 问题 07：角色与 ABAC 属性必须取自 DB，不得硬编码）
        SysUser user = sysUserMapper.selectById(stored.getUserId());
        if (user == null) {
            log.warn("刷新令牌失败：用户不存在或已被删除, userId={}", stored.getUserId());
            throw new BusinessException(401, "用户不存在或已被删除，请重新登录");
        }
        // 4. 校验用户状态（★ 问题 07：已禁用用户不得通过刷新继续获得令牌）
        if (user.getStatus() == null || user.getStatus() != 1) {
            log.warn("刷新令牌被拒绝：用户已禁用, userId={}, status={}", user.getId(), user.getStatus());
            throw new BusinessException(403, "账号已被禁用，无法刷新令牌");
        }

        // 5. 令牌轮换：撤销旧 Token
        stored.setRevoked(true);
        tokenRefreshMapper.updateById(stored);

        // 6. 签发新令牌对（含完整 ABAC 属性）
        log.info("Refresh Token 轮换成功: userId={}, role={}, department={}",
                user.getId(), user.getRole(), user.getDepartment());
        return issueTokens(user);
    }

    /**
     * 撤销用户的所有 Refresh Token。
     *
     * @param userId 用户 ID
     */
    @Transactional
    public void revoke(Long userId) {
        int count = tokenRefreshMapper.revokeByUserId(userId);
        log.info("已撤销用户 {} 的所有 Refresh Token，共 {} 条", userId, count);
    }
}
