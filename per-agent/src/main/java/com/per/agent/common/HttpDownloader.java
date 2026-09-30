package com.per.agent.common;

import com.per.agent.config.AgentProperties;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通用 HTTP 文件下载器：供引擎包与任务资源（脚本/附件）复用。
 * <p>基于 java.net.http.HttpClient 流式落盘（支持大文件），按 Content-Length 每 10% 输出一条 INFO 进度日志；
 * 连接超时/请求超时取自 per.agent.download-*-seconds 配置。
 * 注意：请求超时作用于响应头到达（ofInputStream 模式），大文件正文流式读取不受其限制。</p>
 */
@Slf4j
@Component
public class HttpDownloader {

    /** 流式拷贝缓冲区大小（64KB） */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final AgentProperties properties;

    /** 下载专用 HTTP 客户端（跟随重定向，连接超时取配置） */
    private final HttpClient httpClient;

    /**
     * 构造下载器（HttpClient 需读取配置，故在构造器中初始化）。
     *
     * @param properties Agent 配置
     */
    public HttpDownloader(AgentProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getDownloadConnectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 流式下载远程文件到本地目标路径（父目录自动创建，进度每 10% 一条 INFO 日志）。
     *
     * @param url     完整下载地址
     * @param target  本地落盘路径
     * @return 落盘路径（即 target）
     * @throws IOException 网络/HTTP 状态/磁盘写任一失败（失败时清理半成品文件）
     */
    public Path download(String url, Path target) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getDownloadTimeoutSeconds())))
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断: " + url, e);
        } catch (IOException e) {
            // 连接被拒/超时/DNS 解析失败等网络异常：部分异常（如 ConnectException）message 为 null，
            // 统一包装为「异常类型 + URL + 原始消息」，平台可直接展示，避免只看到 "null" 无法定位是哪个地址不通
            throw new IOException("下载连接失败[" + e.getClass().getSimpleName() + "] " + url
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()), e);
        }
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new IOException("下载失败 HTTP " + response.statusCode() + ": " + url);
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        long done = 0L;
        int nextMilestonePercent = 10;
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (InputStream in = response.body();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                done += read;
                if (total > 0) {
                    int percent = (int) (done * 100 / total);
                    if (percent >= nextMilestonePercent) {
                        log.info("[Download] {} 下载进度 {}%（{}/{} 字节）", target.getFileName(),
                                Math.min(percent, 100), done, total);
                        nextMilestonePercent = (percent / 10 + 1) * 10;
                    }
                }
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // 半成品清理失败不影响原始异常抛出
            }
            throw e;
        }
        log.info("[Download] {} 下载完成，共 {} 字节（Content-Length={}）", target.getFileName(), done, total);
        return target;
    }

    /**
     * 计算文件 MD5（小写十六进制，流式读取支持大文件）。
     *
     * @param file 目标文件
     * @return 32 位小写十六进制 MD5 串
     * @throws IOException 文件读取失败
     */
    public String md5Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 MD5 算法", e);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(32);
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * 拼接基地址与相对路径（处理斜杠重复/缺失；相对路径已是绝对 http(s) 地址时原样返回）。
     *
     * @param baseUrl 基地址（如服务端地址、任务 baseUrl）
     * @param path    相对路径
     * @return 完整 URL
     */
    public static String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        String suffix = path == null ? "" : path.trim();
        if (suffix.startsWith("http://") || suffix.startsWith("https://")) {
            return suffix;
        }
        if (base.endsWith("/") && suffix.startsWith("/")) {
            return base + suffix.substring(1);
        }
        if (!base.isEmpty() && !base.endsWith("/") && !suffix.startsWith("/")) {
            return base + "/" + suffix;
        }
        return base + suffix;
    }

    /**
     * 静默关闭输入流（异常仅忽略）。
     *
     * @param in 待关闭流
     */
    private void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }
}
