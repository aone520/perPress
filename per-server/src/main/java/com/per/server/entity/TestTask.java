package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 压测任务实体：对应表 test_task，记录任务基本信息与状态机流转
 * 状态机：CREATED → PREPARING → RUNNING → STOPPING → FINISHED/FAILED
 */
@Data
@TableName("test_task")
public class TestTask {

    /** 任务状态常量：已创建 */
    public static final String STATUS_CREATED = "CREATED";
    /** 任务状态常量：准备中（等待各节点下载就绪） */
    public static final String STATUS_PREPARING = "PREPARING";
    /** 任务状态常量：运行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 任务状态常量：停止中（已下发 STOP，等待节点结束） */
    public static final String STATUS_STOPPING = "STOPPING";
    /** 任务状态常量：已结束 */
    public static final String STATUS_FINISHED = "FINISHED";
    /** 任务状态常量：失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 触发方式常量：立即手动启动 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** 触发方式常量：定时自动启动 */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务编号（T+yyyyMMdd+4位随机） */
    private String taskNo;

    /** 任务名称 */
    private String name;

    /** 脚本ID */
    private Long scriptId;

    /** 脚本版本ID */
    private Long scriptVersionId;

    /** 压测模式：CONCURRENT/FIXED_TPS/STEPPED（M2 仅实现 CONCURRENT） */
    private String mode;

    /** 模式参数 JSON（CONCURRENT：threads/rampupSeconds/durationSeconds） */
    private String configJson;

    /** 参测节点（逗号分隔 node_key） */
    private String nodeKeys;

    /** 文件分发策略 JSON：[{fileId,mode:SHARED|SPLIT}] */
    private String fileDispatchJson;

    /** 任务状态：CREATED/PREPARING/RUNNING/STOPPING/FINISHED/FAILED */
    private String status;

    /** 触发方式：MANUAL 立即手动 / SCHEDULED 定时（数据库默认 MANUAL） */
    private String triggerType;

    /** 定时启动时间（trigger_type=SCHEDULED 时有效，手动启动后清空） */
    private LocalDateTime scheduledStartTime;

    /** 任务开始时间 */
    private LocalDateTime startTime;

    /** 任务结束时间 */
    private LocalDateTime endTime;

    /** 创建人 */
    private String createBy;

    /** 创建时间（数据库默认生成） */
    private LocalDateTime createTime;
}
