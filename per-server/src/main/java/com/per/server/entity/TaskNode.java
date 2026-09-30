package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务节点实体：对应表 task_node，记录任务在单个节点上的执行明细
 * 节点状态机：PENDING → DOWNLOADING → READY → RUNNING → FINISHED/FAILED；停止路径为 STOPPED
 */
@Data
@TableName("task_node")
public class TaskNode {

    /** 节点状态常量：待下发 */
    public static final String STATUS_PENDING = "PENDING";
    /** 节点状态常量：下载中 */
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    /** 节点状态常量：就绪（文件与脚本准备完成，等待 START 命令） */
    public static final String STATUS_READY = "READY";
    /** 节点状态常量：压测运行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 节点状态常量：已停止（服务端停止/超时强制置位） */
    public static final String STATUS_STOPPED = "STOPPED";
    /** 节点状态常量：正常结束 */
    public static final String STATUS_FINISHED = "FINISHED";
    /** 节点状态常量：执行失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 节点状态常量：被剔除 */
    public static final String STATUS_EXCLUDED = "EXCLUDED";

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 节点唯一标识 */
    private String nodeKey;

    /** 节点状态：PENDING/DOWNLOADING/READY/RUNNING/STOPPED/FINISHED/FAILED/EXCLUDED */
    private String status;

    /** 该节点 JMeter -J 参数 JSON（threads/rampup/duration 按节点均分后） */
    private String jmeterProps;

    /** SPLIT 模式分片序号（从 0 开始） */
    private Integer shardIndex;

    /** 失败原因 */
    private String errorMsg;

    /** 节点开始时间 */
    private LocalDateTime startTime;

    /** 节点结束时间 */
    private LocalDateTime endTime;

    /** 创建时间（数据库默认生成） */
    private LocalDateTime createTime;
}
