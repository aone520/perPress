package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 压力机资源采样实体：对应表 node_resource_sample，
 * 任务运行期间 Agent 心跳携带的 CPU/内存指标逐次落库（10 秒粒度），报告据此绘制资源曲线
 */
@Data
@TableName("node_resource_sample")
public class NodeResourceSample {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联任务ID */
    private Long taskId;

    /** 节点唯一标识 */
    private String nodeKey;

    /** CPU 使用率（0-100） */
    private Double cpuUsage;

    /** 系统内存使用率（0-100） */
    private Double memUsage;

    /** 系统总内存（字节） */
    private Long memTotal;

    /** JVM 已用堆（字节） */
    private Long jvmMemUsed;

    /** JVM 最大堆（字节） */
    private Long jvmMemMax;

    /** 网络接收速率（字节/秒，物理网卡差分） */
    private Double netRecvBps;

    /** 网络发送速率（字节/秒，物理网卡差分） */
    private Double netSentBps;

    /** 采样时间（心跳到达时间） */
    private LocalDateTime createTime;
}
