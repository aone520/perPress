package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 错误样本实体：对应表 error_sample，失败样本明细（每任务累计保留前 200 条）
 */
@Data
@TableName("error_sample")
public class ErrorSample {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 上报节点标识 */
    private String nodeKey;

    /** 采样器名称 */
    private String sampler;

    /** 响应码 */
    private String responseCode;

    /** 错误消息 */
    private String message;

    /** 样本时间戳（毫秒） */
    private Long ts;

    /** 入库时间（数据库默认生成） */
    private LocalDateTime createTime;
}
