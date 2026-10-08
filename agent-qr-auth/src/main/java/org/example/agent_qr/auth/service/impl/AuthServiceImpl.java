package org.example.agent_qr.auth.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.auth.dto.LoginDTO;
import org.example.agent_qr.auth.dto.LoginVO;
import org.example.agent_qr.auth.dto.RegisterDTO;
import org.example.agent_qr.auth.dto.TokenPair;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.auth.service.AuthService;
import org.example.agent_qr.auth.service.RefreshTokenService;
import org.example.agent_qr.auth.util.JwtUtil;
import org.example.agent_qr.auth.util.PasswordUtil;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.user.entity.SysUser;
import org.example.agent_qr.user.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现类，处理登录、注册和获取当前用户的核心业务逻辑。
 * <p>
 * P1 原有：单 Token 登录/注册。
 * P2 扩展：双 Token 机制（Access + Refresh），LoginVO 同步返回 ABAC 属性。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private PasswordUtil passwordUtil;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Override
    public LoginVO login(LoginDTO dto) {
        SysUser user = sysUserMapper.selectByUsername(dto.getUsername());
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }

        if (!passwordUtil.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // P2: 签发双 Token（含 ABAC 属性）
        TokenPair tokenPair = refreshTokenService.issueTokens(user);
        log.info("用户 {} 登录成功（双Token，部门={}，密级={}）",
                user.getUsername(), user.getDepartment(), user.getClearanceLevel());

        return new LoginVO(
                tokenPair.getAccessToken(),
                tokenPair.getRefreshToken(),
                tokenPair.getExpiresIn(),
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getDepartment(),         // ★ P2 ABAC
                user.getClearanceLevel(),     // ★ P2 ABAC
                user.getAllowedDomains(),     // ★ P2 ABAC
                user.getTitle()               // ★ P2 ABAC
        );
    }

    @Override
    public void register(RegisterDTO dto) {
        SysUser existingUser = sysUserMapper.selectByUsername(dto.getUsername());
        if (existingUser != null) {
            throw new BusinessException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordUtil.encode(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setRole("user");
        user.setStatus(1);

        sysUserMapper.insert(user);
        log.info("新用户 {} 注册成功", user.getUsername());
    }

    @Override
    public SysUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        // P2: 支持 UserPrincipal 类型
        Object principal = authentication.getPrincipal();
        if (principal instanceof SysUser sysUser) {
            return sysUser;
        }
        if (principal instanceof UserPrincipal userPrincipal) {
            // 从数据库加载**完整**用户（含 ABAC 属性 department/clearanceLevel/allowedDomains/title）。
            // 原实现只回填 id/username/role → /api/auth/info 的 allowedDomains 为 null →
            // 前端 fetchUserInfo 把它解析成空数组并**覆盖**登录时正确的域列表，
            // 导致知识库上传对话框的"数据域"选择器为空、无法上传（批次 11 R19③ 接线后暴露）。
            SysUser loaded = sysUserMapper.selectById(userPrincipal.getUserId());
            if (loaded != null) {
                return loaded;
            }
            // 兜底：库中查不到时回退为最小对象（与原行为一致）
            SysUser user = new SysUser();
            user.setId(userPrincipal.getUserId());
            user.setUsername(userPrincipal.getUsername());
            user.setRole(userPrincipal.getRole());
            return user;
        }
        return null;
    }

    @Override
    public TokenPair issueTokens(SysUser user) {
        return refreshTokenService.issueTokens(user);
    }

    @Override
    public TokenPair refreshToken(String refreshToken) {
        return refreshTokenService.refresh(refreshToken);
    }
}
