package com.per.agent.core;

import com.per.agent.client.AgentServerException;
import com.per.agent.client.HeartbeatRequest;
import com.per.agent.client.RegisterRequest;
import com.per.agent.client.RegisterResponse;
import com.per.agent.client.ServerClient;
import com.per.agent.common.AgentVersion;
import com.per.agent.config.AgentProperties;
import com.per.agent.engine.EngineManager;
import com.per.agent.monitor.SystemResourceCollector;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Agent 生命周期编排器（启动编排）：
 * <p>1. 启动时创建工作目录（data-dir、engine/、tasks/）；
 * 2. 读取本地 data-dir/node.key——存在则直接发心跳恢复会话；
 * 3. node.key 不存在或心跳返回 4011（节点未注册）则走注册流程，注册成功后持久化 node_key；
 * 4. 注册失败（含 token 无效 1001）按指数退避（5s 起步、最大 60s）持续重试（仅日志）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLifecycle implements ApplicationRunner {

    /** node_key 持久化文件名（位于 data-dir 下） */
    private static final String NODE_KEY_FILE = "node.key";

    /** Agent 版本号（统一维护于 common.AgentVersion） */
    private static final String AGENT_VERSION = AgentVersion.VERSION;

    /** 注册失败退避起始间隔（毫秒） */
    private static final long BACKOFF_BASE_MS = 5_000L;

    /** 注册失败退避上限（毫秒） */
    private static final long BACKOFF_MAX_MS = 60_000L;

    private final AgentProperties properties;
    private final ServerClient serverClient;
    private final SystemResourceCollector resourceCollector;
    private final EngineManager engineManager;

    /** 已注册标记：心跳/轮询任务据此判断是否开始工作 */
    private final AtomicBoolean registered = new AtomicBoolean(false);

    /** 当前节点密钥（注册成功后非空） */
    private volatile String nodeKey;

    /**
     * 启动编排入口：目录初始化 → 会话恢复/注册（未成功前按指数退避重试）。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureWorkDirs();
            // 停止标记检测：本机曾被管理员从平台删除（心跳收到 4090 自停时写入），
            // 直接退出不再运行；恢复方式：删除该标记文件并重启，或重新执行安装命令
            Path stopFlag = dataDir().resolve("STOPPED");
            if (Files.exists(stopFlag)) {
                log.error("[Lifecycle] 检测到停止标记 {}：本节点已被管理员从平台移除，Agent 不启动。"
                        + "如需恢复：删除该文件后重启，或重新执行平台安装命令。", stopFlag.toAbsolutePath());
                Runtime.getRuntime().halt(1);
                return;
            }
            bootstrapWithBackoff();
        } catch (Throwable t) {
            log.error("[Lifecycle] 启动编排出现未预期异常", t);
        }
    }

    /**
     * 当前节点是否已完成注册（心跳/轮询任务的启动门槛）。
     *
     * @return true 表示已持有有效 node_key
     */
    public boolean isRegistered() {
        return registered.get();
    }

    /**
     * 获取当前节点密钥（未注册时为 null）。
     *
     * @return node_key
     */
    public String currentNodeKey() {
        return nodeKey;
    }

    /**
     * 带指数退避的会话引导：恢复会话或注册，失败则 5s→60s 退避重试直至成功。
     */
    private void bootstrapWithBackoff() {
        long backoff = BACKOFF_BASE_MS;
        while (!registered.get()) {
            try {
                resumeOrRegister();
                backoff = BACKOFF_BASE_MS;
            } catch (Exception e) {
                if (e instanceof AgentServerException ase
                        && ase.getCode() == AgentServerException.CODE_TOKEN_INVALID) {
                    log.error("[Lifecycle] 注册 token 无效(code=1001)，请检查 per.agent.register-token 配置，{}ms 后重试", backoff);
                } else {
                    log.error("[Lifecycle] 与服务端建立会话失败，{}ms 后重试: {}", backoff, e.getMessage());
                }
                sleepQuietly(backoff);
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
            }
        }
    }

    /**
     * 恢复已有会话（本地存在 node.key 时先发一次探测心跳）或执行全新注册。
     *
     * @throws IOException 本地 node_key 文件读写失败
     */
    private void resumeOrRegister() throws IOException {
        String savedKey = loadNodeKey();
        if (savedKey != null) {
            try {
                serverClient.heartbeat(buildHeartbeatRequest(savedKey));
                this.nodeKey = savedKey;
                this.registered.set(true);
                log.info("[Lifecycle] 复用本地 node_key 恢复会话成功: {}", maskKey(savedKey));
                return;
            } catch (AgentServerException e) {
                if (e.getCode() == AgentServerException.CODE_NODE_NOT_REGISTERED) {
                    log.warn("[Lifecycle] 服务端返回 4011（节点未注册），本地 node_key 已失效，转入注册流程");
                    deleteNodeKey();
                } else {
                    throw e;
                }
            }
        }
        doRegister();
    }

    /**
     * 执行注册：携带本机身份信息调用 /agent/register，成功后持久化 node_key 并触发引擎检查。
     *
     * @throws IOException node_key 持久化失败
     */
    private void doRegister() throws IOException {
        RegisterRequest request = new RegisterRequest(
                properties.getRegisterToken(),
                localHostname(),
                localIp(),
                osDescription(),
                System.getProperty("java.version"),
                AGENT_VERSION);
        RegisterResponse response = serverClient.register(request);
        if (response == null || response.nodeKey() == null || response.nodeKey().isBlank()) {
            throw new AgentServerException(-1, "注册响应缺少 nodeKey");
        }
        this.nodeKey = response.nodeKey();
        persistNodeKey(response.nodeKey());
        this.registered.set(true);
        log.info("[Lifecycle] 注册成功，nodeKey={}", maskKey(response.nodeKey()));
        engineManager.ensureEngine(response.engine());
    }

    /**
     * 构建心跳请求体：采集本机真实资源快照（会话恢复探测复用同一结构），
     * 含网络收发速率（物理网卡累计字节差分）。
     *
     * @param nodeKey 节点密钥
     * @return 心跳请求体
     */
    private HeartbeatRequest buildHeartbeatRequest(String nodeKey) {
        double[] netBps = resourceCollector.collectNetBps();
        return new HeartbeatRequest(
                nodeKey,
                resourceCollector.collectCpuUsage(),
                resourceCollector.collectMemUsage(),
                resourceCollector.collectMemTotal(),
                resourceCollector.collectJvmMemUsed(),
                resourceCollector.collectJvmMemMax(),
                netBps[0],
                netBps[1],
                engineManager.getDeployedVersion(),
                AgentVersion.VERSION);
    }

    /**
     * 创建工作目录：data-dir、engine/、tasks/。
     *
     * @throws IOException 目录创建失败
     */
    private void ensureWorkDirs() throws IOException {
        Files.createDirectories(engineDir());
        Files.createDirectories(tasksDir());
        log.info("[Lifecycle] 工作目录就绪: {}", dataDir().toAbsolutePath());
    }

    /**
     * 读取本地持久化的 node_key，文件不存在或内容为空返回 null。
     *
     * @return node_key 或 null
     * @throws IOException 文件读取失败
     */
    private String loadNodeKey() throws IOException {
        Path file = nodeKeyFile();
        if (!Files.exists(file)) {
            return null;
        }
        String key = Files.readString(file, StandardCharsets.UTF_8).trim();
        return key.isEmpty() ? null : key;
    }

    /**
     * 持久化 node_key 到 data-dir/node.key。
     *
     * @param key 节点密钥
     * @throws IOException 文件写入失败
     */
    private void persistNodeKey(String key) throws IOException {
        Files.writeString(nodeKeyFile(), key, StandardCharsets.UTF_8);
    }

    /**
     * 删除已失效的 node_key 文件。
     *
     * @throws IOException 文件删除失败
     */
    private void deleteNodeKey() throws IOException {
        Files.deleteIfExists(nodeKeyFile());
    }

    /**
     * 获取本机主机名，获取失败回退 unknown。
     *
     * @return 主机名
     */
    private String localHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取本机 IP：优先取站点本地地址（如 192.168.x.x），失败回退 127.0.0.1。
     *
     * @return 本机 IPv4 地址
     */
    private String localIp() {
        try {
            return NetworkInterface.networkInterfaces()
                    .flatMap(ni -> Collections.list(ni.getInetAddresses()).stream())
                    .filter(addr -> addr instanceof Inet4Address && addr.isSiteLocalAddress())
                    .map(InetAddress::getHostAddress)
                    .findFirst()
                    .orElse(InetAddress.getLocalHost().getHostAddress());
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 获取操作系统描述（名称 + 版本 + 架构）。
     *
     * @return 操作系统描述
     */
    private String osDescription() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
    }

    /**
     * 日志脱敏：仅保留 node_key 前 4 位与后 4 位。
     *
     * @param key 原始 node_key
     * @return 脱敏后的 node_key
     */
    private String maskKey(String key) {
        if (key == null || key.length() <= 8) {
            return key;
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /**
     * 静默休眠：仅恢复中断标记，不向外抛出异常。
     *
     * @param millis 休眠毫秒数
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 工作目录 data-dir 路径。
     *
     * @return data-dir 路径
     */
    private Path dataDir() {
        return Path.of(properties.getDataDir());
    }

    /**
     * 引擎目录 data-dir/engine 路径。
     *
     * @return 引擎目录路径
     */
    private Path engineDir() {
        return dataDir().resolve("engine");
    }

    /**
     * 任务目录 data-dir/tasks 路径。
     *
     * @return 任务目录路径
     */
    private Path tasksDir() {
        return dataDir().resolve("tasks");
    }

    /**
     * node_key 持久化文件路径。
     *
     * @return node.key 文件路径
     */
    private Path nodeKeyFile() {
        return dataDir().resolve(NODE_KEY_FILE);
    }
}
