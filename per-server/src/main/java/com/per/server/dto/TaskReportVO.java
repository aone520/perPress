package com.per.server.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 任务测试报告视图：GET /api/tasks/{id}/report 返回体。
 * finalized=true 表示读取的是任务结束固化的 test_report；false 表示任务未结束时实时聚合
 */
@Data
public class TaskReportVO {

    /** 是否已固化（任务结束后聚合入库的报告） */
    private Boolean finalized;

    /** 汇总：{taskNo,name,mode,nodeCount,startTime,endTime,durationSeconds,totalCount,totalErrorCount,errorRate,avgTps,peakTps,recvTotalKB,sentTotalKB,peakThreads,rt:{min,avg,p50,p75,p90,p95,p99,p999,max}} */
    private JsonNode summary;

    /** 采样器级全量指标（同 metrics.samplers 字段 + bytes/sentBytes/p75/p999） */
    private JsonNode samplers;

    /** 节点级指标：[{nodeKey, count, errorCount, tps, avgMs, p95, p99, bytes}] */
    private JsonNode nodes;

    /** 错误分析：{byCode, topSamplers, samples, timeline} */
    private JsonNode errors;

    /** 全任务时间序列（与 /metrics 的 series 相同） */
    private JsonNode series;
}
