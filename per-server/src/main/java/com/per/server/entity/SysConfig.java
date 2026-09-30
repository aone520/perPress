package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置实体：对应表 sys_config，以键值对方式存储平台配置（如节点注册 token）
 */
@Data
@TableName("sys_config")
public class SysConfig {

    /** 配置键（主键，程序赋值） */
    @TableId(value = "config_key", type = IdType.INPUT)
    private String configKey;

    /** 配置值 */
    private String configValue;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;
}
