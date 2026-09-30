package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.dto.ChangePasswordRequest;
import com.per.server.dto.LoginRequest;
import com.per.server.dto.LoginVO;
import com.per.server.dto.UserVO;
import com.per.server.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录、当前用户信息查询与修改密码
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录（免认证接口）
     *
     * @param request 登录请求（用户名 + 密码）
     * @return token 与用户信息
     */
    @PostMapping("/login")
    public R<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return R.ok(authService.login(request));
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public R<UserVO> me() {
        return R.ok(authService.me());
    }

    /**
     * 修改当前登录用户密码：校验旧密码（BCrypt），新密码至少 8 位且同时包含字母与数字；
     * admin 修改成功后清除首次强制改密标记
     *
     * @param request 修改密码请求（旧密码 + 新密码）
     * @return 空数据成功响应
     */
    @PostMapping("/change-password")
    public R<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        authService.changePassword(request);
        return R.ok();
    }
}
