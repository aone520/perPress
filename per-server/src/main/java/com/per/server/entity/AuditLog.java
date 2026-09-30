package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体：对应表 audit_log，记录平台关键操作
 */
@Data
@TableName("audit_log")
public class AuditLog {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作用户ID */
    private Long userId;

    /** 操作用户名 */
    private String username;

    /** 操作类型（简短英文，如 LOGIN） */
    private String action;

    /** 操作描述（中文） */
    private String detail;

    /** 操作来源 IP */
    private String ip;

    /** 创建时间（数据库默认生成） */
    private LocalDateTime createTime;
}
