package com.per.server.dto;

import lombok.Data;

/**
 * 更新用户请求 DTO：字段均可选，仅更新传入的非空字段
 */
@Data
public class UserUpdateRequest {

    /** 昵称 */
    private String nickname;

    /** 角色 ADMIN/USER */
    private String role;

    /** 状态：1 启用 / 0 禁用 */
    private Integer status;

    /** 新密码（可选，传入则重置） */
    private String password;
}
