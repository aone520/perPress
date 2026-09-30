package com.per.agent.engine;

import com.per.agent.client.EngineInfo;
import com.per.agent.common.HttpDownloader;
import com.per.agent.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 引擎管理器（M2 完整实现）：根据服务端发布的引擎信息（version/md5/url）执行
 * 校验-下载-MD5 校验-解压-原子替换 的自动部署流程。
 * <p>目录约定：data-dir/engine 即 ENGINE_HOME（内含 bin/jmeter 与 engine.properties 版本记录）；
 * 部署过程先解压到 data-dir/engine.tmp-xxx 再 rename 原子替换，失败回滚不影响旧引擎。
 * 下载/校验失败自动重试（最多 3 次，指数退避），MD5 不符删除重下；
 * 部署在独立单线程（engine-deployer）异步执行，避免大文件下载阻塞心跳/轮询调度线程。</p>
 */
@Slf4j
@Component
public class EngineManager {

    /** 引擎目录名（data-dir 下，即 ENGINE_HOME） */
    private static final String ENGINE_DIR_NAME = "engine";

    /** 引擎版本记录文件名（位于 ENGINE_HOME 内） */
    private static final String PROPERTIES_FILE = "engine.properties";

    /** 引擎包下载/校验最大尝试次数 */
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;

    /** 重试退避基数（毫秒），按 2s→4s→8s 指数退避 */
    private static final long RETRY_BACKOFF_BASE_MS = 2_000L;

    /** 同一版本连续部署失败后的冷却时间（毫秒），避免每次心跳都触发整轮下载重试 */
    private static final long FAILURE_COOLDOWN_MS = 60_000L;

    private final AgentProperties properties;
    private final HttpDownloader downloader;

    /** 已部署引擎版本（部署成功后更新，用于心跳上报 engineVersion） */
    private volatile String deployedVersion;

    /** 是否已打印过"平台暂未发布引擎版本"日志（避免每次心跳重复刷屏） */
    private volatile boolean noEngineLogged = false;

    /** 是否已探测过本地引擎状态（平台未发布引擎信息时的一次性本地恢复） */
    private volatile boolean localStateProbed = false;

    /** 最近一次部署失败的版本（用于失败冷却） */
    private volatile String lastFailedVersion;

    /** 最近一次部署失败时间戳（毫秒） */
    private volatile long lastFailedAtMillis;

    /** 部署进行中标记（防止注册与心跳并发触发重复部署） */
    private final AtomicBoolean deploying = new AtomicBoolean(false);

    /** 引擎部署专用单线程（守护线程，随 JVM 退出） */
    private final ExecutorService deployExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "engine-deployer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 构造引擎管理器。
     *
     * @param properties Agent 配置
     * @param downloader 通用下载器（下载 + MD5）
     */
    public EngineManager(AgentProperties properties, HttpDownloader downloader) {
        this.properties = properties;
        this.downloader = downloader;
    }

    /**
     * 检查并按需部署引擎（注册/心跳触发，异常全捕获不中断调用方调度）：
     * engine 为 null 视为平台暂未发布；本地 engine.properties 中 version 与 md5 均一致且
     * bin/jmeter 存在则跳过；否则异步执行完整部署流程。
     *
     * @param engine 注册/心跳响应中的引擎发布信息
     */
    public void ensureEngine(EngineInfo engine) {
        try {
            if (engine == null || engine.version() == null || engine.version().isBlank()) {
                if (!noEngineLogged) {
                    log.info("[Engine] 平台暂未发布引擎版本，跳过引擎部署");
                    noEngineLogged = true;
                } else {
                    log.debug("[Engine] 平台暂未发布引擎版本，跳过引擎部署");
                }
                probeLocalStateOnce();
                return;
            }
            if (matchesLocalDeploy(engine)) {
                deployedVersion = engine.version();
                return;
            }
            submitDeploy(engine);
        } catch (Throwable t) {
            log.error("[Engine] 引擎部署检查出现未预期异常: {}", t.getMessage(), t);
        }
    }

