package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.per.server.common.BizException;
import com.per.server.common.JwtUtil;
import com.per.server.common.LoginUser;
import com.per.server.common.UserContext;
import com.per.server.dto.ChangePasswordRequest;
import com.per.server.dto.LoginRequest;
import com.per.server.dto.LoginVO;
import com.per.server.dto.UserVO;
import com.per.server.entity.SysConfig;
import com.per.server.entity.User;
import com.per.server.mapper.SysConfigMapper;
import com.per.server.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务：负责登录校验、令牌签发、当前用户信息查询与修改密码。
 * 内置 admin 首次登录强制改密：sys_config 存在 admin.initial-password-flag=1 时
 * 登录与 /me 返回 mustChangePassword=true，改密成功后清除该标记
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 系统配置键：admin 首次登录强制改密标记（=1 表示仍使用初始密码，需强制改密） */
    private static final String CONFIG_ADMIN_INITIAL_PASSWORD_FLAG = "admin.initial-password-flag";

    private final UserMapper userMapper;
    private final SysConfigMapper sysConfigMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;

    /**
     * 用户登录：校验用户名密码（BCrypt）与账号状态，成功后签发 JWT 并记录审计日志；
     * 内置 admin 尚未修改初始密码时返回 mustChangePassword=true，其余默认 false
     *
     * @param request 登录请求（用户名 + 密码）
     * @return token、用户信息与是否需强制改密
     */
    public LoginVO login(LoginRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException("账号已被禁用");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        // 登录请求自身不带 token，UserContext 尚未设置：写入临时上下文保证审计日志记录操作人
        try {
            UserContext.set(new LoginUser(user.getId(), user.getUsername(), user.getRole()));
            auditService.record("LOGIN", "用户 " + user.getUsername() + " 登录成功");
        } finally {
            UserContext.clear();
        }
        return new LoginVO(token, UserVO.of(user), mustChangePassword(user.getUsername()));
    }

    /**
     * 查询当前登录用户信息（以数据库最新数据为准，账号被禁用时视为登录失效）；
     * 内置 admin 尚未修改初始密码时附带 mustChangePassword=true（同登录规则）
     *
     * @return 当前用户信息
     */
    public UserVO me() {
        LoginUser current = UserContext.get();
        User user = userMapper.selectById(current.getUid());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
        UserVO vo = UserVO.of(user);
        vo.setMustChangePassword(mustChangePassword(user.getUsername()));
        return vo;
    }

    /**
     * 修改当前登录用户密码：校验旧密码（BCrypt）与新密码强度（≥8 位且同时包含字母与数字），
     * 更新成功后清除 admin 首次改密标记（仅当当前用户是 admin），并记录 CHANGE_PASSWORD 审计日志
     *
     * @param request 修改密码请求（旧密码 + 新密码）
     */
    public void changePassword(ChangePasswordRequest request) {
        LoginUser current = UserContext.get();
        if (current == null) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
        User user = userMapper.selectById(current.getUid());
        if (user == null) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException("旧密码错误");
        }
        String newPassword = request.getNewPassword();
        if (newPassword.length() < 8
                || !newPassword.matches(".*[A-Za-z].*")
                || !newPassword.matches(".*\\d.*")) {
            throw new BizException("新密码至少8位且需同时包含字母与数字");
        }
        User update = new User();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);
        if ("admin".equals(user.getUsername())) {
            sysConfigMapper.deleteById(CONFIG_ADMIN_INITIAL_PASSWORD_FLAG);
        }
        auditService.record("CHANGE_PASSWORD", "用户 " + user.getUsername() + " 修改登录密码");
    }

    /**
     * 判断用户是否必须修改初始密码：仅内置管理员（admin）且 sys_config 中
     * admin.initial-password-flag=1 时为 true；其余用户与旧 token 场景默认 false
     *
     * @param username 用户名
     * @return 是否必须修改初始密码
     */
    private boolean mustChangePassword(String username) {
        if (!"admin".equals(username)) {
            return false;
        }
        SysConfig config = sysConfigMapper.selectById(CONFIG_ADMIN_INITIAL_PASSWORD_FLAG);
        return config != null && "1".equals(config.getConfigValue());
    }
}
