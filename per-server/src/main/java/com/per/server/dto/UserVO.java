package com.per.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.per.server.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息视图对象（不包含密码，用于登录返回与用户管理列表）
 */
@Data
public class UserVO {

    /** 用户ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 角色：ADMIN/USER */
    private String role;

    /** 状态：1 启用 / 0 禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 是否必须修改初始密码（仅认证接口填充：admin 首次登录未改密时为 true；为 null 时不序列化，避免影响用户管理接口） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean mustChangePassword;

    /**
     * 由用户实体构造视图对象（剔除密码字段）
     *
     * @param user 用户实体
     * @return 用户视图对象
     */
    public static UserVO of(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
