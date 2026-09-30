package com.per.server.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点信息视图对象：labels 字段由逗号分隔字符串拆分为字符串数组
 */
@Data
public class NodeVO {

    /** 节点ID */
    private Long id;

    /** 节点唯一标识（UUID） */
    private String nodeKey;

    /** 主机名 */
    private String hostname;

    /** IP 地址 */
    private String ip;

    /** 操作系统 */
    private String os;

    /** JVM 版本 */
    private String jvmVersion;

    /** Agent 版本 */
    private String agentVersion;

    /** 已部署 JMeter 引擎版本 */
    private String engineVersion;

    /** 标签数组 */
    private List<String> labels;

    /** 状态：ONLINE/OFFLINE */
    private String status;

    /** CPU 使用率（百分比） */
    private Double cpuUsage;

    /** 内存使用率（百分比） */
    private Double memUsage;

    /** 总内存（字节） */
    private Long memTotal;

    /** JVM 已用内存（字节） */
    private Long jvmMemUsed;

    /** JVM 最大内存（字节） */
    private Long jvmMemMax;

    /** 最近心跳时间 */
    private LocalDateTime lastHeartbeatTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
