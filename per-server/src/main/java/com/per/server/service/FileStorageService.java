package com.per.server.service;

import com.per.server.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件存储服务：数据文件与引擎包共用 {per.storage.dir}/files/{md5} 存储模式，
 * 另提供 SPLIT 分片文件（{dir}/shard-{taskId}-{fileId}-{idx}）与
 * Agent 分发包（{dir}/agent-dist/per-agent-dist.tar.gz）的路径约定
 */
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /** Agent 分发包目录名（{per.storage.dir}/agent-dist） */
    public static final String AGENT_DIST_DIR = "agent-dist";

    /** Agent 分发包文件名（per-agent-dist.tar.gz，由 scripts/build-agent-dist.sh 产出） */
    public static final String AGENT_DIST_FILE = "per-agent-dist.tar.gz";

    /** 本地存储根目录（来自配置 per.storage.dir） */
    @Value("${per.storage.dir}")
    private String storageDir;

    /** 平台基础地址（来自配置 per.server.base-url） */
    @Value("${per.server.base-url}")
    private String baseUrl;

    /**
     * 计算上传文件 MD5 并落盘到 {storageDir}/files/{md5}；同 md5 文件已存在时直接复用不重复写入
     *
     * @param file 上传文件
     * @return 文件 MD5（同时作为存储文件名）
     */
    public String store(MultipartFile file) {
        String md5;
        try (InputStream in = file.getInputStream()) {
            md5 = DigestUtils.md5DigestAsHex(in);
        } catch (IOException e) {
            throw new BizException("读取上传文件失败：" + e.getMessage());
        }
        Path target = filePath(md5);
        if (Files.exists(target)) {
            return md5;
        }
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException("保存文件失败：" + e.getMessage());
        }
        return md5;
    }

    /**
     * 获取 md5 对应的本地文件路径（不校验存在性）
     *
     * @param md5 文件 MD5
     * @return 本地文件路径 {storageDir}/files/{md5}
     */
    public Path filePath(String md5) {
        return Paths.get(storageDir, "files", md5);
    }

    /**
     * 获取 SPLIT 分片文件路径（不校验存在性）
     *
     * @param taskId 任务ID
     * @param fileId 数据文件ID
     * @param shardIndex 分片序号（从 0 开始）
     * @return 分片文件路径 {storageDir}/shard-{taskId}-{fileId}-{shardIndex}
     */
    public Path shardPath(Long taskId, Long fileId, int shardIndex) {
        return Paths.get(storageDir, "shard-" + taskId + "-" + fileId + "-" + shardIndex);
    }

    /**
     * 获取 Agent 分发包路径（不校验存在性）：
     * {storageDir}/agent-dist/per-agent-dist.tar.gz，由 scripts/build-agent-dist.sh 产出后手工放置
     *
     * @return Agent 分发包文件路径
     */
    public Path agentDistPath() {
        return Paths.get(storageDir, AGENT_DIST_DIR, AGENT_DIST_FILE);
    }

    /**
     * 导入本地文件到 md5 存储体系：计算 MD5，目标不存在时复制到 {storageDir}/files/{md5}（已存在则复用）
     *
     * @param source 本地源文件
     * @return 文件 MD5（同时作为存储文件名）
     */
    public String importFile(Path source) {
        String md5;
        try (InputStream in = Files.newInputStream(source)) {
            md5 = DigestUtils.md5DigestAsHex(in);
        } catch (IOException e) {
            throw new BizException("读取本地文件失败：" + e.getMessage());
        }
        Path target = filePath(md5);
        if (Files.exists(target)) {
            return md5;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException("保存文件失败：" + e.getMessage());
        }
        return md5;
    }

    /**
     * 获取平台基础地址（拼装下载 URL 用）
     *
     * @return 配置的 per.server.base-url
     */
    public String getBaseUrl() {
        return baseUrl;
    }
}
