package com.per.server.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent 心跳上报请求 DTO
 */
@Data
public class AgentHeartbeatRequest {

    /** 节点唯一标识 */
    @NotBlank(message = "nodeKey不能为空")
    private String nodeKey;

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

    /** 网络接收速率（字节/秒，物理网卡差分） */
    private Double netRecvBps;

    /** 网络发送速率（字节/秒，物理网卡差分） */
    private Double netSentBps;

    /** 已部署 JMeter 引擎版本 */
    private String engineVersion;

    /** Agent 自身版本号（随心跳更新，用于识别待升级机器） */
    private String agentVersion;
}
