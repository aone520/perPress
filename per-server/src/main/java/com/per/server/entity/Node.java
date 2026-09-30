package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 压测节点实体：对应表 node，记录 Agent 节点的基础信息与实时资源状态
 */
@Data
@TableName("node")
public class Node {

    /** 节点在线状态常量 */
    public static final String STATUS_ONLINE = "ONLINE";
    /** 节点离线状态常量 */
    public static final String STATUS_OFFLINE = "OFFLINE";

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
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

    /** 标签（逗号分隔存储） */
    private String labels;

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

    /** 创建时间（数据库默认生成） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;
}
