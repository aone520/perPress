package com.per.agent.client;

import java.util.List;

/**
 * 压测指标上报请求体（M3 指标协议，每窗一报）：POST {server}/agent/metrics。
 * <p>Agent 端每 10 秒封一个窗口即上报一次该窗口内全部 label 的聚合数据；
 * finished=true 表示该任务指标流结束（进程退出后的尾窗或空尾报）。</p>
 *
 * @param taskId      任务 ID
 * @param nodeKey     节点密钥
 * @param windowStart 窗口起始时间戳（毫秒，整 10 秒对齐）
 * @param windowEnd   窗口结束时间戳（= windowStart + 10000）
 * @param finished    是否为该任务最后一报（true 后服务端可结束此任务指标流）
 * @param samplers    窗口内各 label 的聚合指标数组（无样本的尾报可能为空数组）
 * @param errors      窗口内错误样本明细（最多 10 条）
 */
public record MetricsReport(Long taskId, String nodeKey, long windowStart, long windowEnd,
                            boolean finished, List<SamplerMetrics> samplers, List<ErrorSample> errors) {

    /**
     * 单个 label（采样器）的窗口聚合指标。
     *
     * @param label         采样器名称（JTL 第 3 列）
     * @param count         样本总数
     * @param errorCount    错误样本数（success=false）
     * @param bytes         接收字节总量（JTL bytes 列求和）
     * @param sentBytes     发送字节总量（JTL sentBytes 列求和）
     * @param activeThreads 窗口内活跃线程峰值（JTL allThreads 列最大值）
     * @param minMs         最小响应耗时（毫秒）
     * @param maxMs         最大响应耗时（毫秒）
     * @param sumMs         响应耗时总和（毫秒，服务端可除以 count 得均值）
     * @param buckets       38 桶响应耗时直方图（逗号分隔字符串，桶上界见 JtlMetricsCollector.BUCKET_BOUNDS）
     */
    public record SamplerMetrics(String label, long count, long errorCount, long bytes, long sentBytes,
                                 int activeThreads, long minMs, long maxMs, long sumMs, String buckets) {
    }

    /**
     * 窗口内错误样本明细（用于服务端展示失败原因抽样）。
     *
     * @param label 采样器名称
     * @param code  响应码（JTL responseCode 列）
     * @param msg   失败信息（JTL failureMessage 列，断言失败为空时回退 responseMessage，截断至 200 字符）
     * @param ts    样本时间戳（毫秒，JTL timeStamp 列，用于报告页错误样本明细的时间展示）
     */
    public record ErrorSample(String label, String code, String msg, long ts) {
    }
}
