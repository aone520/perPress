package com.per.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.per.agent.client.MetricsReport;
import com.per.agent.client.AgentServerException;
import com.per.agent.client.MetricsReport.SamplerMetrics;
import com.per.agent.common.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JtlMetricsCollector 单测（M3 验收）：构造假 JTL（表头 + 成功 + 失败 + 坏行），
 * 同步驱动"解析 → 分窗 → 分桶 → 封窗"链路，断言窗口聚合结构并打印上报 JSON。
 * <p>不启动 JMeter 进程、不真实调用 server：上报回调仅收集到内存列表。</p>
 */
class JtlMetricsCollectorTest {

    /** JMeter CSV 表头（17 列，与采集器列索引约定一致） */
    private static final String HEADER = "timeStamp,elapsed,label,responseCode,responseMessage,threadName,"
            + "dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect";

    /** 窗口长度（毫秒），与实现保持一致 */
    private static final long WINDOW_MS = 3_000L;

    @TempDir
    Path tempDir;

    /**
     * 验证主链路：表头跳过 + 坏行跳过 + 同窗聚合（按 label）+ 引号还原 failureMessage 含逗号 +
     * 38 桶落桶 + 尾窗 finished=true 一次性上报。
     *
     * @throws Exception 文件写入失败
     */
    @Test
    void parseAggregateBucketAndFlush() throws Exception {
        Path jtl = tempDir.resolve("result.jtl");
        long now = System.currentTimeMillis();
        long windowStart = align(now);
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        // login：3 条（5ms/25ms 成功 + 30ms 失败，failureMessage 含逗号需引号包裹）
        lines.add(row(windowStart + 100, 5, "login", "200", true, "", 1000, 200, 10));
        lines.add(row(windowStart + 200, 25, "login", "200", true, "", 1200, 240, 10));
        lines.add(row(windowStart + 300, 30, "login", "500", false,
                "\"assert failed, actual != expected\"", 300, 60, 10));
        // query：2 条（60000ms 恰好末桶上界 + 60001ms 超上界也落末桶）
        lines.add(row(windowStart + 400, 60000, "query", "200", true, "", 500, 100, 20));
        lines.add(row(windowStart + 500, 60001, "query", "200", true, "", 600, 120, 20));
        // 坏行：列数不足
        lines.add("this,is,a,bad,line");
        Files.write(jtl, lines, StandardCharsets.UTF_8);

        List<MetricsReport> reports = Collections.synchronizedList(new ArrayList<>());
        JtlMetricsCollector collector = new JtlMetricsCollector(9L, "node-test", jtl, reports::add);
        collector.collectOnce();   // 同步驱动一轮：增量读取 + 聚合 + 超时封窗检查
        collector.stopAndFlush();  // 任务结束：补读 + 尾窗 finished=true

        assertEquals(5, collector.getSampleCount(), "5 行有效样本应全部解析");
        assertEquals(1, collector.getBadLineCount(), "坏行应跳过并计数");
        assertEquals(1, reports.size(), "单窗任务应恰好产出 1 条尾窗报告");
        MetricsReport report = reports.get(0);
        assertTrue(report.finished(), "尾窗报告 finished 应为 true");
        assertEquals(9L, report.taskId());
        assertEquals("node-test", report.nodeKey());
        assertEquals(windowStart, report.windowStart(), "窗口起点应按 3 秒对齐");
        assertEquals(windowStart + WINDOW_MS, report.windowEnd());
        assertEquals(2, report.samplers().size(), "两个 label 各一条聚合");

        SamplerMetrics login = find(report, "login");
        assertEquals(3, login.count());
        assertEquals(1, login.errorCount());
        assertEquals(1000 + 1200 + 300, login.bytes());
        assertEquals(200 + 240 + 60, login.sentBytes());
        assertEquals(10, login.activeThreads());
        assertEquals(5, login.minMs());
        assertEquals(30, login.maxMs());
        assertEquals(5 + 25 + 30, login.sumMs());
        String[] buckets = login.buckets().split(",");
        assertEquals(JtlMetricsCollector.BUCKET_BOUNDS.length, buckets.length, "桶数应为 38");
        assertEquals(3, sum(buckets), "桶内计数总和应等于样本数");
        assertEquals("1", buckets[4], "5ms 应落上界 5 的桶（下标 4）");
        assertEquals("1", buckets[13], "25ms 应落上界 25 的桶（下标 13）");
        assertEquals("1", buckets[14], "30ms 应落上界 30 的桶（下标 14）");

        SamplerMetrics query = find(report, "query");
        String[] queryBuckets = query.buckets().split(",");
        assertEquals("2", queryBuckets[37], "60000ms 与 60001ms 均应落最后一桶（下标 37）");

        assertEquals(1, report.errors().size(), "错误样本每窗最多 10 条，此处 1 条");
        assertEquals("login", report.errors().get(0).label());
        assertEquals("500", report.errors().get(0).code());
        assertEquals("assert failed, actual != expected", report.errors().get(0).msg(),
                "含逗号的 failureMessage 应被引号还原完整");
        assertEquals(windowStart + 300, report.errors().get(0).ts(), "错误样本应携带样本时间戳");

        System.out.println("[Test] 单窗聚合上报 JSON 示例:");
        System.out.println(Jsons.write(report));
    }