    /**
     * 获取当前已部署引擎版本（未部署返回 null，用于心跳上报 engineVersion 字段）。
     *
     * @return 已部署引擎版本
     */
    public String getDeployedVersion() {
        return deployedVersion;
    }

    /**
     * 获取 JMeter 可执行文件路径（ENGINE_HOME/bin/jmeter，mac/linux 启动脚本）：
     * 做存在性校验，并每次调用幂等地赋予可执行权限（解压/复制会丢失权限位）。
     *
     * @return jmeter 可执行文件路径，未部署或文件缺失返回 null
     */
    public Path getJmeterBin() {
        // 转绝对路径：JMeter 以子进程启动时，相对路径会按 ProcessBuilder.directory() 解析导致错拼
        Path bin = engineDir().toAbsolutePath().normalize().resolve("bin").resolve("jmeter");
        if (!Files.isRegularFile(bin)) {
            return null;
        }
        try {
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("[Engine] 为 jmeter 赋予可执行权限失败（继续返回路径）: {}", e.getMessage());
        }
        return bin;
    }

    /**
     * 容器销毁清理：关闭部署线程池。
     */
    @PreDestroy
    public void shutdown() {
        deployExecutor.shutdown();
    }

    /**
     * 提交异步部署任务：部署进行中忽略；同版本失败冷却期内跳过；CAS 抢占部署权。
     *
     * @param engine 引擎发布信息
     */
    private void submitDeploy(EngineInfo engine) {
        if (deploying.get()) {
            log.debug("[Engine] 引擎部署进行中，忽略本次触发");
            return;
        }
        if (engine.version().equals(lastFailedVersion)
                && System.currentTimeMillis() - lastFailedAtMillis < FAILURE_COOLDOWN_MS) {
            log.debug("[Engine] 引擎版本 {} 部署失败冷却中，等待后续心跳自动重试", engine.version());
            return;
        }
        if (!deploying.compareAndSet(false, true)) {
            return;
        }
        log.info("[Engine] 检测到新引擎版本 {}（md5={}），开始异步部署", engine.version(), engine.md5());
        deployExecutor.execute(() -> {
            try {
                deploy(engine);
            } catch (Throwable t) {
                log.error("[Engine] 引擎部署线程异常: {}", t.getMessage(), t);
            } finally {
                deploying.set(false);
            }
        });
    }

    /**
     * 执行完整部署流程：下载（带 MD5 校验重试）→ 解压到 engine.tmp-xxx → 定位 ENGINE_HOME →
     * 写入版本记录 → 原子替换 engine/ 目录；任一失败记录冷却标记并清理临时目录。
     *
     * @param engine 引擎发布信息
     */
    private void deploy(EngineInfo engine) {
        long stamp = System.currentTimeMillis();
        Path zipFile = dataDir().resolve(ENGINE_DIR_NAME + "-download-" + stamp + ".zip.tmp");
        Path tmpDir = dataDir().resolve(ENGINE_DIR_NAME + ".tmp-" + stamp);
        try {
            downloadWithRetry(engine, zipFile);
            Files.createDirectories(tmpDir);
            unzip(zipFile, tmpDir);
            Path engineHome = locateJmeterRoot(tmpDir);
            if (engineHome == null) {
                throw new IOException("解压后未定位到包含 bin/jmeter 的引擎根目录");
            }
            writeEngineProperties(engineHome, engine);
            atomicReplace(engineHome, tmpDir);
            deployedVersion = engine.version();
            lastFailedVersion = null;
            log.info("[Engine] 引擎版本 {} 部署完成，ENGINE_HOME={}，jmeter={}",
                    engine.version(), engineDir().toAbsolutePath(), getJmeterBin());
        } catch (Throwable t) {
            lastFailedVersion = engine.version();
            lastFailedAtMillis = System.currentTimeMillis();
            deleteRecursively(tmpDir);
            log.error("[Engine] 引擎版本 {} 部署失败: {}", engine.version(), t.getMessage(), t);
        } finally {
            try {
                Files.deleteIfExists(zipFile);
            } catch (IOException e) {
                log.warn("[Engine] 引擎包临时文件清理失败: {} - {}", zipFile, e.getMessage());
            }
        }
    }

