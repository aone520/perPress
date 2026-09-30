package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.common.RequireAdmin;
import com.per.server.dto.PageVO;
import com.per.server.dto.UserCreateRequest;
import com.per.server.dto.UserUpdateRequest;
import com.per.server.dto.UserVO;
import com.per.server.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口：用户分页查询、创建与更新（均要求管理员权限）
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 分页查询用户列表（仅管理员）
     *
     * @param page 页码，默认 1
     * @param size 每页条数，默认 10
     * @return 用户分页数据（不含密码）
     */
    @GetMapping
    @RequireAdmin
    public R<PageVO<UserVO>> page(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size) {
        return R.ok(userService.page(page, size));
    }

    /**
     * 创建用户（仅管理员）
     *
     * @param request 创建请求（用户名 + 密码 + 昵称 + 角色）
     * @return 创建后的用户信息
     */
    @PostMapping
    @RequireAdmin
    public R<UserVO> create(@RequestBody @Valid UserCreateRequest request) {
        return R.ok(userService.create(request));
    }

    /**
     * 更新用户信息（仅管理员；内置 admin 不允许改角色/状态）
     *
     * @param id      用户ID
     * @param request 更新请求（昵称/角色/状态/密码均可选）
     * @return 更新后的用户信息
     */
    @PutMapping("/{id}")
    @RequireAdmin
    public R<UserVO> update(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return R.ok(userService.update(id, request));
    }
}
