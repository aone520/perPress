package com.per.server.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 任务实时指标视图：GET /api/tasks/{id}/metrics 返回体。
 * 各节点快照按 (windowStart, sampler) 合并；分位数由该窗/该采样器全节点桶相加后用 MetricBuckets 计算；
 * 任务未结束也可查询（实时监控用）
 */
@Data
public class TaskMetricsVO {

    /** 时间序列：[{t:窗口起点秒, tps, errorCount, avgMs, p95, p99, threads, recvKbps}] */
    private List<Map<String, Object>> series;

    /** 采样器级全量指标：[{label, count, errorCount, tps, avgMs, minMs, maxMs, p50, p90, p95, p99}] */
    private List<Map<String, Object>> samplers;

    /** 总量：{count, errorCount, startTime(毫秒), endTime(毫秒)} */
    private Map<String, Object> total;
}
