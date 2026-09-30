package com.per.agent.core;

import com.per.agent.client.AgentServerException;
import com.per.agent.client.HeartbeatRequest;
import com.per.agent.client.HeartbeatResponse;
import com.per.agent.client.ServerClient;
import com.per.agent.common.AgentVersion;
import com.per.agent.config.AgentProperties;
import com.per.agent.engine.EngineManager;
import com.per.agent.monitor.SystemResourceCollector;
import com.per.agent.task.TaskExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 心跳定时任务：按配置间隔采集本机资源并上报服务端。
 * <p>连续失败达到 失联自停阈值/心跳间隔 次数时，触发失联自停：
 * 停止本机所有运行中的压测任务并输出 WARN 日志"失联自停"（避免服务端失联后压测负载失控）。</p>
 * <p>服务端返回 4090（节点被管理员删除）时：写停止标记、停止压测任务并退出 Agent 进程，
 * 同时尝试停掉 systemd 服务避免 Restart=always 拉起造成循环。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatTask {

    /** 服务端返回码：节点已被管理员删除 */
    private static final int CODE_NODE_REVOKED = 4090;

    private final AgentProperties properties;
    private final AgentLifecycle lifecycle;
    private final ServerClient serverClient;
    private final SystemResourceCollector resourceCollector;
    private final EngineManager engineManager;
    private final TaskExecutor taskExecutor;

    /** 连续失败计数（成功后清零） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /**
     * 心跳调度入口：fixedDelay=心跳间隔，首次启动延迟 3s；
     * 方法体异常全捕获（ERROR 日志），确保调度永不中断。
     */
    @Scheduled(fixedDelayString = "${per.agent.heartbeat-interval-seconds}000", initialDelay = 3000)
    public void heartbeat() {
        try {
            doHeartbeat();
        } catch (Throwable t) {
            handleFailure(t);
        }
    }

    /**
     * 执行一次心跳：未完成注册前静默跳过，否则采集资源快照并上报。
     */
    private void doHeartbeat() {
        if (!lifecycle.isRegistered()) {
            return;
        }
        HeartbeatRequest request = new HeartbeatRequest(
                lifecycle.currentNodeKey(),
                resourceCollector.collectCpuUsage(),
                resourceCollector.collectMemUsage(),
                resourceCollector.collectMemTotal(),
                resourceCollector.collectJvmMemUsed(),
                resourceCollector.collectJvmMemMax(),
                engineManager.getDeployedVersion(),
                AgentVersion.VERSION);
        HeartbeatResponse response = serverClient.heartbeat(request);
        handleSuccess(response);
    }

    /**
     * 心跳成功处理：失败计数清零、失联恢复时打印恢复日志，并触发引擎检查部署。
     *
     * @param response 心跳响应
     */
    private void handleSuccess(HeartbeatResponse response) {
        int failed = consecutiveFailures.getAndSet(0);
        if (failed > 0) {
            log.info("[Heartbeat] 失联恢复：此前连续失败 {} 次，心跳已恢复正常", failed);
        }
        if (response != null && response.serverTime() != null) {
            log.debug("[Heartbeat] 心跳成功，serverTime={}", response.serverTime());
        }
        engineManager.ensureEngine(response == null ? null : response.engine());
    }

    /**
     * 心跳失败处理：4090（节点被管理员删除）触发自停退出；
     * 其余失败累计连续次数，达到失联阈值次数时触发失联自停
     * （停止所有运行中的压测任务，输出 WARN 日志"失联自停"）。
     *
     * @param t 心跳失败异常
     */
    private void handleFailure(Throwable t) {
        // 节点被管理员删除：写停止标记并退出进程（systemd 场景同步停服务防拉起）
        if (t instanceof AgentServerException agentError && agentError.getCode() == CODE_NODE_REVOKED) {
            log.warn("[Heartbeat] 平台已移除本节点：{}。Agent 将停止服务；如需恢复请重新执行安装命令。",
                    t.getMessage());
            shutdownByRevocation();
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        long interval = Math.max(1, properties.getHeartbeatIntervalSeconds());
        long threshold = Math.max(1, properties.getHeartbeatLostStopSeconds() / interval);
        log.error("[Heartbeat] 心跳上报失败（连续第 {} 次，失联阈值 {} 次）: {}", failures, threshold, t.getMessage(), t);
        if (failures == threshold) {
            log.warn("[Heartbeat] 已失联 {} 秒，触发失联自停：停止所有运行中的压测任务",
                    properties.getHeartbeatLostStopSeconds());
            try {
                taskExecutor.stopAllRunning();
            } catch (Throwable stopError) {
                log.error("[Heartbeat] 失联自停执行异常: {}", stopError.getMessage(), stopError);
            }
        }
    }

    /**
     * 节点被吊销后的自停流程：停止运行中的压测任务 → 写 STOP 标记（防止 systemd 拉起后重复运行）
     * → 尝试 systemctl stop perpress-agent（Linux 安装场景，失败忽略）→ 退出进程。
     */
    private void shutdownByRevocation() {
        try {
            taskExecutor.stopAllRunning();
        } catch (Throwable t) {
            log.warn("[Heartbeat] 停止压测任务异常（继续退出）: {}", t.getMessage());
        }
        try {
            Path stopFlag = Path.of(properties.getDataDir()).toAbsolutePath().normalize().resolve("STOPPED");
            Files.writeString(stopFlag, String.valueOf(System.currentTimeMillis()));
            log.warn("[Heartbeat] 已写入停止标记 {}，重启 Agent 前需删除该文件或重新安装", stopFlag);
        } catch (IOException e) {
            log.warn("[Heartbeat] 停止标记写入失败: {}", e.getMessage());
        }
        // systemd 部署场景：停掉自身服务单元，避免 Restart=always 反复拉起
        try {
            new ProcessBuilder("systemctl", "stop", "perpress-agent").inheritIO().start();
        } catch (Throwable ignore) {
            // 非 Linux / 无 systemd / 无权限时忽略，直接退出进程
        }
        Runtime.getRuntime().halt(0);
    }
}
