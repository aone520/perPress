package com.per.server.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务节点视图对象：任务详情中的节点执行明细
 */
@Data
public class TaskNodeVO {

    /** 记录ID */
    private Long id;

    /** 节点唯一标识 */
    private String nodeKey;

    /** 节点主机名（展示用，便于识别机器） */
    private String hostname;

    /** 节点 IP（展示用） */
    private String ip;

    /** 节点状态：PENDING/DOWNLOADING/READY/RUNNING/STOPPED/FINISHED/FAILED/EXCLUDED */
    private String status;

    /** 该节点 JMeter -J 参数（jmeter_props 解析后的 JSON 节点） */
    private Object jmeterProps;

    /** SPLIT 分片序号（从 0 开始） */
    private Integer shardIndex;

    /** 失败原因 */
    private String errorMsg;

    /** 节点开始时间 */
    private LocalDateTime startTime;

    /** 节点结束时间 */
    private LocalDateTime endTime;
}
