package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 压测脚本实体：对应表 script，记录脚本基本信息（导入 JMX 或表单生成）与最新版本号
 */
@Data
@TableName("script")
public class Script {

    /** 脚本类型常量：导入 JMX */
    public static final String TYPE_IMPORTED = "IMPORTED";
    /** 脚本类型常量：表单生成 */
    public static final String TYPE_FORM = "FORM";

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 脚本名称 */
    private String name;

    /** 脚本描述 */
    private String description;

    /** 类型：IMPORTED导入/FORM表单 */
    private String type;

    /** 表单场景定义 JSON（仅 FORM 类型有值） */
    private String formDef;

    /** 最新版本号 */
    private Integer latestVersion;

    /** 创建人 */
    private String createBy;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 创建时间（数据库默认生成） */
    private LocalDateTime createTime;
}
