package com.per.agent.metrics;

import com.per.agent.client.AgentServerException;
import com.per.agent.client.MetricsReport;
import com.per.agent.client.MetricsReport.ErrorSample;
import com.per.agent.client.MetricsReport.SamplerMetrics;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * JTL 结果文件增量采集器（M3 指标链路核心）：JTL 增量采集 → 10s 窗口聚合 → 直方图分桶 → 上报。
 * <ul>
 *   <li>增量读取：RandomAccessFile 记住文件偏移，采集线程每 1s 轮询一次；
 *       首次读取跳过表头行；文件末尾未写完整的残行留待下一轮；文件长度回退（同路径重跑）时重置偏移重新读。</li>
 *   <li>行解析：CSV 按逗号分割（对双引号包裹字段做简单还原，兼容 failureMessage/label 含逗号），
 *       仅按列索引取 timeStamp/elapsed/label/responseCode/success/failureMessage/bytes/sentBytes/allThreads；
 *       success 列非法（非 true/false）或时间戳非法的行跳过并计入坏行计数。</li>
 *   <li>窗口聚合：windowStart = ts - ts % windowMs（按配置窗口长度对齐）；读到属于下一窗的样本时封上一窗；
 *       轮询时发现当前时间已越过当前窗末尾也封窗（低流量任务不掉尾窗）。</li>
 *   <li>封窗上报：窗口内按 label 聚合 {count, errorCount, bytes, sentBytes, activeThreads(max),
 *       minMs/maxMs/sumMs, buckets}，errors 每窗最多 10 条（msg 截断 200），投递到独立上报单线程，
 *       POST /agent/metrics；code!=0 仅 WARN 不重试，网络失败静默（下一窗数据独立）。</li>
 *   <li>任务结束：stopAndFlush() 中断采集线程 → 补读剩余增量 → 以 finished=true 封最后未封窗
 *       （无未封样本时也发空 samplers 的 finished 报告，保证指标流结束信号必达）。</li>
 * </ul>
 * 非线程安全约定：collectOnce/stopAndFlush 之外的实例方法仅由采集线程访问。
 */
@Slf4j
public class JtlMetricsCollector {

