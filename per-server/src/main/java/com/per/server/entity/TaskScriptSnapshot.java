package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务脚本快照实体：对应表 task_script_snapshot，FORM 脚本按压测模式重渲染后的 JMX 快照。
 * 任务下发（PREPARE 载荷与脚本下载）优先使用快照内容，保证任务参数与脚本形态不可变
 */
@Data
@TableName("task_script_snapshot")
public class TaskScriptSnapshot {

    /** 任务ID（主键，与 test_task.id 一致） */
    @TableId(type = IdType.INPUT)
    private Long taskId;

    /** 按模式渲染后的 JMX 内容 */
    private String jmxContent;

    /** 生成时间（数据库默认生成） */
    private LocalDateTime createTime;
}
