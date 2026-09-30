package com.per.server.dto;

import lombok.Data;

import java.util.List;

/**
 * Agent 指标上报请求 DTO：每 10 秒窗口一次，含采样器聚合快照与错误样本
 * （errors 每窗口最多传 10 条，Server 全任务累计保留前 200 条 error_sample）
 */
@Data
public class AgentMetricsRequest {

    /** 任务ID */
    private Long taskId;

    /** 上报节点标识 */
    private String nodeKey;

    /** 窗口起点毫秒（对齐整 10s） */
    private Long windowStart;

    /** 窗口终点毫秒 */
    private Long windowEnd;

    /** 本节点压测是否已结束（收尾窗口标记，当前仅作记录） */
    private Boolean finished;

    /** 采样器聚合快照列表 */
    private List<SamplerMetric> samplers;

    /** 错误样本列表（每窗口最多 10 条） */
    private List<ErrorItem> errors;

    /**
     * 单采样器窗口聚合指标
     */
    @Data
    public static class SamplerMetric {

        /** 采样器名称 */
        private String label;

        /** 窗口内样本数 */
        private Long count;

        /** 窗口内错误数 */
        private Long errorCount;

        /** 窗口内接收字节数 */
        private Long bytes;

        /** 窗口内发送字节数 */
        private Long sentBytes;

        /** 窗口末活跃线程数 */
        private Integer activeThreads;

        /** 最小响应时间（毫秒） */
        private Integer minMs;

        /** 最大响应时间（毫秒） */
        private Integer maxMs;

        /** 响应时间总和（毫秒） */
        private Long sumMs;

        /** 对数桶计数（逗号分隔 38 项，桶定义见 MetricBuckets） */
        private String buckets;
    }

    /**
     * 错误样本明细
     */
    @Data
    public static class ErrorItem {

        /** 采样器名称 */
        private String label;

        /** 响应码 */
        private String code;

        /** 错误消息 */
        private String msg;

        /** 样本时间戳毫秒 */
        private Long ts;
    }
}
