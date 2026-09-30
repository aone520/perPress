package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标快照实体：对应表 metric_snapshot，Agent 每 10 秒窗口上报的采样器聚合指标。
 * 唯一键 (task_id,node_key,sampler,window_start) 保证上报幂等：冲突时整行覆盖更新
 */
@Data
@TableName("metric_snapshot")
public class MetricSnapshot {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 上报节点标识 */
    private String nodeKey;

    /** 采样器名称 */
    private String sampler;

    /** 窗口起点毫秒（对齐整 10s） */
    private Long windowStart;

    /** 窗口终点毫秒 */
    private Long windowEnd;

    /** 窗口内样本数 */
    private Long sampleCount;

    /** 窗口内错误数 */
    private Long errorCount;

    /** 窗口内接收字节数 */
    private Long bytes;

    /** 窗口内发送字节数 */
    private Long sentBytes;

    /** 窗口末活跃线程数 */
    private Integer activeThreads;

    /** 窗口内最小响应时间（毫秒） */
    private Integer minMs;

    /** 窗口内最大响应时间（毫秒） */
    private Integer maxMs;

    /** 窗口内响应时间总和（毫秒） */
    private Long sumMs;

    /** 对数桶计数（逗号分隔 38 项，桶定义见 MetricBuckets） */
    private String buckets;

    /** 入库时间（数据库默认生成，快照过期清理依据） */
    private LocalDateTime createTime;
}
