package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.per.server.common.BizException;
import com.per.server.dto.AgentMetricsRequest;
import com.per.server.dto.TaskMetricsVO;
import com.per.server.entity.ErrorSample;
import com.per.server.entity.MetricSnapshot;
import com.per.server.entity.TaskNode;
import com.per.server.entity.TestTask;
import com.per.server.mapper.ErrorSampleMapper;
import com.per.server.mapper.MetricSnapshotMapper;
import com.per.server.mapper.TaskNodeMapper;
import com.per.server.mapper.TestTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 指标服务：接收 Agent 窗口指标上报（幂等入库），并提供任务级实时指标查询。
 * 上报结构：每窗口一次（窗口长度由 Agent 配置，见 per.agent.metrics-window-ms），
 * 快照按唯一键 (task_id,node_key,sampler,window_start) 冲突覆盖，
 * 错误样本每窗口最多 10 条、全任务累计保留前 200 条
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricService {

    /** 每窗口接收错误样本上限 */
    private static final int MAX_ERRORS_PER_WINDOW = 10;

    /** 全任务累计保留错误样本上限 */
    private static final int MAX_ERROR_SAMPLES_PER_TASK = 200;

    private final MetricSnapshotMapper snapshotMapper;
    private final ErrorSampleMapper errorSampleMapper;
    private final TaskNodeMapper taskNodeMapper;
    private final TestTaskMapper taskMapper;

    /**
     * 处理 Agent 指标上报：校验任务与节点归属后，
     * 逐采样器 UPSERT 快照（唯一键冲突整行覆盖，支持重试幂等），错误样本按上限入库
     *
     * @param request 上报请求（窗口 + 采样器聚合 + 错误样本）
     */
    public void report(AgentMetricsRequest request) {
        if (request.getTaskId() == null || !StringUtils.hasText(request.getNodeKey())) {
            throw new BizException(4012, "taskId与nodeKey不能为空");
        }
        TestTask task = taskMapper.selectById(request.getTaskId());
        if (task == null) {
            throw new BizException(4012, "任务不存在：taskId=" + request.getTaskId());
        }
        TaskNode node = taskNodeMapper.selectOne(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getTaskId, request.getTaskId())
                .eq(TaskNode::getNodeKey, request.getNodeKey()));
        if (node == null) {
            throw new BizException(4012, "任务节点不存在：taskId=" + request.getTaskId() + ",nodeKey=" + request.getNodeKey());
        }
        if (request.getSamplers() != null) {
            for (AgentMetricsRequest.SamplerMetric metric : request.getSamplers()) {
                upsertSnapshot(task.getId(), request.getNodeKey(), metric,
                        request.getWindowStart(), request.getWindowEnd());
            }
        }
        saveErrorSamples(task.getId(), request.getNodeKey(), request.getErrors());
    }

    /** APDEX 满意阈值（毫秒，行业惯例 500） */
    public static final double APDEX_SATISFIED_MS = 500;

    /** APDEX 可容忍阈值（毫秒，行业惯例 1500） */
    public static final double APDEX_TOLERATING_MS = 1500;

    /** 监控 series 输出保留的最大窗口点数（超出截取尾部，控制轮询响应体与前端重绘成本） */
    private static final int MAX_SERIES_POINTS = 200;

    /**
     * 查询任务实时指标（任务未结束也可查）：按 (windowStart, sampler) 合并各节点快照，
     * 产出窗口时间序列（截取最近 MAX_SERIES_POINTS 点）、采样器级全量指标与总量（含 APDEX）
     *
     * @param taskId 任务ID
     * @return 实时指标视图
     */
    public TaskMetricsVO taskMetrics(Long taskId) {
        List<MetricSnapshot> snapshots = snapshots(taskId);
        TaskMetricsVO vo = new TaskMetricsVO();
        List<Map<String, Object>> series = seriesOf(snapshots);
        if (series.size() > MAX_SERIES_POINTS) {
            series = series.subList(series.size() - MAX_SERIES_POINTS, series.size());
        }
        vo.setSeries(series);
        vo.setSamplers(samplersSection(snapshots, false));
        Map<String, Object> total = new LinkedHashMap<>();
        long count = 0;
        long errorCount = 0;
        Long startMs = null;
        Long endMs = null;
        long[] buckets = MetricBuckets.emptyBuckets();
        for (MetricSnapshot s : snapshots) {
            count += nvl(s.getSampleCount());
            errorCount += nvl(s.getErrorCount());
            MetricBuckets.merge(buckets, MetricBuckets.parse(s.getBuckets()));
            if (s.getWindowStart() != null && (startMs == null || s.getWindowStart() < startMs)) {
                startMs = s.getWindowStart();
            }
            if (s.getWindowEnd() != null && (endMs == null || s.getWindowEnd() > endMs)) {
                endMs = s.getWindowEnd();
            }
        }
        total.put("count", count);
        total.put("errorCount", errorCount);
        total.put("startTime", startMs);
        total.put("endTime", endMs);
        total.put("apdex", MetricBuckets.apdex(buckets, APDEX_SATISFIED_MS, APDEX_TOLERATING_MS));
        vo.setTotal(total);
        return vo;
    }

    /**
     * 加载任务全部指标快照（按窗口起点、采样器名排序，保证时间序列有序）
     *
     * @param taskId 任务ID
     * @return 快照列表（可能为空）
     */
    public List<MetricSnapshot> snapshots(Long taskId) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<MetricSnapshot>()
                .eq(MetricSnapshot::getTaskId, taskId)
                .orderByAsc(MetricSnapshot::getWindowStart)
                .orderByAsc(MetricSnapshot::getSampler));
    }

    /**
     * 构建窗口时间序列：每窗口合并全节点全采样器桶，
     * [{t(窗口起点秒), tps(该窗全采样器count之和/窗口时长), errorCount, avgMs, p90, p95, p99, threads(max), recvKbps}]
     * 窗口时长取快照真实 (windowEnd-windowStart)，兼容 Agent 侧可配窗口（3s/10s 等）
     *
     * @param snapshots 任务全部快照
     * @return 有序时间序列
     */
    public List<Map<String, Object>> seriesOf(List<MetricSnapshot> snapshots) {
        Map<Long, Agg> windows = new TreeMap<>();
        for (MetricSnapshot s : snapshots) {
            if (s.getWindowStart() == null) {
                continue;
            }
            windows.computeIfAbsent(s.getWindowStart(), k -> new Agg()).add(s);
        }
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<Long, Agg> entry : windows.entrySet()) {
            Agg agg = entry.getValue();
            double windowSeconds = agg.windowSeconds();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", entry.getKey() / 1000);
            point.put("tps", round2(windowSeconds <= 0 ? 0 : agg.count * 1.0 / windowSeconds));
            point.put("errorCount", agg.errorCount);
            point.put("avgMs", agg.avgMs());
            point.put("p90", round2(MetricBuckets.percentile(agg.buckets, 90)));
            point.put("p95", round2(MetricBuckets.percentile(agg.buckets, 95)));
            point.put("p99", round2(MetricBuckets.percentile(agg.buckets, 99)));
            point.put("threads", agg.activeThreads);
            point.put("recvKbps", round2(windowSeconds <= 0 ? 0 : agg.bytes * 1.0 / windowSeconds / 1024));
            series.add(point);
        }
        return series;
    }

    /**
     * 构建采样器级指标列表（跨窗口跨节点合并）：
     * [{label, count, errorCount, tps(平均), peakTps(峰值), avgMs, minMs, maxMs, p50, p90, p95, p99(, p75, p999, bytes, sentBytes)}]
     * 峰值TPS = 该采样器逐窗口（多节点合并）count/窗口时长 的最大值
     *
     * @param snapshots      任务全部快照
     * @param withReportCols 是否附带报告额外字段（bytes/sentBytes/p75/p999）
     * @return 采样器指标列表
     */
    public List<Map<String, Object>> samplersSection(List<MetricSnapshot> snapshots, boolean withReportCols) {
        Map<String, Agg> byLabel = aggBySampler(snapshots);
        Map<String, Double> peakTpsByLabel = peakTpsBySampler(snapshots);
        double totalSeconds = windowSeconds(snapshots);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Agg> entry : byLabel.entrySet()) {
            Agg agg = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", entry.getKey());
            row.put("count", agg.count);
            row.put("errorCount", agg.errorCount);
            row.put("tps", round2(totalSeconds <= 0 ? 0 : agg.count * 1.0 / totalSeconds));
            row.put("peakTps", round2(peakTpsByLabel.getOrDefault(entry.getKey(), 0.0)));
            row.put("avgMs", agg.avgMs());
            row.put("minMs", agg.minOrNull());
            row.put("maxMs", agg.maxOrNull());
            row.put("p50", round2(MetricBuckets.percentile(agg.buckets, 50)));
            if (withReportCols) {
                row.put("p75", round2(MetricBuckets.percentile(agg.buckets, 75)));
            }
            row.put("p90", round2(MetricBuckets.percentile(agg.buckets, 90)));
            row.put("p95", round2(MetricBuckets.percentile(agg.buckets, 95)));
            row.put("p99", round2(MetricBuckets.percentile(agg.buckets, 99)));
            if (withReportCols) {
                row.put("p999", round2(MetricBuckets.percentile(agg.buckets, 99.9)));
                row.put("bytes", agg.bytes);
                row.put("sentBytes", agg.sentBytes);
            }
            list.add(row);
        }
        return list;
    }

    /**
     * 计算各采样器的峰值 TPS：按 (采样器, 窗口) 合并多节点 count，
     * 除以该窗口真实时长后取各窗口最大值（反映瞬时吞吐尖峰）
     *
     * @param snapshots 任务全部快照
     * @return 采样器名 → 峰值 TPS
     */
    public Map<String, Double> peakTpsBySampler(List<MetricSnapshot> snapshots) {
        Map<String, Map<Long, Agg>> byLabelWindow = new LinkedHashMap<>();
        for (MetricSnapshot s : snapshots) {
            if (s.getWindowStart() == null) {
                continue;
            }
            byLabelWindow.computeIfAbsent(s.getSampler(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(s.getWindowStart(), k -> new Agg()).add(s);
        }
        Map<String, Double> peaks = new LinkedHashMap<>();
        byLabelWindow.forEach((label, windows) -> {
            double peak = 0;
            for (Agg agg : windows.values()) {
                double seconds = agg.windowSeconds();
                if (seconds > 0) {
                    peak = Math.max(peak, agg.count / seconds);
                }
            }
            peaks.put(label, peak);
        });
        return peaks;
    }

    /**
     * 按采样器名合并快照（跨窗口、跨节点）
     *
     * @param snapshots 任务全部快照
     * @return 采样器名 → 聚合器（LinkedHashMap 保序）
     */
    public Map<String, Agg> aggBySampler(List<MetricSnapshot> snapshots) {
        Map<String, Agg> map = new LinkedHashMap<>();
        for (MetricSnapshot s : snapshots) {
            map.computeIfAbsent(s.getSampler(), k -> new Agg()).add(s);
        }
        return map;
    }

    /**
     * 按节点合并快照（跨窗口、跨采样器）
     *
     * @param snapshots 任务全部快照
     * @return 节点标识 → 聚合器（LinkedHashMap 保序）
     */
    public Map<String, Agg> aggByNode(List<MetricSnapshot> snapshots) {
        Map<String, Agg> map = new LinkedHashMap<>();
        for (MetricSnapshot s : snapshots) {
            map.computeIfAbsent(s.getNodeKey(), k -> new Agg()).add(s);
        }
        return map;
    }

    /**
     * 全任务整体合并（跨窗口、跨节点、跨采样器）
     *
     * @param snapshots 任务全部快照
     * @return 整体聚合器
     */
    public Agg aggTotal(List<MetricSnapshot> snapshots) {
        Agg total = new Agg();
        for (MetricSnapshot s : snapshots) {
            total.add(s);
        }
        return total;
    }

    /**
     * 计算观测窗口总秒数（各窗口真实时长求和，兼容 Agent 侧可配窗口 3s/10s 等），
     * 用于采样器级平均 TPS 估算
     *
     * @param snapshots 任务全部快照
     * @return 观测总秒数（无快照返回 0）
     */
    public double windowSeconds(List<MetricSnapshot> snapshots) {
        Map<Long, Agg> windows = new LinkedHashMap<>();
        for (MetricSnapshot s : snapshots) {
            if (s.getWindowStart() == null) {
                continue;
            }
            windows.computeIfAbsent(s.getWindowStart(), k -> new Agg()).add(s);
        }
        return windows.values().stream().mapToDouble(Agg::windowSeconds).sum();
    }

    /**
     * UPSERT 单采样器窗口快照：先 INSERT，唯一键冲突时按唯一键整行覆盖 UPDATE（重试幂等）
     *
     * @param taskId     任务ID
     * @param nodeKey    节点标识
     * @param metric     采样器聚合指标
     * @param windowStart 窗口起点毫秒
     * @param windowEnd   窗口终点毫秒
     */
    private void upsertSnapshot(Long taskId, String nodeKey, AgentMetricsRequest.SamplerMetric metric,
                                Long windowStart, Long windowEnd) {
        if (!StringUtils.hasText(metric.getLabel()) || windowStart == null || windowEnd == null) {
            throw new BizException(4012, "samplers.label与窗口时间不能为空");
        }
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setTaskId(taskId);
        snapshot.setNodeKey(nodeKey);
        snapshot.setSampler(metric.getLabel());
        snapshot.setWindowStart(windowStart);
        snapshot.setWindowEnd(windowEnd);
        snapshot.setSampleCount(nvl(metric.getCount()));
        snapshot.setErrorCount(nvl(metric.getErrorCount()));
        snapshot.setBytes(nvl(metric.getBytes()));
        snapshot.setSentBytes(nvl(metric.getSentBytes()));
        snapshot.setActiveThreads(metric.getActiveThreads() == null ? 0 : metric.getActiveThreads());
        snapshot.setMinMs(metric.getMinMs());
        snapshot.setMaxMs(metric.getMaxMs());
        snapshot.setSumMs(nvl(metric.getSumMs()));
        snapshot.setBuckets(metric.getBuckets());
        try {
            snapshotMapper.insert(snapshot);
        } catch (DuplicateKeyException e) {
            snapshotMapper.update(null, new LambdaUpdateWrapper<MetricSnapshot>()
                    .set(MetricSnapshot::getWindowEnd, windowEnd)
                    .set(MetricSnapshot::getSampleCount, snapshot.getSampleCount())
                    .set(MetricSnapshot::getErrorCount, snapshot.getErrorCount())
                    .set(MetricSnapshot::getBytes, snapshot.getBytes())
                    .set(MetricSnapshot::getSentBytes, snapshot.getSentBytes())
                    .set(MetricSnapshot::getActiveThreads, snapshot.getActiveThreads())
                    .set(MetricSnapshot::getMinMs, snapshot.getMinMs())
                    .set(MetricSnapshot::getMaxMs, snapshot.getMaxMs())
                    .set(MetricSnapshot::getSumMs, snapshot.getSumMs())
                    .set(MetricSnapshot::getBuckets, snapshot.getBuckets())
                    .eq(MetricSnapshot::getTaskId, taskId)
                    .eq(MetricSnapshot::getNodeKey, nodeKey)
                    .eq(MetricSnapshot::getSampler, snapshot.getSampler())
                    .eq(MetricSnapshot::getWindowStart, windowStart));
        }
    }

    /**
     * 错误样本入库：每窗口最多取前 10 条，且全任务累计不超过 200 条（超出丢弃）
     *
     * @param taskId  任务ID
     * @param nodeKey 节点标识
     * @param errors  本窗口错误样本列表
     */
    private void saveErrorSamples(Long taskId, String nodeKey, List<AgentMetricsRequest.ErrorItem> errors) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        Long existing = errorSampleMapper.selectCount(new LambdaQueryWrapper<ErrorSample>()
                .eq(ErrorSample::getTaskId, taskId));
        int capacity = MAX_ERROR_SAMPLES_PER_TASK - (existing == null ? 0 : existing.intValue());
        int saved = 0;
        for (AgentMetricsRequest.ErrorItem item : errors) {
            if (saved >= MAX_ERRORS_PER_WINDOW || capacity <= 0) {
                break;
            }
            ErrorSample sample = new ErrorSample();
            sample.setTaskId(taskId);
            sample.setNodeKey(nodeKey);
            sample.setSampler(item.getLabel());
            sample.setResponseCode(item.getCode());
            sample.setMessage(item.getMsg());
            sample.setTs(item.getTs());
            errorSampleMapper.insert(sample);
            saved++;
            capacity--;
        }
    }

    /**
     * Long 空值安全转 long
     *
     * @param value 可空 Long
     * @return null 视为 0
     */
    private long nvl(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 数值保留两位小数
     *
     * @param value 原值
     * @return 四舍五入两位小数
     */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 快照聚合器：跨节点/窗口/采样器累加 count、bytes、桶计数等，
     * min/max 取极值、activeThreads 取最大值，供指标查询与报告聚合共用
     */
    public static class Agg {

        /** 样本总数 */
        public long count;

        /** 错误总数 */
        public long errorCount;

        /** 接收字节总数 */
        public long bytes;

        /** 发送字节总数 */
        public long sentBytes;

        /** 响应时间总和（毫秒） */
        public long sumMs;

        /** 最小响应时间（毫秒，Integer.MAX_VALUE 表示尚无样本） */
        public int minMs = Integer.MAX_VALUE;

        /** 最大响应时间（毫秒） */
        public int maxMs = 0;

        /** 活跃线程数峰值 */
        public int activeThreads = 0;

        /** 窗口时长（毫秒，首条快照记录 end-start；0 表示未知） */
        public long windowMs = 0;

        /** 合并后的对数桶计数 */
        public final long[] buckets = MetricBuckets.emptyBuckets();

        /**
         * 累加一条快照（null 数值按 0 处理）
         *
         * @param s 指标快照
         */
        public void add(MetricSnapshot s) {
            count += s.getSampleCount() == null ? 0 : s.getSampleCount();
            errorCount += s.getErrorCount() == null ? 0 : s.getErrorCount();
            bytes += s.getBytes() == null ? 0 : s.getBytes();
            sentBytes += s.getSentBytes() == null ? 0 : s.getSentBytes();
            sumMs += s.getSumMs() == null ? 0 : s.getSumMs();
            if (s.getMinMs() != null && s.getMinMs() < minMs) {
                minMs = s.getMinMs();
            }
            if (s.getMaxMs() != null && s.getMaxMs() > maxMs) {
                maxMs = s.getMaxMs();
            }
            if (s.getActiveThreads() != null && s.getActiveThreads() > activeThreads) {
                activeThreads = s.getActiveThreads();
            }
            if (windowMs <= 0 && s.getWindowStart() != null && s.getWindowEnd() != null
                    && s.getWindowEnd() > s.getWindowStart()) {
                windowMs = s.getWindowEnd() - s.getWindowStart();
            }
            MetricBuckets.merge(buckets, MetricBuckets.parse(s.getBuckets()));
        }

        /**
         * 窗口时长（秒，未知返回 0）
         *
         * @return 窗口秒数
         */
        public double windowSeconds() {
            return windowMs <= 0 ? 0 : windowMs / 1000.0;
        }

        /**
         * 平均响应时间（毫秒，无样本返回 0）
         *
         * @return 平均值保留两位小数
         */
        public double avgMs() {
            return count <= 0 ? 0 : Math.round(sumMs * 100.0 / count) / 100.0;
        }

        /**
         * 最小响应时间（无样本返回 null）
         *
         * @return 最小值或 null
         */
        public Integer minOrNull() {
            return minMs == Integer.MAX_VALUE ? null : minMs;
        }

        /**
         * 最大响应时间（无样本返回 null）
         *
         * @return 最大值或 null
         */
        public Integer maxOrNull() {
            return count <= 0 ? null : maxMs;
        }
    }
}
