package com.per.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建用户请求 DTO
 */
@Data
public class UserCreateRequest {

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过64")
    private String username;

    /** 初始密码 */
    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码长度不能超过64")
    private String password;

    /** 昵称 */
    private String nickname;

    /** 角色 ADMIN/USER（为空默认 USER） */
    private String role;
}