    /** 响应耗时直方图桶上界（毫秒，38 桶，与服务端约定一致）：elapsed&lt;=上界落入该桶，&gt;60000 落最后一桶 */
    public static final long[] BUCKET_BOUNDS = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100,
            150, 200, 300, 400, 600, 800, 1000, 1500, 2000, 3000, 4000, 6000, 8000, 10000,
            15000, 20000, 30000, 60000};

    /** 聚合窗口长度（毫秒，窗口起点按该长度整秒对齐）；实例字段以支持配置化（默认见 AgentProperties.metricsWindowMs） */
    private final long windowMs;

    /** 采集线程轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 1_000L;

    /** 单轮增量读取的最大字节数（超出分多轮读完，防止瞬时大文件占内存） */
    private static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;

    /** 每窗最多上报的错误样本条数 */
    private static final int MAX_ERRORS_PER_WINDOW = 10;

    /** 错误样本 failureMessage 截断长度 */
    private static final int ERROR_MESSAGE_MAX = 200;

    /** JTL 列索引（0 基，对应表头 17 列）：timeStamp */
    private static final int IDX_TIMESTAMP = 0;

    /** JTL 列索引：elapsed（毫秒） */
    private static final int IDX_ELAPSED = 1;

    /** JTL 列索引：label（采样器名） */
    private static final int IDX_LABEL = 2;

    /** JTL 列索引：responseCode */
    private static final int IDX_RESPONSE_CODE = 3;

    /** JTL 列索引：success（true/false） */
    private static final int IDX_SUCCESS = 7;

    /** JTL 列索引：failureMessage */
    private static final int IDX_FAILURE_MESSAGE = 8;

    /** JTL 列索引：bytes（接收字节） */
    private static final int IDX_BYTES = 9;

    /** JTL 列索引：sentBytes（发送字节） */
    private static final int IDX_SENT_BYTES = 10;

    /** JTL 列索引：allThreads（总活跃线程，窗口内取最大值） */
    private static final int IDX_ALL_THREADS = 12;

    private final long taskId;

    private final String nodeKey;

    private final Path jtlFile;

    /** 上报回调（由调用方注入，通常为 serverClient::sendMetrics；异常由本类上报线程统一捕获） */
    private final Consumer<MetricsReport> reporter;

    /** 采集线程（1s 轮询增量读取 + 超时封窗） */
    private final Thread collectThread;

    /** 上报单线程 executor（与采集线程分离，封窗即投递） */
    private final ExecutorService reportExecutor;

    /** 停止标记（stopAndFlush 幂等） */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 已解析样本总数（stop 日志统计） */
    private final AtomicLong sampleCount = new AtomicLong();

    /** 跳过的坏行总数（success/时间戳列非法） */
    private final AtomicLong badLineCount = new AtomicLong();

    /** 当前文件读取偏移（字节） */
    private long fileOffset = 0;

    /** 表头是否已跳过（文件重建后重置） */
    private boolean headerSkipped = false;

    /** 当前未封窗起点（-1 表示尚无样本） */
    private long currentWindowStart = -1;

    /** 当前窗各 label 聚合表 */
    private final Map<String, SamplerAgg> windowSamplers = new LinkedHashMap<>();

    /** 当前窗错误样本明细（最多 MAX_ERRORS_PER_WINDOW 条） */
    private final List<ErrorSample> windowErrors = new ArrayList<>();

    /** 最近已封窗起点（迟到样本保护：晚于此窗起点的样本丢弃计数，防止重开窗覆盖已上报数据） */
    private long lastClosedWindow = -1;

    /** 因迟到被丢弃的样本数（封窗保护触发时累计，排障用） */
    private final AtomicLong droppedLateCount = new AtomicLong();

    /**
     * 构造 JTL 采集器（构造后需调用 start() 启动采集线程）。
     *
     * @param taskId   任务 ID
     * @param nodeKey  节点密钥（上报体字段）
     * @param jtlFile  JMeter -l 产生的 result.jtl 路径（可暂不存在，出现后自动开始读取）
     * @param reporter 上报回调（接收窗口报告；抛出的异常由本类吞掉并记日志）
     */
    public JtlMetricsCollector(long taskId, String nodeKey, Path jtlFile, Consumer<MetricsReport> reporter) {
        this(taskId, nodeKey, jtlFile, reporter, 10_000L);
    }

    /**
     * 构造 JTL 采集器（指定聚合窗口长度，构造后需调用 start() 启动采集线程）。
     *
     * @param taskId   任务 ID
     * @param nodeKey  节点密钥（上报体字段）
     * @param jtlFile  JMeter -l 产生的 result.jtl 路径（可暂不存在，出现后自动开始读取）
     * @param reporter 上报回调（接收窗口报告；抛出的异常由本类吞掉并记日志）
     * @param windowMs 聚合窗口长度（毫秒，最小 1000；窗口越小监控越实时，上报频率相应提高）
     */
    public JtlMetricsCollector(long taskId, String nodeKey, Path jtlFile,
                               Consumer<MetricsReport> reporter, long windowMs) {
        this.taskId = taskId;
        this.nodeKey = nodeKey;
        this.jtlFile = jtlFile;
        this.reporter = reporter;
        this.windowMs = Math.max(1_000L, windowMs);
        this.collectThread = new Thread(this::collectLoop, "jtl-collector-" + taskId);
        this.collectThread.setDaemon(true);
        this.reportExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "metrics-reporter-" + taskId);
            thread.setDaemon(true);
            return thread;
        });
        log.info("[Metrics] 任务 {} 聚合窗口 {}ms", taskId, this.windowMs);
    }

    /**
     * 启动采集线程（重复调用无副作用）。
     */
    public void start() {
        if (collectThread.isAlive() || stopped.get()) {
            return;
        }
        collectThread.start();
        log.info("[Metrics] 任务 {} 指标采集已启动: jtl={}", taskId, jtlFile);
    }

    /**
     * 执行一轮"增量读取 → 聚合 → 超时封窗"（采集线程轮询体，亦供单测同步驱动）。
     * 方法体异常全捕获，保证轮询永不中断。
     */
    public void collectOnce() {
        try {
            readIncremental();
        } catch (Throwable t) {
            log.warn("[Metrics] 任务 {} JTL 增量读取异常: {}", taskId, t.getMessage());
        }
        try {
            flushExpiredWindow();
        } catch (Throwable t) {
            log.warn("[Metrics] 任务 {} 超时封窗异常: {}", taskId, t.getMessage());
        }
    }

    /**
     * 停止采集并冲洗尾窗（任务结束时调用，幂等）：
     * 中断采集线程 → 补读剩余增量 → 以 finished=true 封最后未封窗（无样本也发空报告保证结束信号）→
     * 关闭上报线程并等待已投递报告发完；异常全捕获不外抛。
     */
    public void stopAndFlush() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        collectThread.interrupt();
        try {
            collectThread.join(3_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            readIncremental();
        } catch (Throwable t) {
            log.warn("[Metrics] 任务 {} 尾部补读异常: {}", taskId, t.getMessage());
        }
        try {
            closeWindow(true);
        } catch (Throwable t) {
            log.warn("[Metrics] 任务 {} 尾窗封窗上报异常: {}", taskId, t.getMessage());
        }
        reportExecutor.shutdown();
        try {
            if (!reportExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[Metrics] 任务 {} 上报线程 5s 内未排空，丢弃未发送窗口报告", taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[Metrics] 任务 {} 采集结束：样本 {} 条，坏行 {} 条，迟到丢弃 {} 条",
                taskId, sampleCount.get(), badLineCount.get(), droppedLateCount.get());
    }

    /**
     * 获取已跳过的坏行总数（success/时间戳列非法的行，测试与排障用）。
     *
     * @return 坏行数
     */
    public long getBadLineCount() {
        return badLineCount.get();
    }

    /**
     * 获取已解析的样本总数（测试与排障用）。
     *
     * @return 样本数
     */
    public long getSampleCount() {
        return sampleCount.get();
    }

    /**
     * 采集线程主体：未停止前每 1s 执行一轮 collectOnce，中断即退出（尾部数据由 stopAndFlush 补读）。
     */
    private void collectLoop() {
        while (!stopped.get()) {
            collectOnce();
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 增量读取 JTL 新增字节：按 '\n' 切行逐行解析，末尾未换行的残行不推进偏移（等写入完整）；
     * 文件长度小于已记录偏移视为重建（重跑），重置偏移与表头标记重新读取。
     *
     * @throws IOException 文件读取失败
     */
    private void readIncremental() throws IOException {
        if (!Files.isRegularFile(jtlFile)) {
            return; // JMeter 尚未创建结果文件，等待下一轮
        }
        try (RandomAccessFile raf = new RandomAccessFile(jtlFile.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength < fileOffset) {
                log.warn("[Metrics] 任务 {} 结果文件长度回退（{} < {}），视为重建，重置偏移重新读取",
                        taskId, fileLength, fileOffset);
                fileOffset = 0;
                headerSkipped = false;
            }
            if (fileLength <= fileOffset) {
                return;
            }
            int chunk = (int) Math.min(fileLength - fileOffset, MAX_CHUNK_BYTES);
            byte[] buffer = new byte[chunk];
            raf.seek(fileOffset);
            raf.readFully(buffer);
            int lineStart = 0;
            for (int i = 0; i < buffer.length; i++) {
                if (buffer[i] == '\n') {
                    handleLine(new String(buffer, lineStart, i - lineStart, StandardCharsets.UTF_8));
                    lineStart = i + 1;
                }
            }
            fileOffset += lineStart;
        }
    }

    /**
     * 解析单行 JTL 记录并聚合进当前窗口：跳过表头；success/时间戳非法计坏行跳过；
     * bytes/sentBytes/allThreads 解析失败按 0 容错（不丢整行统计）。
     *
     * @param rawLine 原始行（可能含 '\r' 结尾）
     */
    private void handleLine(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty()) {
            return;
        }
        if (!headerSkipped && line.startsWith("timeStamp")) {
            headerSkipped = true;
            return;
        }
        String[] parts = splitCsv(line);
        if (parts.length <= IDX_SUCCESS) {
            skipBadLine(line, "列数不足（" + parts.length + " 列）");
            return;
        }
        long timestamp;
        long elapsed;
        try {
            timestamp = Long.parseLong(parts[IDX_TIMESTAMP].trim());
            elapsed = Long.parseLong(parts[IDX_ELAPSED].trim());
        } catch (NumberFormatException e) {
            skipBadLine(line, "timeStamp/elapsed 非数字");
            return;
        }
        String successText = parts[IDX_SUCCESS].trim();
        if (!"true".equalsIgnoreCase(successText) && !"false".equalsIgnoreCase(successText)) {
            skipBadLine(line, "success 列非法: " + successText);
            return;
        }
        boolean success = "true".equalsIgnoreCase(successText);
        String label = parts[IDX_LABEL].isBlank() ? "(unknown)" : parts[IDX_LABEL];
        String responseCode = parts[IDX_RESPONSE_CODE];
        long bytes = tryParseLong(parts, IDX_BYTES);
        long sentBytes = tryParseLong(parts, IDX_SENT_BYTES);
        int allThreads = (int) tryParseLong(parts, IDX_ALL_THREADS);
        sampleCount.incrementAndGet();
        String failureMessage = parts.length > IDX_FAILURE_MESSAGE ? parts[IDX_FAILURE_MESSAGE] : "";
        if (!success && windowErrors.size() < MAX_ERRORS_PER_WINDOW) {
            windowErrors.add(new ErrorSample(label, responseCode, truncate(failureMessage, ERROR_MESSAGE_MAX)));
        }
        aggregate(timestamp, elapsed, label, success, bytes, sentBytes, allThreads);
    }

    /**
     * 将样本聚合进窗口（必要时封上一窗）：windowStart = ts - ts % 10000；
     * 读到更晚窗口的样本即封当前窗；早于当前窗的乱序样本归入当前窗（避免重复封窗）。
     *
     * @param timestamp  样本时间戳（毫秒）
     * @param elapsed    样本耗时（毫秒）
     * @param label      采样器名
     * @param success    是否成功
     * @param bytes      接收字节
     * @param sentBytes  发送字节
     * @param allThreads 总活跃线程
     */
    private void aggregate(long timestamp, long elapsed, String label, boolean success,
                           long bytes, long sentBytes, int allThreads) {
        long windowStart = alignWindow(timestamp);
        if (currentWindowStart < 0) {
            // 迟到样本保护：属于已封窗的样本直接丢弃计数，绝不能重开同窗（服务端 UPSERT 会覆盖完整数据）
            if (lastClosedWindow >= 0 && windowStart <= lastClosedWindow) {
                droppedLateCount.incrementAndGet();
                return;
            }
            currentWindowStart = windowStart;
        } else if (windowStart > currentWindowStart) {
            closeWindow(false);
            currentWindowStart = windowStart;
        }
        windowSamplers.computeIfAbsent(label, SamplerAgg::new).add(elapsed, success, bytes, sentBytes, allThreads);
    }

    /**
     * 超时封窗检查：JMeter 批量落盘存在延迟（样本时间戳可能落后文件写入 10s+），
     * 直接按"当前时间越过窗末尾"封窗会导致迟到样本重开同窗并覆盖已上报的完整窗口数据。
     * 因此给一个完整窗口的迟到缓冲：窗末后再等 windowMs 才允许超时封窗。
     */
    private void flushExpiredWindow() {
        if (currentWindowStart >= 0 && System.currentTimeMillis() >= currentWindowStart + 2 * windowMs) {
            closeWindow(false);
        }
    }

    /**
     * 封闭当前窗口并投递上报：finished=false 且窗口无样本时跳过（不产生空报）；
     * finished=true 时即使无样本也发空报告（保证指标流结束信号必达）；封窗后清零继续。
     *
     * @param finished 是否任务结束的最后一报
     */
    private void closeWindow(boolean finished) {
        if (!finished && windowSamplers.isEmpty()) {
            return;
        }
        long windowStart = currentWindowStart >= 0 ? currentWindowStart : alignWindow(System.currentTimeMillis());
        if (windowStart > lastClosedWindow) {
            lastClosedWindow = windowStart;
        }
        List<SamplerMetrics> samplers = new ArrayList<>(windowSamplers.size());
        for (SamplerAgg agg : windowSamplers.values()) {
            samplers.add(agg.toMetrics());
        }
        MetricsReport report = new MetricsReport(taskId, nodeKey, windowStart, windowStart + windowMs,
                finished, samplers, new ArrayList<>(windowErrors));
        windowSamplers.clear();
        windowErrors.clear();
        currentWindowStart = -1;
        dispatchReport(report);
    }

    /**
     * 投递窗口报告到上报单线程：服务端业务错误（code&gt;0）WARN 提示不重试；
     * 网络失败等其它异常静默（下一窗数据独立，容忍抖动）；线程池已关闭时丢弃并告警。
     *
     * @param report 窗口报告
     */
    private void dispatchReport(MetricsReport report) {
        try {
            reportExecutor.execute(() -> {
                try {
                    reporter.accept(report);
                } catch (AgentServerException e) {
                    if (e.getCode() > 0) {
                        log.warn("[Metrics] 任务 {} 窗口 {} 指标上报被服务端拒绝: code={}, message={}（不重试）",
                                taskId, report.windowStart(), e.getCode(), e.getMessage());
                    } else {
                        log.debug("[Metrics] 任务 {} 窗口 {} 指标上报网络失败（静默容忍）: {}",
                                taskId, report.windowStart(), e.getMessage());
                    }
                } catch (Throwable t) {
                    log.debug("[Metrics] 任务 {} 窗口 {} 指标上报失败（静默容忍）: {}",
                            taskId, report.windowStart(), t.getMessage());
                }
            });
        } catch (Throwable t) {
            log.warn("[Metrics] 任务 {} 上报线程池已关闭，丢弃窗口 {} 报告", taskId, report.windowStart());
        }
    }

    /**
     * 计数并告警一条坏行（success 列解析异常等，JMeter 输出异常时定位用）。
     *
     * @param line   原始行
     * @param reason 跳过原因
     */
    private void skipBadLine(String line, String reason) {
        badLineCount.incrementAndGet();
        log.warn("[Metrics] 任务 {} 跳过坏行（第 {} 条）: 原因={}, 内容={}",
                taskId, badLineCount.get(), reason, truncate(line, 200));
    }

    /**
     * 时间戳对齐到窗口长度整倍数的窗口起点（如 3s 窗口对齐到 3 的整倍数秒）。
     *
     * @param ts 时间戳（毫秒）
     * @return 窗口起点
     */
    private long alignWindow(long ts) {
        return ts - ts % windowMs;
    }

    /**
     * 容错解析指定列的 long 值（列越界/非数字返回 0，不丢整行其余统计）。
     *
     * @param parts     已切分字段
     * @param index     列索引
     * @return 解析值，失败返回 0
     */
    private static long tryParseLong(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Long.parseLong(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 计算响应耗时所属直方图桶下标：第一个满足 elapsed&lt;=上界的桶，超过 60000 落最后一桶。
     *
     * @param elapsedMs 耗时（毫秒）
     * @return 桶下标（0..37）
     */
    static int bucketIndex(long elapsedMs) {
        for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
            if (elapsedMs <= BUCKET_BOUNDS[i]) {
                return i;
            }
        }
        return BUCKET_BOUNDS.length - 1;
    }

    /**
     * 截断字符串到指定长度（错误消息防刷屏）。
     *
     * @param text      原始字符串（null 视为空串）
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * CSV 行切分（兼容双引号包裹字段：JMeter 对含逗号字段加引号包裹、内部引号翻倍），
     * 避免含逗号的 failureMessage/label 导致后续列整体错位。
     *
     * @param line 单行 CSV
     * @return 字段数组（引号已剥除、转义引号已还原）
     */
    private static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    /**
     * 单 label 窗口内聚合器：计数/错误数/字节/线程峰值/耗时统计与 38 桶直方图。
     */
    private static final class SamplerAgg {

        /** 采样器名称 */
        private final String label;

        /** 样本总数 */
        private long count;

        /** 错误样本数 */
        private long errorCount;

        /** 接收字节总量 */
        private long bytes;

        /** 发送字节总量 */
        private long sentBytes;

        /** 活跃线程峰值（allThreads 最大值） */
        private int activeThreads;

        /** 最小耗时（毫秒） */
        private long minMs = Long.MAX_VALUE;

        /** 最大耗时（毫秒） */
        private long maxMs;

        /** 耗时总和（毫秒） */
        private long sumMs;

        /** 38 桶直方图计数 */
        private final int[] buckets = new int[BUCKET_BOUNDS.length];

        /**
         * 构造聚合器。
         *
         * @param label 采样器名称
         */
        private SamplerAgg(String label) {
            this.label = label;
        }

        /**
         * 累加一个样本。
         *
         * @param elapsed    耗时（毫秒）
         * @param success    是否成功
         * @param bytes      接收字节
         * @param sentBytes  发送字节
         * @param allThreads 总活跃线程
         */
        private void add(long elapsed, boolean success, long bytes, long sentBytes, int allThreads) {
            count++;
            if (!success) {
                errorCount++;
            }
            this.bytes += bytes;
            this.sentBytes += sentBytes;
            this.activeThreads = Math.max(this.activeThreads, allThreads);
            minMs = Math.min(minMs, elapsed);
            maxMs = Math.max(maxMs, elapsed);
            sumMs += elapsed;
            buckets[bucketIndex(elapsed)]++;
        }

        /**
         * 输出为上报 DTO（buckets 序列化为逗号分隔字符串）。
         *
         * @return 窗口聚合指标
         */
        private SamplerMetrics toMetrics() {
            StringBuilder bucketText = new StringBuilder(buckets.length * 3);
            for (int i = 0; i < buckets.length; i++) {
                if (i > 0) {
                    bucketText.append(',');
                }
                bucketText.append(buckets[i]);
            }
            return new SamplerMetrics(label, count, errorCount, bytes, sentBytes,
                    activeThreads, count > 0 ? minMs : 0, maxMs, sumMs, bucketText.toString());
        }
    }
}
