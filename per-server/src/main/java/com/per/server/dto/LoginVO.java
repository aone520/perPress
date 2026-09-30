package com.per.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录成功响应 DTO：包含访问令牌与用户信息
 */
@Data
@AllArgsConstructor
public class LoginVO {

    /** JWT 访问令牌 */
    private String token;

    /** 用户信息 */
    private UserVO user;

    /** 是否必须修改初始密码（admin 且 admin.initial-password-flag=1 时为 true，其余/旧 token 兼容默认 false） */
    private Boolean mustChangePassword;
}
