package com.per.server.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脚本摘要视图对象：脚本列表展示
 */
@Data
public class ScriptVO {

    /** 脚本ID */
    private Long id;

    /** 脚本名称 */
    private String name;

    /** 脚本描述 */
    private String description;

    /** 类型：IMPORTED导入/FORM表单 */
    private String type;

    /** 最新版本号 */
    private Integer latestVersion;

    /** 创建人 */
    private String createBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