    /**
     * 验证窗口切换：跨两个历史窗的样本触发"读下一窗样本即封上一窗"，超时封窗兜底封掉第二窗，
     * stopAndFlush 无未封样本时仍发空 samplers 的 finished 报告（结束信号必达）。
     *
     * @throws Exception 文件写入失败
     */
    @Test
    void windowSwitchAndTailFlush() throws Exception {
        Path jtl = tempDir.resolve("result.jtl");
        long nowWindow = align(System.currentTimeMillis());
        long windowA = nowWindow - 2 * WINDOW_MS;
        long windowB = nowWindow - WINDOW_MS;
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.add(row(windowA + 100, 10, "login", "200", true, "", 100, 20, 5));
        lines.add(row(windowA + 200, 20, "login", "200", true, "", 100, 20, 5));
        lines.add(row(windowB + 100, 30, "query", "200", true, "", 50, 10, 8));
        Files.write(jtl, lines, StandardCharsets.UTF_8);

        List<MetricsReport> reports = Collections.synchronizedList(new ArrayList<>());
        JtlMetricsCollector collector = new JtlMetricsCollector(10L, "node-test", jtl, reports::add);
        collector.collectOnce();   // 读到 B 窗样本即封 A 窗；B 窗受迟到缓冲保护不超时封（新语义）
        collector.stopAndFlush();  // B 窗作为尾窗以 finished=true 封掉（自带结束信号）

        assertEquals(2, reports.size(), "应产出 A 窗(finished=false) + B 窗尾报(finished=true)");
        MetricsReport reportA = reports.get(0);
        assertEquals(windowA, reportA.windowStart());
        assertEquals(1, reportA.samplers().size());
        assertEquals(2, reportA.samplers().get(0).count());
        assertTrue(!reportA.finished());
        MetricsReport reportB = reports.get(1);
        assertEquals(windowB, reportB.windowStart());
        assertEquals(1, reportB.samplers().size());
        assertEquals("query", reportB.samplers().get(0).label());
        assertTrue(reportB.finished(), "尾窗报告 finished 必为 true（结束信号随尾窗直达）");
        assertEquals(WINDOW_MS, reportB.windowEnd() - reportB.windowStart());

        System.out.println("[Test] 窗口切换上报 JSON 示例:");
        reports.forEach(r -> System.out.println(Jsons.write(r)));
    }

    @Test
    void compactHeaderAndNetworkRetryAreSupported() throws Exception {
        Path jtl = tempDir.resolve("compact-result.jtl");
        long windowStart = align(System.currentTimeMillis());
        String compactHeader = "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,"
                + "bytes,sentBytes,grpThreads,allThreads";
        String compactRow = windowStart + ",12,login,200,OK,true,,128,32,4,8";
        Files.write(jtl, List.of(compactHeader, compactRow), StandardCharsets.UTF_8);

        AtomicInteger attempts = new AtomicInteger();
        List<MetricsReport> reports = Collections.synchronizedList(new ArrayList<>());
        JtlMetricsCollector collector = new JtlMetricsCollector(11L, "node-test", jtl, report -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AgentServerException(-1, "temporary network failure");
            }
            reports.add(report);
        });
        collector.collectOnce();
        collector.stopAndFlush();

        assertEquals(3, attempts.get(), "网络失败后应重试直至成功");
        assertEquals(1, reports.size());
        assertEquals(8, reports.get(0).samplers().get(0).activeThreads());
    }

    /**
     * 按标签查找窗口聚合项。
     *
     * @param report 窗口报告
     * @param label  采样器名
     * @return 对应聚合项
     */
    private static SamplerMetrics find(MetricsReport report, String label) {
        return report.samplers().stream()
                .filter(s -> label.equals(s.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 label 聚合: " + label));
    }

    /**
     * 桶计数数组求和。
     *
     * @param buckets 桶计数字符串数组
     * @return 总和
     */
    private static long sum(String[] buckets) {
        long total = 0;
        for (String bucket : buckets) {
            total += Long.parseLong(bucket);
        }
        return total;
    }

    /**
     * 时间戳对齐到 3 秒窗口起点（与实现一致）。
     *
     * @param ts 时间戳（毫秒）
     * @return 窗口起点
     */
    private static long align(long ts) {
        return ts - ts % WINDOW_MS;
    }

    /**
     * 构造一行 17 列 JTL CSV 记录。
     *
     * @param ts             时间戳（毫秒）
     * @param elapsed        耗时（毫秒）
     * @param label          采样器名
     * @param code           响应码
     * @param success        是否成功
     * @param failureMessage 失败信息（含逗号时需自带双引号包裹）
     * @param bytes          接收字节
     * @param sentBytes      发送字节
     * @param allThreads     总活跃线程
     * @return 单行 CSV
     */
    private static String row(long ts, long elapsed, String label, String code, boolean success,
                              String failureMessage, long bytes, long sentBytes, int allThreads) {
        return ts + "," + elapsed + "," + label + "," + code + ",OK,Thread Group 1-1,text,"
                + success + "," + failureMessage + "," + bytes + "," + sentBytes + ",1," + allThreads
                + ",http://localhost:8080/api,0,0,0";
    }
}