    /**
     * 带重试的引擎包下载与 MD5 校验：不符或失败删除临时文件，指数退避（2s→4s→8s）重试，
     * 最多 {@value #MAX_DOWNLOAD_ATTEMPTS} 次。
     *
     * @param engine  引擎发布信息
     * @param zipFile 临时落盘路径
     * @throws IOException      下载/校验失败
     * @throws InterruptedException 重试等待被中断
     */
    private void downloadWithRetry(EngineInfo engine, Path zipFile) throws IOException, InterruptedException {
        String expected = engine.md5() == null ? "" : engine.md5().trim().toLowerCase();
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                String url = HttpDownloader.joinUrl(properties.getServerUrl(), engine.url());
                log.info("[Engine] 开始下载引擎包（第 {}/{} 次）: {}", attempt, MAX_DOWNLOAD_ATTEMPTS, url);
                downloader.download(url, zipFile);
                if (!expected.isEmpty()) {
                    String actual = downloader.md5Hex(zipFile);
                    if (!actual.equals(expected)) {
                        throw new IOException("引擎包 MD5 校验失败：期望 " + expected + "，实际 " + actual);
                    }
                    log.info("[Engine] 引擎包 MD5 校验通过: {}", actual);
                }
                return;
            } catch (Exception e) {
                safeDelete(zipFile);
                if (attempt == MAX_DOWNLOAD_ATTEMPTS) {
                    throw new IOException("引擎包下载/校验连续 " + MAX_DOWNLOAD_ATTEMPTS + " 次失败: " + e.getMessage(), e);
                }
                long backoff = RETRY_BACKOFF_BASE_MS << (attempt - 1);
                log.warn("[Engine] 引擎包下载/校验失败（第 {} 次）: {}，{}ms 后重试", attempt, e.getMessage(), backoff);
                Thread.sleep(backoff);
            }
        }
    }

    /**
     * 解压 zip 到目标目录（防御 zip-slip 越界路径，目录条目自动创建）。
     *
     * @param zipFile   引擎包路径
     * @param targetDir 解压目标目录
     * @throws IOException 解压失败
     */
    private void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path target = targetDir.resolve(entry.getName()).normalize();
                // targetDir 可能含 "./" 等相对段（相对路径配置），比较前需同样 normalize，否则合法条目被误判
                if (!target.startsWith(targetDir.normalize())) {
                    throw new IOException("引擎包内存在非法路径条目: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        log.info("[Engine] 引擎包解压完成: {} → {}", zipFile.getFileName(), targetDir.getFileName());
    }

    /**
     * 定位解压结果中的 ENGINE_HOME：zip 内可能是单层目录（如 apache-jmeter-x.x.x/），
     * 优先取解压根目录，否则在直接子目录中查找包含 bin/jmeter 的那个。
     *
     * @param extracted 解压根目录
     * @return ENGINE_HOME 路径，未找到返回 null
     * @throws IOException 目录遍历失败
     */
    private Path locateJmeterRoot(Path extracted) throws IOException {
        if (isJmeterRoot(extracted)) {
            return extracted;
        }
        try (Stream<Path> children = Files.list(extracted)) {
            return children.filter(Files::isDirectory)
                    .filter(this::isJmeterRoot)
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 判断目录是否为 JMeter 根目录（含 bin/jmeter 启动脚本）。
     *
     * @param dir 待判断目录
     * @return true 表示包含 bin/jmeter
     */
    private boolean isJmeterRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("bin").resolve("jmeter"));
    }

    /**
     * 向 ENGINE_HOME 写入 engine.properties（记录 version 与 md5，供下次比对跳过重复部署）。
     *
     * @param engineHome 引擎根目录
     * @param engine     引擎发布信息
     * @throws IOException 写入失败
     */
    private void writeEngineProperties(Path engineHome, EngineInfo engine) throws IOException {
        Properties props = new Properties();
        props.setProperty("version", engine.version() == null ? "" : engine.version());
        props.setProperty("md5", engine.md5() == null ? "" : engine.md5());
        try (OutputStream out = Files.newOutputStream(engineHome.resolve(PROPERTIES_FILE))) {
            props.store(out, "PerPress agent engine deployment record");
        }
    }

    /**
     * 原子替换 engine/ 目录：旧目录先 rename 为 engine.old-xxx 备份，新 ENGINE_HOME rename 到位，
     * 失败回滚备份；成功后清理备份与解压临时目录残留。
     *
     * @param engineHome 解压定位出的新 ENGINE_HOME
     * @param tmpDir     解压临时目录（engine.tmp-xxx）
     * @throws IOException 替换失败
     */
    private void atomicReplace(Path engineHome, Path tmpDir) throws IOException {
        Path target = engineDir();
        Path backup = null;
        if (Files.exists(target)) {
            backup = dataDir().resolve(ENGINE_DIR_NAME + ".old-" + System.currentTimeMillis());
            Files.move(target, backup);
        }
        try {
            Files.move(engineHome, target);
        } catch (IOException e) {
            if (backup != null && !Files.exists(target)) {
                Files.move(backup, target); // 回滚旧引擎
            }
            throw e;
        }
        if (backup != null) {
            deleteRecursively(backup);
        }
        if (!engineHome.equals(tmpDir)) {
            deleteRecursively(tmpDir); // 清理单层目录壳残留
        }
    }

    /**
     * 比对本地已部署引擎：engine.properties 的 version/md5 与服务端发布一致且 bin/jmeter 存在。
     *
     * @param engine 引擎发布信息
     * @return true 表示本地已是目标版本
     */
    private boolean matchesLocalDeploy(EngineInfo engine) {
        try {
            Path file = engineDir().resolve(PROPERTIES_FILE);
            if (!Files.isRegularFile(file) || getJmeterBin() == null) {
                return false;
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            boolean versionMatch = engine.version() != null && engine.version().equals(props.getProperty("version"));
            boolean md5Match = Objects.equals(normalizeMd5(engine.md5()), normalizeMd5(props.getProperty("md5")));
            if (versionMatch && md5Match) {
                log.debug("[Engine] 引擎版本 {} 已部署且 version/md5 一致，无需重复部署", engine.version());
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[Engine] 本地引擎状态读取失败，按未部署处理: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 一次性探测本地引擎状态：平台未发布引擎信息但本地存在已部署引擎时，
     * 恢复 deployedVersion 用于心跳上报（进程重启后不丢引擎版本）。
     */
    private void probeLocalStateOnce() {
        if (localStateProbed) {
            return;
        }
        localStateProbed = true;
        try {
            Path file = engineDir().resolve(PROPERTIES_FILE);
            if (!Files.isRegularFile(file) || getJmeterBin() == null) {
                return;
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            String version = props.getProperty("version");
            if (version != null && !version.isBlank()) {
                deployedVersion = version;
                log.info("[Engine] 检测到本地已部署引擎版本 {}（平台当前未发布引擎信息）", version);
            }
        } catch (Exception e) {
            log.debug("[Engine] 本地引擎状态探测失败: {}", e.getMessage());
        }
    }

    /**
     * 规范化 MD5 字符串（去空格、转小写，空值返回 null）用于比对。
     *
     * @param md5 原始 MD5 串
     * @return 规范化 MD5 串
     */
    private String normalizeMd5(String md5) {
        if (md5 == null || md5.isBlank()) {
            return null;
        }
        return md5.trim().toLowerCase();
    }

    /**
     * 递归删除目录/文件（失败仅告警，不抛出）。
     *
     * @param root 待删除根路径
     */
    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删除失败不影响其余清理
                }
            });
        } catch (Exception e) {
            log.warn("[Engine] 临时目录清理失败: {} - {}", root, e.getMessage());
        }
    }

    /**
     * 静默删除文件（失败仅告警）。
     *
     * @param file 待删除文件
     */
    private void safeDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[Engine] 临时文件删除失败: {} - {}", file, e.getMessage());
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
     * 引擎目录 data-dir/engine（即 ENGINE_HOME）路径。
     *
     * @return 引擎目录路径
     */
    private Path engineDir() {
        return dataDir().resolve(ENGINE_DIR_NAME);
    }
}
