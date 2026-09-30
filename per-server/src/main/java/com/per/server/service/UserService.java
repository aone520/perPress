package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.per.server.common.BizException;
import com.per.server.dto.PageVO;
import com.per.server.dto.UserCreateRequest;
import com.per.server.dto.UserUpdateRequest;
import com.per.server.dto.UserVO;
import com.per.server.entity.User;
import com.per.server.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户管理服务：用户的分页查询、创建与更新（管理员操作，均记录审计日志）
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuditService auditService;

    /**
     * 分页查询用户列表（按 id 倒序）
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 用户分页结果（不含密码）
     */
    public PageVO<UserVO> page(long page, long size) {
        Page<User> result = userMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<User>().orderByDesc(User::getId));
        List<UserVO> records = result.getRecords().stream().map(UserVO::of).toList();
        return PageVO.of(records, result.getTotal());
    }

    /**
     * 创建用户：校验用户名唯一与角色合法性，密码 BCrypt 加密存储
     *
     * @param request 创建请求（用户名 + 密码 + 昵称 + 角色）
     * @return 创建后的用户信息
     */
    public UserVO create(UserCreateRequest request) {
        String role = StringUtils.hasText(request.getRole()) ? request.getRole() : "USER";
        checkRole(role);
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (count != null && count > 0) {
            throw new BizException("用户名已存在");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);
        auditService.record("CREATE_USER", "创建用户 " + user.getUsername());
        return UserVO.of(user);
    }

    /**
     * 更新用户：昵称/角色/状态/密码均可选更新；
     * 内置管理员（admin）不允许修改角色与状态
     *
     * @param id      用户ID
     * @param request 更新请求
     * @return 更新后的用户信息
     */
    public UserVO update(Long id, UserUpdateRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        boolean builtinAdmin = "admin".equals(user.getUsername());
        if (builtinAdmin && request.getRole() != null && !request.getRole().equals(user.getRole())) {
            throw new BizException("不允许修改内置管理员的角色");
        }
        if (builtinAdmin && request.getStatus() != null && !request.getStatus().equals(user.getStatus())) {
            throw new BizException("不允许修改内置管理员的状态");
        }
        User update = new User();
        update.setId(id);
        if (request.getNickname() != null) {
            update.setNickname(request.getNickname());
        }
        if (request.getRole() != null) {
            checkRole(request.getRole());
            update.setRole(request.getRole());
        }
        if (request.getStatus() != null) {
            if (request.getStatus() != 0 && request.getStatus() != 1) {
                throw new BizException("状态不合法，仅支持 0/1");
            }
            update.setStatus(request.getStatus());
        }
        if (StringUtils.hasText(request.getPassword())) {
            update.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        userMapper.updateById(update);
        auditService.record("UPDATE_USER", "更新用户 " + user.getUsername() + " 信息");
        return UserVO.of(userMapper.selectById(id));
    }

    /**
     * 校验角色取值合法（仅支持 ADMIN/USER）
     *
     * @param role 角色字符串
     */
    private void checkRole(String role) {
        if (!"ADMIN".equals(role) && !"USER".equals(role)) {
            throw new BizException("角色不合法，仅支持 ADMIN/USER");
        }
    }
}
