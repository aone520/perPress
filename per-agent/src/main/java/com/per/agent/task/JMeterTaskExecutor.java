package com.per.agent.task;

import com.per.agent.client.MetricsReport;
import com.per.agent.client.ServerClient;
import com.per.agent.client.TaskReceipt;
import com.per.agent.common.HttpDownloader;
import com.per.agent.common.Jsons;
import com.per.agent.config.AgentProperties;
import com.per.agent.core.AgentLifecycle;
import com.per.agent.engine.EngineManager;
import com.per.agent.metrics.JtlMetricsCollector;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JMeter 压测任务执行器（M2 完整实现，遵循平台任务协议）：
 * <ul>
 *   <li>PREPARE：下载脚本（{baseUrl}/agent/task/{taskId}/script.jmx，MD5 比对 scriptMd5）与
 *       附件（SPLIT 直下分片地址、SHARED 下载后校验 md5，落地文件名用 file.name 保持 JMX 相对引用），
 *       全部成功回执 READY，任一失败回执 FAILED；state.json 记录 PREPARED 标记实现幂等。</li>
 *   <li>START：校验已 PREPARED 且引擎就绪 → 启动前清理同任务残留进程 →
 *       {jmeterBin} -n -t script.jmx -l result.jtl -j jmeter.log -J{k}={v}... →
 *       回执 RUNNING → 单线程 watcher 等待进程退出 → 退出码 0 回执 FINISHED，
 *       否则 FAILED+尾 200 字符日志；startedAt/endedAt 落盘 state.json。</li>
 *   <li>STOP：destroy → 3 秒未退 destroyForcibly → 回执 FINISHED（message=stopped by server）。</li>
 *   <li>stopAllRunning：心跳失联自停，强停全部运行中进程（不发回执）。</li>
 *   <li>M3 指标接线：START 启动进程成功即为该任务启动 JtlMetricsCollector（增量采集 result.jtl，
 *       10s 窗口聚合分桶后经 /agent/metrics 每窗一报）；进程退出（awaitExit）/STOP/失联自停/
 *       残留清理均触发 collector.stopAndFlush()（尾窗 finished=true）；
 *       重复 START 由 collector 表先停旧再建新防重复；result.jtl 运行后保留用于排障。</li>
 * </ul>
 * 工作目录：data-dir/tasks/{taskId}/（script.jmx、附件、result.jtl、jmeter.log、jmeter.out、state.json）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JMeterTaskExecutor implements TaskExecutor {

    /** 压测脚本文件名（服务端固定提供 script.jmx 下载地址） */
    private static final String SCRIPT_FILE = "script.jmx";

    /** JMeter 运行结果文件名 */
    private static final String RESULT_FILE = "result.jtl";

    /** JMeter 自身日志文件名 */
    private static final String JMETER_LOG_FILE = "jmeter.log";

    /** JMeter 进程标准输出/错误重定向文件名 */
    private static final String STDOUT_FILE = "jmeter.out";

    /** 任务状态持久化文件名 */
    private static final String STATE_FILE = "state.json";

    /** STOP 优雅退出等待时长（秒），超时强杀 */
    private static final long STOP_GRACE_SECONDS = 3;

    /** 回执 message 最大长度（超长截断，避免异常堆栈刷爆协议包） */
    private static final int RECEIPT_MESSAGE_MAX = 500;

    /** 运行中任务表：taskId → 进程句柄与停止标记 */
    private final ConcurrentHashMap<Long, RunningTask> runningTasks = new ConcurrentHashMap<>();

    /** 运行中任务的指标采集器表：taskId → JTL 采集器（进程退出/停止时 stopAndFlush，重复 START 先停旧） */
    private final ConcurrentHashMap<Long, JtlMetricsCollector> collectors = new ConcurrentHashMap<>();

    private final AgentProperties properties;
    private final ServerClient serverClient;
    private final AgentLifecycle lifecycle;
    private final EngineManager engineManager;
    private final HttpDownloader downloader;

    /** 进程退出 watcher 专用单线程（守护线程，阻塞于 waitFor 不影响调度） */
    private final ExecutorService processWatcher = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "jmeter-process-watcher");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 处理 PREPARE 指令：下载脚本与附件并校验，全部成功写 PREPARED 状态并回执 READY；
     * 已 PREPARED 时幂等跳过（仅补发 READY）；任何失败回执 FAILED（异常全捕获不外抛）。
     *
     * @param task 任务详情
     */
    @Override
    public void handlePrepare(TaskSpec task) {
        long taskId = task.taskId();
        try {
            TaskState state = loadState(taskId);
            if (state != null && TaskState.STATUS_PREPARED.equals(state.status())) {
                log.info("[Task] 任务 {} 已 PREPARED，重复 PREPARE 幂等跳过", taskId);
                sendReceipt(taskId, TaskReceipt.PHASE_READY, "重复 PREPARE：任务已就绪");
                return;
            }
            Path taskDir = taskDir(taskId);
            Files.createDirectories(taskDir);
            downloadScript(task, taskDir);
            if (task.files() != null) {
                for (TaskFile file : task.files()) {
                    downloadFile(task, file, taskDir);
                }
            }
            saveState(new TaskState(taskId, TaskState.STATUS_PREPARED, null, null,
                    task.jmeterProps(), task.jmeterHeapMb()));
            log.info("[Task] 任务 {} 资源准备完成（脚本 + {} 个附件），回执 READY",
                    taskId, task.files() == null ? 0 : task.files().size());
            sendReceipt(taskId, TaskReceipt.PHASE_READY, "资源准备完成");
        } catch (Throwable t) {
            String detail = describeFailure(t);
            log.error("[Task] 任务 {} 准备失败: {}", taskId, detail, t);
            sendReceipt(taskId, TaskReceipt.PHASE_FAILED, "准备失败: " + detail);
        }
    }

    /**
     * 提炼准备阶段失败原因：异常类型 + 原始消息；消息为空时仅保留类型，
     * 避免平台只展示「准备失败: null」无法定位（例如 ConnectException 常无 message，
     * 下载类异常的 URL 信息已由 HttpDownloader 包装进 message）。
     *
     * @param t 准备阶段捕获的异常
     * @return 可直接展示的失败原因文本
     */
    private static String describeFailure(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "（无异常消息）" : ": " + msg);
    }

    /**
     * 处理 START 指令：校验已 PREPARED 且引擎就绪，杀同任务残留进程后启动 JMeter 子进程，
     * 启动成功回执 RUNNING 并提交 watcher 等待退出（退出码 0 回执 FINISHED，否则 FAILED+日志尾部）；
     * 校验或启动失败回执 FAILED（异常全捕获不外抛）。
     *
     * @param taskId 任务 ID
     */
    @Override
    public void handleStart(long taskId) {
        try {
            killResidualProcess(taskId);
            TaskState state = loadState(taskId);
            if (state == null || !TaskState.STATUS_PREPARED.equals(state.status())) {
                log.warn("[Task] 任务 {} 未处于 PREPARED 状态，拒绝启动", taskId);
                sendReceipt(taskId, TaskReceipt.PHASE_FAILED, "任务未准备（缺少 PREPARED 状态）");
                return;
            }
            Path jmeterBin = engineManager.getJmeterBin();
            if (jmeterBin == null) {
                log.error("[Task] 任务 {} 启动失败：JMeter 引擎未就绪", taskId);
                sendReceipt(taskId, TaskReceipt.PHASE_FAILED, "JMeter 引擎未就绪");
                return;
            }
            Path taskDir = taskDir(taskId);
            if (!Files.isRegularFile(taskDir.resolve(SCRIPT_FILE))) {
                sendReceipt(taskId, TaskReceipt.PHASE_FAILED, "压测脚本缺失: " + SCRIPT_FILE);
                return;
            }
            // 参数文件兜底校验：JMX 引用的 CSV/TXT 文件必须已存在于任务目录，
            // 否则 JMeter 线程会空转到调度结束（零请求、任务"正常结束"但报告为空）
            String missingFile = findMissingDataFile(taskDir.resolve(SCRIPT_FILE), taskDir);
            if (missingFile != null) {
                log.error("[Task] 任务 {} 启动失败：缺少参数文件 {}", taskId, missingFile);
                sendReceipt(taskId, TaskReceipt.PHASE_FAILED, "缺少参数文件: " + missingFile + "（请检查任务文件分发配置）");
                return;
            }
            cleanPreviousRunOutputs(taskDir);
            List<String> command = buildCommand(jmeterBin, state.jmeterProps());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(taskDir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(taskDir.resolve(STDOUT_FILE).toFile()));
            // JMeter 堆内存：任务级配置优先，缺省用 Agent 本地默认（jmeter 启动脚本读取 HEAP 环境变量）
            int heapMb = state.jmeterHeapMb() != null ? state.jmeterHeapMb() : properties.getJmeterHeapMb();
            builder.environment().put("HEAP", "-Xmx" + heapMb + "m");
            Process process = builder.start();
            RunningTask running = new RunningTask(process);
            runningTasks.put(taskId, running);
            startCollector(taskId, taskDir.resolve(RESULT_FILE));
            saveState(new TaskState(taskId, TaskState.STATUS_RUNNING, System.currentTimeMillis(), null,
                    state.jmeterProps(), state.jmeterHeapMb()));
            log.info("[Task] 任务 {} JMeter 进程已启动 pid={}，heap={}m，命令: {}",
                    taskId, process.pid(), heapMb, String.join(" ", command));
            sendReceipt(taskId, TaskReceipt.PHASE_RUNNING, "pid=" + process.pid());
            processWatcher.execute(() -> awaitExit(taskId, running));
        } catch (Throwable t) {
            log.error("[Task] 任务 {} 启动失败: {}", taskId, t.getMessage(), t);
            sendReceipt(taskId, TaskReceipt.PHASE_FAILED, "启动失败: " + t.getMessage());
        }
    }

    /**
     * 处理 STOP 指令：destroy 对应进程（3 秒未退 destroyForcibly），回执 FINISHED（message=stopped by server）；
     * 任务本就未运行时同样回执 FINISHED（幂等）。异常全捕获不外抛。
     *
     * @param taskId 任务 ID
     */
    @Override
    public void handleStop(long taskId) {
        try {
            RunningTask running = runningTasks.remove(taskId);
            if (running == null) {
                log.warn("[Task] 收到 STOP 指令但任务 {} 当前未运行，直接回执 FINISHED", taskId);
                sendReceipt(taskId, TaskReceipt.PHASE_FINISHED, "stopped by server");
                return;
            }
            log.info("[Task] 收到 STOP 指令，停止任务 {}，pid={}", taskId, running.process.pid());
            terminate(taskId, running);
            stopCollectorQuietly(taskId);
            updateStateQuietly(taskId, TaskState.STATUS_FINISHED);
            sendReceipt(taskId, TaskReceipt.PHASE_FINISHED, "stopped by server");
        } catch (Throwable t) {
            log.error("[Task] 任务 {} 停止失败: {}", taskId, t.getMessage(), t);
            sendReceipt(taskId, TaskReceipt.PHASE_FAILED, "停止失败: " + t.getMessage());
        }
    }

    /**
     * 查询任务是否运行中（M3 指标采集使用，本里程碑仅预留接口）。
     *
     * @param taskId 任务 ID
     * @return true 表示对应 JMeter 进程仍在运行
     */
    @Override
    public boolean isRunning(long taskId) {
        return runningTasks.containsKey(taskId);
    }

    /**
     * 停止所有运行中的压测任务（心跳失联自停调用，服务端不可达故不发回执，仅 WARN 日志）。
     */
    @Override
    public void stopAllRunning() {
        for (Map.Entry<Long, RunningTask> entry : runningTasks.entrySet()) {
            long taskId = entry.getKey();
            RunningTask running = entry.getValue();
            log.warn("[Task] 失联自停：强制停止运行中的任务 {}，pid={}", taskId, running.process.pid());
            if (runningTasks.remove(taskId, running)) {
                terminate(taskId, running);
            }
            stopCollectorQuietly(taskId);
        }
    }

    /**
     * 容器销毁清理：先冲洗全部指标采集器（尾窗 finished=true 上报），再关闭进程 watcher 线程池。
     */
    @PreDestroy
    public void shutdown() {
        stopAllCollectorsQuietly();
        processWatcher.shutdown();
    }

    /**
     * 获取压测脚本：优先使用服务端内嵌的 jmxContent 直接落盘（省一次网络往返）；
     * 未内嵌时从 {baseUrl}/agent/task/{taskId}/script.jmx 下载；均会与 scriptMd5 比对（提供时）。
     *
     * @param task    任务详情
     * @param taskDir 任务工作目录
     * @throws IOException 落盘/下载或校验失败
     */
    private void downloadScript(TaskSpec task, Path taskDir) throws IOException {
        Path target = taskDir.resolve(SCRIPT_FILE);
        if (task.jmxContent() != null && !task.jmxContent().isBlank()) {
            Files.writeString(target, task.jmxContent(), java.nio.charset.StandardCharsets.UTF_8);
            if (task.scriptMd5() != null && !task.scriptMd5().isBlank()) {
                verifyMd5(target, task.scriptMd5(), "压测脚本");
            }
            log.info("[Task] 任务 {} 压测脚本已由下发载荷内嵌写入（{} 字符）", task.taskId(), task.jmxContent().length());
            return;
        }
        String url = HttpDownloader.joinUrl(task.baseUrl(), "/agent/task/" + task.taskId() + "/script.jmx");
        downloader.download(url, target);
        if (task.scriptMd5() != null && !task.scriptMd5().isBlank()) {
            verifyMd5(target, task.scriptMd5(), "压测脚本");
        }
        log.info("[Task] 任务 {} 压测脚本下载完成: {}", task.taskId(), url);
    }

    /**
     * 下载单个附件：地址 baseUrl+downloadUrl（SPLIT 已是分片地址），落地文件名用 file.name；
     * SHARED 模式下载后校验 md5（SPLIT 的 md5 指向全量文件，不校验）。
     *
     * @param task    任务详情
     * @param file    附件描述
     * @param taskDir 任务工作目录
     * @throws IOException 下载或校验失败
     */
    private void downloadFile(TaskSpec task, TaskFile file, Path taskDir) throws IOException {
        String url = HttpDownloader.joinUrl(task.baseUrl(), file.downloadUrl());
        Path target = secureResolve(taskDir, file.name());
        downloader.download(url, target);
        if (file.isShared() && file.md5() != null && !file.md5().isBlank()) {
            verifyMd5(target, file.md5(), "附件 " + file.name());
        }
        log.info("[Task] 任务 {} 附件下载完成: name={}, mode={}, url={}", task.taskId(), file.name(), file.mode(), url);
    }

    /**
     * 校验文件 MD5（忽略大小写），不符抛 IOException。
     *
     * @param file     本地文件
     * @param expected 期望 MD5（十六进制）
     * @param what     校验对象描述（用于日志与异常信息）
     * @throws IOException 校验失败
     */
    private void verifyMd5(Path file, String expected, String what) throws IOException {
        String actual = downloader.md5Hex(file);
        if (!actual.equalsIgnoreCase(expected.trim())) {
            throw new IOException(what + " MD5 校验失败：期望 " + expected + "，实际 " + actual);
        }
    }

    /**
     * 兜底校验：扫描 JMX 中 CSVDataSet 引用的数据文件（filename 非空且非 JMeter 函数表达式），
     * 核对任务目录本地是否存在；返回第一个缺失的文件名，全部存在返回 null。
     *
     * @param jmxFile 压测脚本路径
     * @param taskDir 任务工作目录（文件相对该目录解析）
     * @return 缺失的文件名，或 null
     */
    private String findMissingDataFile(Path jmxFile, Path taskDir) {
        try {
            String jmx = Files.readString(jmxFile, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = CSV_FILENAME_PATTERN.matcher(jmx);
            while (matcher.find()) {
                String name = matcher.group(1).trim();
                if (name.isEmpty() || name.contains("${")) {
                    continue;
                }
                if (!Files.isRegularFile(taskDir.resolve(name).normalize())) {
                    return name;
                }
            }
        } catch (Exception e) {
            log.warn("[Task] 参数文件校验读取 JMX 失败（跳过兜底）: {}", e.getMessage());
        }
        return null;
    }

    /** JMX 中 CSVDataSet filename 属性提取正则（stringProp name="filename"，与 delimiter 同名节点的区分：filename 仅出现在 CSVDataSet） */
    private static final java.util.regex.Pattern CSV_FILENAME_PATTERN =
            java.util.regex.Pattern.compile("<stringProp name=\"filename\">([^<]+)</stringProp>");

    /**
     * 解析附件落地路径：支持相对子目录（保持 JMX 相对引用），防御路径越界（zip-slip 同类风险）。
     *
     * @param taskDir 任务工作目录
     * @param name    附件文件名（可能含相对路径）
     * @return 安全的落地路径
     * @throws IOException 文件名越界或父目录创建失败
     */
    private Path secureResolve(Path taskDir, String name) throws IOException {
        Path target = taskDir.resolve(name).normalize();
        // taskDir 可能含 "./" 相对段，比较前同样 normalize，避免合法文件名被误判越界
        if (!target.startsWith(taskDir.normalize())) {
            throw new IOException("非法附件文件名（越界）: " + name);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        return target;
    }

    /**
     * 清理同任务残留运行进程（重复 START/异常场景兜底），并标记跳过 watcher 回执。
     *
     * @param taskId 任务 ID
     */
    private void killResidualProcess(long taskId) {
        RunningTask residual = runningTasks.remove(taskId);
        if (residual == null) {
            return;
        }
        log.warn("[Task] 任务 {} 存在残留运行进程 pid={}，启动前强制清理", taskId, residual.process.pid());
        terminate(taskId, residual);
        stopCollectorQuietly(taskId);
    }

    /**
     * 清理上一次运行的输出文件（result.jtl 存在时 JMeter 会拒绝启动，jmeter.log 避免混淆）。
     *
     * @param taskDir 任务工作目录
     */
    private void cleanPreviousRunOutputs(Path taskDir) {
        try {
            Files.deleteIfExists(taskDir.resolve(RESULT_FILE));
            Files.deleteIfExists(taskDir.resolve(JMETER_LOG_FILE));
        } catch (IOException e) {
            log.warn("[Task] 清理上次运行输出失败（可能影响本次启动）: {}", e.getMessage());
        }
    }

    /**
     * 拼 JMeter 命令行：{jmeterBin} -n -t script.jmx -l result.jtl -j jmeter.log -J{k}={v}...，
     * jmeterProps 全量透传（相对路径基于任务工作目录）。
     *
     * @param jmeterBin JMeter 可执行文件
     * @param props     JMeter 属性透传表（PREPARE 时落盘的 jmeterProps）
     * @return 完整命令行参数列表
     */
    private List<String> buildCommand(Path jmeterBin, Map<String, Object> props) {
        List<String> command = new ArrayList<>();
        command.add(jmeterBin.toString());
        command.add("-n");
        command.add("-t");
        command.add(SCRIPT_FILE);
        command.add("-l");
        command.add(RESULT_FILE);
        command.add("-j");
        command.add(JMETER_LOG_FILE);
        if (props != null) {
            props.forEach((key, value) -> {
                if (key != null && value != null) {
                    command.add("-J" + key + "=" + value);
                }
            });
        }
        return command;
    }

    /**
     * watcher 线程体：等待进程退出并回执（主动停止的任务回执由停止方发送，此处跳过）；
     * 退出码 0 回执 FINISHED，否则 FAILED+尾 200 字符日志；endedAt 落盘 state.json。
     *
     * @param taskId  任务 ID
     * @param running 运行句柄
     */
    private void awaitExit(long taskId, RunningTask running) {
        try {
            int exitCode = running.process.waitFor();
            runningTasks.remove(taskId, running);
            // 进程退出即 JTL 定稿：冲洗尾窗（finished=true）并取最终样本数（-1 表示无采集器，无法判定）
            JtlMetricsCollector collector = collectors.remove(taskId);
            long samples = -1;
            if (collector != null) {
                try {
                    collector.stopAndFlush();
                    samples = collector.getSampleCount();
                } catch (Throwable t) {
                    log.warn("[Metrics] 任务 {} 指标采集器停止冲洗失败: {}", taskId, t.getMessage());
                }
            }
            if (running.stopRequested) {
                log.info("[Task] 任务 {} 进程已被主动停止，退出码 {}（回执由停止方发送）", taskId, exitCode);
                return;
            }
            // 零样本兜底：JMeter 可能全程零请求仍以退出码 0 结束（线程组被禁用/脚本逻辑缺陷等），
            // 此时报告必然为空，按 FAILED 回执避免误判 FINISHED 生成空报告
            if (exitCode == 0 && samples == 0) {
                String tail = tail(taskDir(taskId).resolve(JMETER_LOG_FILE), 200);
                updateStateQuietly(taskId, TaskState.STATUS_FAILED);
                log.warn("[Task] 任务 {} 运行结束但零样本，回执 FAILED", taskId);
                sendReceipt(taskId, TaskReceipt.PHASE_FAILED,
                        "运行结束但零样本，疑似脚本或参数配置问题，日志尾部: " + tail);
                return;
            }
            String status = exitCode == 0 ? TaskState.STATUS_FINISHED : TaskState.STATUS_FAILED;
            updateStateQuietly(taskId, status);
            if (exitCode == 0) {
                log.info("[Task] 任务 {} 运行结束，退出码 0，回执 FINISHED", taskId);
                sendReceipt(taskId, TaskReceipt.PHASE_FINISHED, "运行完成，退出码 0");
            } else {
                String tail = tail(taskDir(taskId).resolve(JMETER_LOG_FILE), 200);
                if (tail.isEmpty()) {
                    tail = tail(taskDir(taskId).resolve(STDOUT_FILE), 200);
                }
                log.warn("[Task] 任务 {} 运行异常结束，退出码 {}，回执 FAILED", taskId, exitCode);
                sendReceipt(taskId, TaskReceipt.PHASE_FAILED, "退出码 " + exitCode + "，日志尾部: " + tail);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.error("[Task] 任务 {} 进程等待异常", taskId, t);
        }
    }

    /**
     * 终止运行中的进程：标记 stopRequested（watcher 据此跳过回执）→ destroy →
     * 3 秒未退 destroyForcibly；异常仅记录不外抛。
     *
     * @param taskId  任务 ID
     * @param running 运行句柄
     */
    private void terminate(long taskId, RunningTask running) {
        running.stopRequested = true;
        try {
            Process process = running.process;
            process.destroy();
            if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("[Task] 任务 {} 进程 pid={} 优雅退出超时（{}s），执行 destroyForcibly",
                        taskId, process.pid(), STOP_GRACE_SECONDS);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.process.destroyForcibly();
        } catch (Throwable t) {
            log.error("[Task] 任务 {} 进程终止异常", taskId, t);
        }
    }

    /**
     * 读取文件尾部指定字符数（用于 FAILED 回执附带的日志尾部，UTF-8 解码）。
     *
     * @param file  目标文件
     * @param chars 尾部字符数
     * @return 尾部字符串，文件缺失或读取失败返回空串
     */
    private String tail(Path file, int chars) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            long size = Files.size(file);
            if (size == 0) {
                return "";
            }
            int bytes = (int) Math.min(size, chars * 4L + 8L);
            byte[] buffer = new byte[bytes];
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(size - bytes);
                raf.readFully(buffer);
            }
            String content = new String(buffer, StandardCharsets.UTF_8);
            return content.length() <= chars ? content : content.substring(content.length() - chars);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 发送任务回执（phase: READY/RUNNING/FINISHED/FAILED），message 超长截断；
     * 发送失败仅记录错误日志，不影响任务主流程。
     *
     * @param taskId  任务 ID
     * @param phase   回执阶段
     * @param message 附加说明
     */
    private void sendReceipt(long taskId, String phase, String message) {
        try {
            String trimmed = message == null ? ""
                    : (message.length() > RECEIPT_MESSAGE_MAX ? message.substring(0, RECEIPT_MESSAGE_MAX) : message);
            serverClient.sendReceipt(new TaskReceipt(taskId, lifecycle.currentNodeKey(), phase, trimmed));
            log.info("[Task] 任务回执已发送: taskId={}, phase={}, message={}", taskId, phase, trimmed);
        } catch (Throwable t) {
            log.error("[Task] 任务回执发送失败: taskId={}, phase={}", taskId, phase, t);
        }
    }

    /**
     * 为任务启动 JTL 指标采集器（START 成功后调用）：先停掉同 taskId 旧采集器（重复 START 防重复），
     * nodeKey 取自注册信息（未注册时告警跳过，不影响压测主流程）。
     *
     * @param taskId   任务 ID
     * @param jtlFile  该任务 result.jtl 路径（可暂不存在，采集器轮询等待其出现）
     */
    private void startCollector(long taskId, Path jtlFile) {
        stopCollectorQuietly(taskId);
        String nodeKey = lifecycle.currentNodeKey();
        if (nodeKey == null || nodeKey.isBlank()) {
            log.warn("[Metrics] 任务 {} nodeKey 未就绪（未注册），跳过指标采集", taskId);
            return;
        }
        JtlMetricsCollector collector = new JtlMetricsCollector(taskId, nodeKey, jtlFile,
                this::sendMetricsReport, properties.getMetricsWindowMs());
        collectors.put(taskId, collector);
        collector.start();
    }

    /**
     * 停止并冲洗任务指标采集器（幂等，不存在时无操作）：补读 JTL 尾部增量并以 finished=true 封尾窗上报。
     *
     * @param taskId 任务 ID
     */
    private void stopCollectorQuietly(long taskId) {
        JtlMetricsCollector collector = collectors.remove(taskId);
        if (collector == null) {
            return;
        }
        try {
            collector.stopAndFlush();
        } catch (Throwable t) {
            log.warn("[Metrics] 任务 {} 指标采集器停止冲洗失败: {}", taskId, t.getMessage());
        }
    }

    /**
     * 停止并冲洗全部指标采集器（容器销毁时调用）。
     */
    private void stopAllCollectorsQuietly() {
        for (Long taskId : collectors.keySet()) {
            stopCollectorQuietly(taskId);
        }
    }

    /**
     * 窗口指标上报回调（供采集器调用）：异常由采集器上报线程统一捕获（code!=0 WARN、网络失败静默）。
     *
     * @param report 窗口指标报告
     */
    private void sendMetricsReport(MetricsReport report) {
        serverClient.sendMetrics(report);
    }

    /**
     * 读取任务本地状态（state.json），文件缺失/损坏返回 null。
     *
     * @param taskId 任务 ID
     * @return 任务状态，无则 null
     */
    private TaskState loadState(long taskId) {
        Path file = taskDir(taskId).resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Jsons.parse(Files.readString(file, StandardCharsets.UTF_8), TaskState.class);
        } catch (Exception e) {
            log.warn("[Task] 任务 {} 状态文件读取失败，视为无状态: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * 持久化任务状态到 state.json。
     *
     * @param state 任务状态
     * @throws IOException 写入失败
     */
    private void saveState(TaskState state) throws IOException {
        Path dir = taskDir(state.taskId());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(STATE_FILE), Jsons.write(state), StandardCharsets.UTF_8);
    }

    /**
     * 静默更新任务状态（保留 startedAt/jmeterProps，写入 endedAt），失败仅告警。
     *
     * @param taskId 任务 ID
     * @param status 目标状态
     */
    private void updateStateQuietly(long taskId, String status) {
        try {
            TaskState current = loadState(taskId);
            saveState(new TaskState(taskId, status,
                    current == null ? null : current.startedAt(),
                    System.currentTimeMillis(),
                    current == null ? null : current.jmeterProps(),
                    current == null ? null : current.jmeterHeapMb()));
        } catch (Exception e) {
            log.warn("[Task] 任务 {} 状态更新失败: {}", taskId, e.getMessage());
        }
    }

    /**
     * 任务工作目录 data-dir/tasks/{taskId} 路径。
     *
     * @param taskId 任务 ID
     * @return 工作目录路径
     */
    private Path taskDir(long taskId) {
        // 统一转绝对路径：ProcessBuilder 的相对 program 路径会按 directory() 解析，
        // 相对路径配置（./agent-data）下会导致可执行文件路径错拼而启动失败
        return Path.of(properties.getDataDir()).toAbsolutePath().normalize()
                .resolve("tasks").resolve(String.valueOf(taskId));
    }

    /**
     * 运行中任务句柄：进程引用 + 主动停止标记（watcher 据此跳过重复回执）。
     */
    private static final class RunningTask {

        /** JMeter 子进程 */
        private final Process process;

        /** 是否被主动停止（STOP/失联自停/残留清理），true 时 watcher 不发送回执 */
        private volatile boolean stopRequested;

        /**
         * 构造运行句柄。
         *
         * @param process JMeter 子进程
         */
        private RunningTask(Process process) {
            this.process = process;
        }
    }
}
