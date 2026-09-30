package com.per.server.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户信息（来自 JWT 解析结果，由认证拦截器写入 ThreadLocal）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    /** 用户ID */
    private Long uid;

    /** 用户名 */
    private String username;

    /** 角色：ADMIN/USER */
    private String role;
}
