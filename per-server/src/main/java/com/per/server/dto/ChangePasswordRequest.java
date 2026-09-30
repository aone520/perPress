package com.per.server.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改密码请求 DTO：当前登录用户校验旧密码后设置新密码
 */
@Data
public class ChangePasswordRequest {

    /** 旧密码 */
    @NotBlank(message = "oldPassword不能为空")
    private String oldPassword;

    /** 新密码（至少 8 位且同时包含字母与数字） */
    @NotBlank(message = "newPassword不能为空")
    private String newPassword;
}
