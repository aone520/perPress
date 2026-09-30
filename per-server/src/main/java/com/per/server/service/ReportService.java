package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.per.server.common.BizException;
import com.per.server.dto.TaskReportVO;
import com.per.server.entity.ErrorSample;
import com.per.server.entity.MetricSnapshot;
import com.per.server.entity.Node;
import com.per.server.entity.TestReport;
import com.per.server.entity.TestTask;
import com.per.server.mapper.ErrorSampleMapper;
import com.per.server.mapper.NodeMapper;
import com.per.server.mapper.TestReportMapper;
import com.per.server.mapper.TestTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 报告服务：任务结束后将指标快照/错误样本聚合为测试报告并固化到 test_report（5 个 JSON 分区）。
 * 查询时优先返回固化报告（finalized=true），未结束任务实时聚合并标记 finalized=false
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    /** HTML 报告时间格式（起止时间/错误样本时间） */
    private static final DateTimeFormatter HTML_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** HTML 报告图表 x 轴时间格式 */
    private static final DateTimeFormatter HTML_AXIS_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 空数据占位块 */
    private static final String EMPTY_BLOCK = "<div class=\"empty\">无数据</div>";

    /** HTML 报告内联样式（单文件自包含，无外链与 JS 依赖，含打印适配） */
    private static final String REPORT_CSS = """
            <style>
              * { box-sizing: border-box; }
              body { font-family: "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif;
                     color: #1e293b; background: #f1f5f9; margin: 0; padding: 16px; }
              .page { max-width: 1040px; margin: 0 auto; background: #fff; border-radius: 8px;
                      padding: 24px 28px; box-shadow: 0 1px 3px rgba(0,0,0,.08); }
              h1 { font-size: 22px; margin: 0 0 10px; }
              h2 { font-size: 16px; margin: 22px 0 10px; padding-left: 8px; border-left: 4px solid #2563eb; }
              h3 { font-size: 14px; margin: 14px 0 8px; color: #475569; }
              .meta { display: flex; flex-wrap: wrap; gap: 6px 24px; font-size: 13px; color: #475569;
                      background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 10px 12px; }
              .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
              .kpi { border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px; background: #f8fafc; }
              .kpi-label { font-size: 12px; color: #64748b; margin-bottom: 6px; }
              .kpi-value { font-size: 20px; font-weight: 600; }
              table { width: 100%; border-collapse: collapse; font-size: 12px; }
              th, td { border: 1px solid #e2e8f0; padding: 6px 8px; text-align: right; white-space: nowrap; }
              th { background: #f1f5f9; color: #334155; font-weight: 600; }
              td.tl, th.tl { text-align: left; }
              .empty { color: #94a3b8; font-size: 13px; padding: 10px; background: #f8fafc;
                       border: 1px dashed #cbd5e1; border-radius: 6px; text-align: center; }
              .chart { margin-top: 6px; }
              .chart svg { width: 100%; height: auto; display: block; }
              .grid { stroke: #e2e8f0; stroke-width: 1; }
              .tick { font-size: 10px; fill: #64748b; }
              .axis-title { font-size: 11px; fill: #475569; }
              .legend { font-size: 11px; fill: #334155; }
              footer { margin-top: 24px; font-size: 12px; color: #94a3b8; text-align: center; }
              @media print {
                body { background: #fff; padding: 0; }
                .page { box-shadow: none; max-width: none; padding: 0; }
                section { page-break-inside: avoid; }
              }
            </style>
            """;

    private final TestTaskMapper taskMapper;
    private final TestReportMapper reportMapper;
    private final ErrorSampleMapper errorSampleMapper;
    private final NodeMapper nodeMapper;
    private final MetricService metricService;
    private final ObjectMapper objectMapper;

    /**
     * 聚合并固化测试报告（任务收尾时由 TaskOrchestrator 调用，事务内执行，task_id 唯一可重复覆盖）
     *
     * @param taskId 任务ID
     * @return 固化后的报告视图（finalized=true）
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskReportVO aggregate(Long taskId) {
        Sections sections = buildSections(taskId);
        TestReport existing = reportMapper.selectOne(new LambdaQueryWrapper<TestReport>()
                .eq(TestReport::getTaskId, taskId));
        if (existing == null) {
            TestReport report = new TestReport();
            report.setTaskId(taskId);
            report.setSummaryJson(writeJson(sections.summary));
            report.setSamplersJson(writeJson(sections.samplers));
            report.setNodesJson(writeJson(sections.nodes));
            report.setErrorsJson(writeJson(sections.errors));
            report.setSeriesJson(writeJson(sections.series));
            reportMapper.insert(report);
        } else {
            reportMapper.update(null, new LambdaUpdateWrapper<TestReport>()
                    .set(TestReport::getSummaryJson, writeJson(sections.summary))
                    .set(TestReport::getSamplersJson, writeJson(sections.samplers))
                    .set(TestReport::getNodesJson, writeJson(sections.nodes))
                    .set(TestReport::getErrorsJson, writeJson(sections.errors))
                    .set(TestReport::getSeriesJson, writeJson(sections.series))
                    .eq(TestReport::getTaskId, taskId));
        }
        TaskReportVO vo = new TaskReportVO();
        vo.setFinalized(true);
        vo.setSummary(objectMapper.valueToTree(sections.summary));
        vo.setSamplers(objectMapper.valueToTree(sections.samplers));
        vo.setNodes(objectMapper.valueToTree(sections.nodes));
        vo.setErrors(objectMapper.valueToTree(sections.errors));
        vo.setSeries(objectMapper.valueToTree(sections.series));
        return vo;
    }

    /**
     * 查询任务报告：已有固化报告直接返回（finalized=true）；
     * 未固化（任务未结束）则基于当前快照实时聚合返回（finalized=false，不落库）
     *
     * @param taskId 任务ID
     * @return 报告视图
     */
    public TaskReportVO report(Long taskId) {
        requireTask(taskId);
        TestReport existing = reportMapper.selectOne(new LambdaQueryWrapper<TestReport>()
                .eq(TestReport::getTaskId, taskId));
        if (existing != null) {
            TaskReportVO vo = new TaskReportVO();
            vo.setFinalized(true);
            vo.setSummary(readTree(existing.getSummaryJson()));
            vo.setSamplers(readTree(existing.getSamplersJson()));
            vo.setNodes(readTree(existing.getNodesJson()));
            vo.setErrors(readTree(existing.getErrorsJson()));
            vo.setSeries(readTree(existing.getSeriesJson()));
            return vo;
        }
        Sections sections = buildSections(taskId);
        TaskReportVO vo = new TaskReportVO();
        vo.setFinalized(false);
        vo.setSummary(objectMapper.valueToTree(sections.summary));
        vo.setSamplers(objectMapper.valueToTree(sections.samplers));
        vo.setNodes(objectMapper.valueToTree(sections.nodes));
        vo.setErrors(objectMapper.valueToTree(sections.errors));
        vo.setSeries(objectMapper.valueToTree(sections.series));
        return vo;
    }

    /**
     * 导出任务离线 HTML 报告：数据来源与 report(taskId) 一致（有固化报告读固化，未固化实时聚合），
     * 生成单文件自包含 HTML 字符串（内联 CSS、无任何外链与 JS 依赖、打印友好）。
     * 内容依次为：页头概要、KPI 网格卡、RT 分位数表、事务明细表、节点明细表、
     * 错误分析（错误码分布/TOP错误事务/错误样本前50条）、两幅纯 SVG 折线图（TPS 时间线与 RT 三线）
     *
     * @param taskId 任务ID
     * @return 自包含 HTML 报告字符串
     */
    public String exportHtml(Long taskId) {
        TaskReportVO report = report(taskId);
        JsonNode summary = report.getSummary();
        JsonNode samplers = report.getSamplers();
        JsonNode nodes = report.getNodes();
        JsonNode errors = report.getErrors();
        JsonNode series = report.getSeries();

        StringBuilder html = new StringBuilder(64 * 1024);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>PerPress 压测报告 - ").append(esc(jsonText(summary, "taskNo"))).append("</title>\n");
        html.append(REPORT_CSS);
        html.append("</head>\n<body>\n<div class=\"page\">\n");
        appendHtmlHeader(html, summary);
        appendHtmlKpi(html, summary);
        appendHtmlRtTable(html, summary.path("rt"));
        appendHtmlSamplerTable(html, samplers);
        appendHtmlNodeTable(html, nodes);
        appendHtmlErrorSection(html, errors);
        html.append("<section><h2>TPS 时间线</h2>");
        html.append(buildLineChart("TPS", new String[]{"TPS"}, new String[]{"#2563eb"},
                List.of(seriesValues(series, "tps")), seriesXMs(series)));
        html.append("</section>\n");
        html.append("<section><h2>响应时间曲线（Avg / P90 / P95 / P99）</h2>");
        html.append(buildLineChart("响应时间(ms)", new String[]{"Avg", "P90", "P95", "P99"},
                new String[]{"#2563eb", "#16a34a", "#f59e0b", "#ef4444"},
                List.of(seriesValues(series, "avgMs"), seriesValues(series, "p90"),
                        seriesValues(series, "p95"), seriesValues(series, "p99")),
                seriesXMs(series)));
        html.append("</section>\n");
        html.append("<footer>PerPress 压测平台 · 报告生成时间：")
                .append(formatDateTime(System.currentTimeMillis())).append("</footer>\n");
        html.append("</div>\n</body>\n</html>");
        return html.toString();
    }

    /**
     * 拼接 HTML 页头：任务号/名称/压测模式（中文映射）/节点数/起止时间/时长
     *
     * @param html    输出缓冲
     * @param summary 汇总分区
     */
    private void appendHtmlHeader(StringBuilder html, JsonNode summary) {
        html.append("<header><h1>PerPress 压测报告</h1><div class=\"meta\">");
        html.append("<span>任务号：<b>").append(esc(jsonText(summary, "taskNo"))).append("</b></span>");
        html.append("<span>任务名称：<b>").append(esc(jsonText(summary, "name"))).append("</b></span>");
        html.append("<span>压测模式：<b>").append(modeName(jsonText(summary, "mode"))).append("</b></span>");
        html.append("<span>节点数：<b>").append(jsonLong(summary, "nodeCount")).append("</b></span>");
        html.append("<span>起止时间：<b>").append(formatDateTime(jsonLongOrNull(summary, "startTime")))
                .append(" ~ ").append(formatDateTime(jsonLongOrNull(summary, "endTime"))).append("</b></span>");
        html.append("<span>时长：<b>").append(formatDuration(jsonLong(summary, "durationSeconds"))).append("</b></span>");
        html.append("</div></header>\n");
    }

    /**
     * 拼接 KPI 网格卡（div grid）：总请求/总错误/错误率/平均TPS/峰值TPS/峰值线程
     *
     * @param html    输出缓冲
     * @param summary 汇总分区
     */
    private void appendHtmlKpi(StringBuilder html, JsonNode summary) {
        html.append("<section><h2>关键指标</h2><div class=\"kpi-grid\">");
        kpiCard(html, "APDEX", String.format(Locale.ROOT, "%.3f", jsonDouble(summary, "apdex")));
        kpiCard(html, "总请求", String.format(Locale.ROOT, "%,d", jsonLong(summary, "totalCount")));
        kpiCard(html, "总错误", String.format(Locale.ROOT, "%,d", jsonLong(summary, "totalErrorCount")));
        kpiCard(html, "错误率", String.format(Locale.ROOT, "%.2f%%", jsonDouble(summary, "errorRate")));
        kpiCard(html, "平均TPS", String.format(Locale.ROOT, "%.2f", jsonDouble(summary, "avgTps")));
        kpiCard(html, "峰值TPS", String.format(Locale.ROOT, "%.2f", jsonDouble(summary, "peakTps")));
        kpiCard(html, "峰值线程", String.valueOf(jsonLong(summary, "peakThreads")));
        html.append("</div></section>\n");
    }

    /**
     * 拼接单个 KPI 卡片
     *
     * @param html  输出缓冲
     * @param label 指标名
     * @param value 指标值文本
     */
    private void kpiCard(StringBuilder html, String label, String value) {
        html.append("<div class=\"kpi\"><div class=\"kpi-label\">").append(label)
                .append("</div><div class=\"kpi-value\">").append(value).append("</div></div>");
    }

    /**
     * 拼接 RT 分位数表：min/avg/p50/p75/p90/p95/p99/p999/max（保留 1 位小数，单位 ms），空数据显示"无数据"
     *
     * @param html 输出缓冲
     * @param rt   RT 分位对象
     */
    private void appendHtmlRtTable(StringBuilder html, JsonNode rt) {
        html.append("<section><h2>响应时间分位数（ms）</h2>");
        if (rt == null || rt.isMissingNode() || rt.isEmpty()) {
            html.append(EMPTY_BLOCK);
        } else {
            String[] fields = {"min", "avg", "p50", "p75", "p90", "p95", "p99", "p999", "max"};
            String[] titles = {"Min", "Avg", "P50", "P75", "P90", "P95", "P99", "P99.9", "Max"};
            html.append("<table><thead><tr>");
            for (String title : titles) {
                html.append("<th>").append(title).append("</th>");
            }
            html.append("</tr></thead><tbody><tr>");
            for (String field : fields) {
                html.append("<td>").append(jsonMs(rt, field)).append("</td>");
            }
            html.append("</tr></tbody></table>");
        }
        html.append("</section>\n");
    }

    /**
     * 拼接事务明细表：label/请求数/错误率/平均TPS/峰值TPS/Min/Avg/P90/P95/P99/Max，空数据显示"无数据"
     *
     * @param html     输出缓冲
     * @param samplers 采样器级指标数组
     */
    private void appendHtmlSamplerTable(StringBuilder html, JsonNode samplers) {
        html.append("<section><h2>事务明细</h2>");
        if (samplers == null || !samplers.isArray() || samplers.isEmpty()) {
            html.append(EMPTY_BLOCK);
        } else {
            html.append("<table><thead><tr><th class=\"tl\">事务</th><th>请求数</th><th>错误率</th><th>平均TPS</th><th>峰值TPS</th>")
                    .append("<th>Min</th><th>Avg</th><th>P90</th><th>P95</th><th>P99</th>")
                    .append("<th>Max</th></tr></thead><tbody>");
            for (JsonNode row : samplers) {
                long count = jsonLong(row, "count");
                double errorRate = count <= 0 ? 0 : jsonLong(row, "errorCount") * 100.0 / count;
                html.append("<tr><td class=\"tl\">").append(esc(jsonText(row, "label"))).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%,d", count)).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%.2f%%", errorRate)).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%.2f", jsonDouble(row, "tps"))).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%.2f", jsonDouble(row, "peakTps"))).append("</td><td>")
                        .append(jsonMs(row, "minMs")).append("</td><td>")
                        .append(jsonMs(row, "avgMs")).append("</td><td>")
                        .append(jsonMs(row, "p90")).append("</td><td>")
                        .append(jsonMs(row, "p95")).append("</td><td>")
                        .append(jsonMs(row, "p99")).append("</td><td>")
                        .append(jsonMs(row, "maxMs")).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</section>\n");
    }

    /**
     * 拼接节点明细表：nodeKey（截前 8 位）/请求数/TPS/错误数/Avg/P95/P99，空数据显示"无数据"
     *
     * @param html  输出缓冲
     * @param nodes 节点级指标数组
     */
    private void appendHtmlNodeTable(StringBuilder html, JsonNode nodes) {
        html.append("<section><h2>节点明细</h2>");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            html.append(EMPTY_BLOCK);
        } else {
            html.append("<table><thead><tr><th class=\"tl\">节点</th><th>请求数</th><th>平均TPS</th><th>错误数</th>")
                    .append("<th>Avg(ms)</th><th>P95(ms)</th><th>P99(ms)</th></tr></thead><tbody>");
            for (JsonNode row : nodes) {
                // 节点显示：主机名（IP 副行），缺失时回退 nodeKey 前 8 位
                String hostname = jsonText(row, "hostname");
                String ip = jsonText(row, "ip");
                String nodeDisplay = (hostname == null || hostname.isBlank())
                        ? shortNodeKey(jsonText(row, "nodeKey"))
                        : hostname + (ip == null || ip.isBlank() ? "" : " (" + ip + ")");
                html.append("<tr><td class=\"tl\">").append(esc(nodeDisplay)).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%,d", jsonLong(row, "count"))).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%.2f", jsonDouble(row, "tps"))).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%,d", jsonLong(row, "errorCount"))).append("</td><td>")
                        .append(jsonMs(row, "avgMs")).append("</td><td>")
                        .append(jsonMs(row, "p95")).append("</td><td>")
                        .append(jsonMs(row, "p99")).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</section>\n");
    }

    /**
     * 拼接错误分析区块：错误码分布表（code/count）+ TOP 错误事务 + 错误样本前 50 条（时间/事务/码/信息）
     *
     * @param html   输出缓冲
     * @param errors 错误分析分区
     */
    private void appendHtmlErrorSection(StringBuilder html, JsonNode errors) {
        html.append("<section><h2>错误分析</h2>");
        JsonNode byCode = errors == null ? null : errors.get("byCode");
        html.append("<h3>错误码分布</h3>");
        if (byCode == null || !byCode.isArray() || byCode.isEmpty()) {
            html.append(EMPTY_BLOCK);
        } else {
            html.append("<table><thead><tr><th class=\"tl\">错误码</th><th>次数</th></tr></thead><tbody>");
            for (JsonNode row : byCode) {
                html.append("<tr><td class=\"tl\">").append(esc(jsonText(row, "code"))).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%,d", jsonLong(row, "count"))).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }

        JsonNode topSamplers = errors == null ? null : errors.get("topSamplers");
        html.append("<h3>TOP 错误事务</h3>");
        if (topSamplers == null || !topSamplers.isArray() || topSamplers.isEmpty()) {
            html.append(EMPTY_BLOCK);
        } else {
            html.append("<table><thead><tr><th class=\"tl\">事务</th><th>错误数</th><th>错误率</th></tr></thead><tbody>");
            for (JsonNode row : topSamplers) {
                html.append("<tr><td class=\"tl\">").append(esc(jsonText(row, "label"))).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%,d", jsonLong(row, "errorCount"))).append("</td><td>")
                        .append(String.format(Locale.ROOT, "%.2f%%", jsonDouble(row, "errorRate"))).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }

        JsonNode samples = errors == null ? null : errors.get("samples");
        html.append("<h3>错误样本（前 50 条）</h3>");
        if (samples == null || !samples.isArray() || samples.isEmpty()) {
            html.append(EMPTY_BLOCK);
        } else {
            html.append("<table><thead><tr><th>时间</th><th class=\"tl\">事务</th><th>码</th><th class=\"tl\">信息</th></tr></thead><tbody>");
            int limit = Math.min(50, samples.size());
            for (int i = 0; i < limit; i++) {
                JsonNode row = samples.get(i);
                // 时间优先样本真实 ts，老数据为空时回退入库时间 createTime
                Long sampleTs = jsonLongOrNull(row, "ts");
                if (sampleTs == null) {
                    sampleTs = jsonLongOrNull(row, "createTime");
                }
                html.append("<tr><td>").append(formatDateTime(sampleTs)).append("</td><td class=\"tl\">")
                        .append(esc(jsonText(row, "sampler"))).append("</td><td>")
                        .append(esc(jsonText(row, "responseCode"))).append("</td><td class=\"tl\">")
                        .append(esc(jsonText(row, "message"))).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</section>\n");
    }

    /**
     * 生成纯 SVG 折线图（服务端字符串拼接，viewBox 960x240）：
     * 含横向网格与 y 轴刻度、纵向网格与 x 轴时间标签（HH:mm:ss，每 6 窗标一个防重叠）、
     * 中文轴标题与图例；无数据时返回"无数据"占位块
     *
     * @param yTitle    y 轴标题（中文，如 TPS / 响应时间(ms)）
     * @param names     图例名称数组（与折线一一对应）
     * @param colors    折线颜色数组（与折线一一对应）
     * @param lines     每条折线的数值序列（与 x 轴窗口一一对应）
     * @param xMs       各窗口起点毫秒时间戳序列
     * @return 图表 HTML 片段
     */
    private String buildLineChart(String yTitle, String[] names, String[] colors,
                                  List<List<Double>> lines, List<Long> xMs) {
        int n = xMs.size();
        if (n == 0 || lines.isEmpty()) {
            return "<div class=\"chart\">" + EMPTY_BLOCK + "</div>";
        }
        final double left = 56, top = 30, right = 944, bottom = 206;
        double max = 0;
        for (List<Double> line : lines) {
            for (double v : line) {
                if (v > max) {
                    max = v;
                }
            }
        }
        double yMax = max > 0 ? max * 1.15 : 100;
        String tickFmt = yMax >= 10 ? "%.0f" : "%.1f";
        StringBuilder svg = new StringBuilder(8 * 1024);
        svg.append("<div class=\"chart\"><svg viewBox=\"0 0 960 240\" preserveAspectRatio=\"xMidYMid meet\">");
        // 横向网格线与 y 轴刻度（5 档）
        for (int g = 0; g <= 4; g++) {
            double y = top + (bottom - top) * g / 4.0;
            double value = yMax * (1 - g / 4.0);
            svg.append("<line class=\"grid\" x1=\"").append(num(left)).append("\" y1=\"").append(num(y))
                    .append("\" x2=\"").append(num(right)).append("\" y2=\"").append(num(y)).append("\"/>");
            svg.append("<text class=\"tick\" x=\"").append(num(left - 6)).append("\" y=\"").append(num(y + 3))
                    .append("\" text-anchor=\"end\">").append(String.format(Locale.ROOT, tickFmt, value)).append("</text>");
        }
        // 纵向网格线与 x 轴时间标签（每 6 窗标一个；窗口不足 6 个时逐窗标注）
        int step = n > 6 ? 6 : 1;
        for (int i = 0; i < n; i += step) {
            double x = xOf(i, n, left, right);
            svg.append("<line class=\"grid\" x1=\"").append(num(x)).append("\" y1=\"").append(num(top))
                    .append("\" x2=\"").append(num(x)).append("\" y2=\"").append(num(bottom)).append("\"/>");
            svg.append("<text class=\"tick\" x=\"").append(num(x))
                    .append("\" y=\"222\" text-anchor=\"middle\">").append(formatAxisTime(xMs.get(i))).append("</text>");
        }
        // 中文轴标题
        svg.append("<text class=\"axis-title\" x=\"10\" y=\"16\">").append(esc(yTitle)).append("</text>");
        svg.append("<text class=\"axis-title\" x=\"950\" y=\"234\" text-anchor=\"end\">时间</text>");
        // 折线
        for (int li = 0; li < lines.size(); li++) {
            List<Double> line = lines.get(li);
            svg.append("<polyline fill=\"none\" stroke=\"").append(colors[li]).append("\" stroke-width=\"1.6\" points=\"");
            for (int i = 0; i < n; i++) {
                double x = xOf(i, n, left, right);
                double y = bottom - (bottom - top) * line.get(i) / yMax;
                if (i > 0) {
                    svg.append(' ');
                }
                svg.append(num(x)).append(',').append(num(y));
            }
            svg.append("\"/>");
        }
        // 图例（顶部，避开左侧轴标题）
        double lx = 150;
        for (int li = 0; li < names.length; li++) {
            svg.append("<line x1=\"").append(num(lx)).append("\" y1=\"14\" x2=\"").append(num(lx + 16))
                    .append("\" y2=\"14\" stroke=\"").append(colors[li]).append("\" stroke-width=\"2\"/>");
            svg.append("<text class=\"legend\" x=\"").append(num(lx + 21)).append("\" y=\"18\">")
                    .append(esc(names[li])).append("</text>");
            lx += 90;
        }
        svg.append("</svg></div>");
        return svg.toString();
    }

    /**
     * 计算第 i 个窗口的 x 坐标（窗口数=1 时居中）
     *
     * @param i     窗口序号
     * @param n     窗口总数
     * @param left  绘图区左边界
     * @param right 绘图区右边界
     * @return x 坐标
     */
    private double xOf(int i, int n, double left, double right) {
        return n <= 1 ? (left + right) / 2 : left + (right - left) * i / (n - 1);
    }

    /**
     * 提取时间序列中各窗口起点毫秒时间戳（series.t 为秒，×1000 换算毫秒）
     *
     * @param series 时间序列分区
     * @return 窗口起点毫秒列表
     */
    private List<Long> seriesXMs(JsonNode series) {
        List<Long> list = new ArrayList<>();
        if (series != null && series.isArray()) {
            for (JsonNode point : series) {
                list.add(jsonLong(point, "t") * 1000);
            }
        }
        return list;
    }

    /**
     * 提取时间序列中指定字段的双精度值序列（如 tps/avgMs/p95/p99）
     *
     * @param series 时间序列分区
     * @param field  字段名
     * @return 字段值列表
     */
    private List<Double> seriesValues(JsonNode series, String field) {
        List<Double> list = new ArrayList<>();
        if (series != null && series.isArray()) {
            for (JsonNode point : series) {
                list.add(jsonDouble(point, field));
            }
        }
        return list;
    }

    /**
     * 压测模式中文映射：CONCURRENT 并发模式 / FIXED_TPS 固定TPS / STEPPED 阶梯压测，未知原样返回
     *
     * @param mode 模式英文标识
     * @return 中文名称
     */
    private String modeName(String mode) {
        return switch (mode == null ? "" : mode) {
            case "CONCURRENT" -> "并发模式";
            case "FIXED_TPS" -> "固定TPS";
            case "STEPPED" -> "阶梯压测";
            default -> mode == null || mode.isEmpty() ? "-" : mode;
        };
    }

    /**
     * 节点标识缩短显示：超 8 位截前 8 位加省略号
     *
     * @param nodeKey 节点标识
     * @return 缩短后的节点标识
     */
    private String shortNodeKey(String nodeKey) {
        if (nodeKey == null || nodeKey.isEmpty()) {
            return "-";
        }
        return nodeKey.length() <= 8 ? nodeKey : nodeKey.substring(0, 8) + "…";
    }

    /**
     * HTML 特殊字符转义（& < > " '），防注入与破坏页面结构
     *
     * @param text 原始文本
     * @return 转义后文本
     */
    private String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * 毫秒时间戳格式化为 yyyy-MM-dd HH:mm:ss，null 显示 "-"
     *
     * @param epochMs 毫秒时间戳（可空）
     * @return 格式化时间
     */
    private String formatDateTime(Long epochMs) {
        return epochMs == null ? "-" : HTML_TIME.format(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()));
    }

    /**
     * 窗口起点毫秒格式化为 HH:mm:ss（图表 x 轴标签）
     *
     * @param epochMs 毫秒时间戳
     * @return HH:mm:ss 文本
     */
    private String formatAxisTime(long epochMs) {
        return HTML_AXIS_TIME.format(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()));
    }

    /**
     * 时长秒数中文人性化（如 330 → 5分30秒，7325 → 2时2分5秒），非正数显示 "-"
     *
     * @param seconds 时长秒数
     * @return 中文时长文本
     */
    private String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "-";
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return h + "时" + m + "分" + s + "秒";
        }
        if (m > 0) {
            return m + "分" + s + "秒";
        }
        return s + "秒";
    }

    /**
     * JSON 对象取文本字段，缺失/空值返回空串
     *
     * @param node  JSON 对象
     * @param field 字段名
     * @return 文本值
     */
    private String jsonText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /**
     * JSON 对象取长整型字段，缺失/空值返回 0
     *
     * @param node  JSON 对象
     * @param field 字段名
     * @return 长整型值
     */
    private long jsonLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? 0L : v.asLong();
    }

    /**
     * JSON 对象取长整型字段，缺失/空值/非数值返回 null
     *
     * @param node  JSON 对象
     * @param field 字段名
     * @return 长整型值或 null
     */
    private Long jsonLongOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.canConvertToLong() ? null : v.asLong();
    }

    /**
     * JSON 对象取双精度字段，缺失/空值返回 0
     *
     * @param node  JSON 对象
     * @param field 字段名
     * @return 双精度值
     */
    private double jsonDouble(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? 0.0 : v.asDouble();
    }

    /**
     * JSON 对象取 RT 毫秒值并保留 1 位小数，缺失显示 "-"
     *
     * @param node  JSON 对象
     * @param field 字段名
     * @return 1 位小数文本或 "-"
     */
    private String jsonMs(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "-" : String.format(Locale.ROOT, "%.1f", v.asDouble());
    }

    /**
     * 数值格式化为 1 位小数字符串（SVG 坐标用）
     *
     * @param v 数值
     * @return 1 位小数文本
     */
    private String num(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * 构建报告五个分区：summary（含 rt 分位）/ samplers / nodes / errors / series
     *
     * @param taskId 任务ID
     * @return 五分区数据
     */
    private Sections buildSections(Long taskId) {
        TestTask task = requireTask(taskId);
        List<MetricSnapshot> snapshots = metricService.snapshots(taskId);
        List<ErrorSample> errorSamples = errorSampleMapper.selectList(new LambdaQueryWrapper<ErrorSample>()
                .eq(ErrorSample::getTaskId, taskId)
                .orderByAsc(ErrorSample::getId));

        MetricService.Agg total = metricService.aggTotal(snapshots);
        List<Map<String, Object>> series = metricService.seriesOf(snapshots);
        List<Map<String, Object>> samplers = metricService.samplersSection(snapshots, true);
        int nodeCount = StringUtils.hasText(task.getNodeKeys())
                ? task.getNodeKeys().split(",").length : 0;
        int durationSeconds = resolveDurationSeconds(task, snapshots);

        Map<String, Object> rt = new LinkedHashMap<>();
        rt.put("min", total.minOrNull());
        rt.put("avg", total.avgMs());
        rt.put("p50", round2(MetricBuckets.percentile(total.buckets, 50)));
        rt.put("p75", round2(MetricBuckets.percentile(total.buckets, 75)));
        rt.put("p90", round2(MetricBuckets.percentile(total.buckets, 90)));
        rt.put("p95", round2(MetricBuckets.percentile(total.buckets, 95)));
        rt.put("p99", round2(MetricBuckets.percentile(total.buckets, 99)));
        rt.put("p999", round2(MetricBuckets.percentile(total.buckets, 99.9)));
        rt.put("max", total.maxOrNull());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskNo", task.getTaskNo());
        summary.put("name", task.getName());
        summary.put("mode", task.getMode());
        summary.put("nodeCount", nodeCount);
        summary.put("startTime", epochMs(task.getStartTime()));
        summary.put("endTime", epochMs(task.getEndTime()));
        summary.put("durationSeconds", durationSeconds);
        summary.put("totalCount", total.count);
        summary.put("totalErrorCount", total.errorCount);
        summary.put("errorRate", total.count <= 0 ? 0
                : round2(total.errorCount * 100.0 / total.count));
        summary.put("avgTps", round2(total.count * 1.0 / durationSeconds));
        summary.put("peakTps", series.stream()
                .map(p -> toDouble(p.get("tps")))
                .max(Double::compare).orElse(0.0));
        summary.put("recvTotalKB", round2(total.bytes / 1024.0));
        summary.put("sentTotalKB", round2(total.sentBytes / 1024.0));
        summary.put("peakThreads", total.activeThreads);
        // APDEX 满意度指数（行业标准，JMeter Dashboard 首屏指标；阈值 500/1500ms）
        summary.put("apdex", MetricBuckets.apdex(total.buckets,
                MetricService.APDEX_SATISFIED_MS, MetricService.APDEX_TOLERATING_MS));
        summary.put("rt", rt);

        List<Map<String, Object>> nodes = new ArrayList<>();
        // 节点主机名映射（展示用，便于识别是哪台机器；节点已删除时回退 nodeKey）
        Map<String, MetricService.Agg> aggByNode = metricService.aggByNode(snapshots);
        Map<String, Node> nodeInfoMap = nodeMapper.selectList(new LambdaQueryWrapper<Node>()
                        .in(Node::getNodeKey, aggByNode.keySet()))
                .stream().collect(java.util.stream.Collectors.toMap(Node::getNodeKey, n -> n, (a, b) -> a));
        aggByNode.forEach((nodeKey, agg) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeKey", nodeKey);
            Node info = nodeInfoMap.get(nodeKey);
            row.put("hostname", info == null ? null : info.getHostname());
            row.put("ip", info == null ? null : info.getIp());
            row.put("count", agg.count);
            row.put("errorCount", agg.errorCount);
            row.put("tps", round2(agg.count * 1.0 / durationSeconds));
            row.put("avgMs", agg.avgMs());
            row.put("p95", round2(MetricBuckets.percentile(agg.buckets, 95)));
            row.put("p99", round2(MetricBuckets.percentile(agg.buckets, 99)));
            row.put("bytes", agg.bytes);
            nodes.add(row);
        });

        Map<String, Object> errors = buildErrorsSection(snapshots, errorSamples);

        Sections sections = new Sections();
        sections.summary = summary;
        sections.samplers = samplers;
        sections.nodes = nodes;
        sections.errors = errors;
        sections.series = series;
        return sections;
    }

    /**
     * 构建错误分析分区：byCode（按响应码计数）/ topSamplers（错误最多的采样器）/
     * samples（错误样本明细）/ timeline（窗口错误数时间线）
     *
     * @param snapshots    任务全部快照
     * @param errorSamples 错误样本列表
     * @return 错误分析分区
     */
    private Map<String, Object> buildErrorsSection(List<MetricSnapshot> snapshots,
                                                   List<ErrorSample> errorSamples) {
        Map<String, Long> byCode = new TreeMap<>();
        for (ErrorSample sample : errorSamples) {
            String code = StringUtils.hasText(sample.getResponseCode()) ? sample.getResponseCode() : "UNKNOWN";
            byCode.merge(code, 1L, Long::sum);
        }
        List<Map<String, Object>> byCodeList = new ArrayList<>();
        byCode.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", e.getKey());
                    row.put("count", e.getValue());
                    byCodeList.add(row);
                });

        List<Map<String, Object>> topSamplers = new ArrayList<>();
        metricService.aggBySampler(snapshots).forEach((label, agg) -> {
            if (agg.errorCount <= 0) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("errorCount", agg.errorCount);
            row.put("errorRate", agg.count <= 0 ? 0 : round2(agg.errorCount * 100.0 / agg.count));
            topSamplers.add(row);
        });
        topSamplers.sort((a, b) -> Long.compare(toLong(b.get("errorCount")), toLong(a.get("errorCount"))));

        List<Map<String, Object>> samples = new ArrayList<>();
        for (ErrorSample sample : errorSamples) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeKey", sample.getNodeKey());
            // 字段名与前端错误样本明细列对齐（sampler/responseCode），避免 Web 端取值落空
            row.put("sampler", sample.getSampler());
            row.put("responseCode", sample.getResponseCode());
            row.put("message", sample.getMessage());
            // ts 为样本真实时间（老数据可能为空），createTime 为入库时间兜底
            row.put("ts", sample.getTs());
            row.put("createTime", epochMs(sample.getCreateTime()));
            samples.add(row);
        }

        Map<Long, Long> timelineMap = new TreeMap<>();
        for (MetricSnapshot s : snapshots) {
            if (s.getWindowStart() == null || s.getErrorCount() == null) {
                continue;
            }
            timelineMap.merge(s.getWindowStart(), s.getErrorCount(), Long::sum);
        }
        List<Map<String, Object>> timeline = new ArrayList<>();
        timelineMap.forEach((windowStart, errorCount) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", windowStart / 1000);
            row.put("errorCount", errorCount);
            timeline.add(row);
        });

        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("byCode", byCodeList);
        errors.put("topSamplers", topSamplers);
        errors.put("samples", samples);
        errors.put("timeline", timeline);
        return errors;
    }

    /**
     * 解析任务时长（秒）：优先用任务起止时间，异常时回退观测窗口总秒数，最小取 1 防除零
     *
     * @param task      任务实体
     * @param snapshots 任务全部快照
     * @return 时长秒数
     */
    private int resolveDurationSeconds(TestTask task, List<MetricSnapshot> snapshots) {
        if (task.getStartTime() != null && task.getEndTime() != null) {
            long seconds = Duration.between(task.getStartTime(), task.getEndTime()).getSeconds();
            if (seconds > 0) {
                return (int) seconds;
            }
        }
        return (int) Math.max(1, metricService.windowSeconds(snapshots));
    }

    /**
     * 校验任务存在，不存在抛业务异常
     *
     * @param taskId 任务ID
     * @return 任务实体
     */
    private TestTask requireTask(Long taskId) {
        TestTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("任务不存在");
        }
        return task;
    }

    /**
     * LocalDateTime 转毫秒时间戳（东八区），null 安全
     *
     * @param time 时间（可空）
     * @return 毫秒时间戳或 null
     */
    private Long epochMs(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 对象转 double（null 视为 0）
     *
     * @param value 数值对象
     * @return double 值
     */
    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    /**
     * 对象转 long（null 视为 0）
     *
     * @param value 数值对象
     * @return long 值
     */
    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
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
     * 对象序列化为 JSON 字符串（失败抛业务异常）
     *
     * @param value 任意可序列化对象
     * @return JSON 字符串
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("报告JSON序列化失败：" + e.getMessage());
        }
    }

    /**
     * JSON 字符串解析为树节点（空串返回 null）
     *
     * @param json JSON 字符串
     * @return 解析后的 JsonNode
     */
    private JsonNode readTree(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("报告JSON解析失败，忽略该分区", e);
            return null;
        }
    }

    /**
     * 报告五分区数据载体（内部传递用）
     */
    private static class Sections {

        /** 汇总指标 */
        Map<String, Object> summary;

        /** 采样器级指标 */
        List<Map<String, Object>> samplers;

        /** 节点级指标 */
        List<Map<String, Object>> nodes;

        /** 错误分析 */
        Map<String, Object> errors;

        /** 时间序列 */
        List<Map<String, Object>> series;
    }
}
