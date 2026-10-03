package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.dto.AgentHeartbeatRequest;
import com.per.server.dto.AgentMetricsRequest;
import com.per.server.dto.AgentPollVO;
import com.per.server.dto.AgentRegisterRequest;
import com.per.server.dto.AgentRegisterVO;
import com.per.server.dto.HeartbeatVO;
import com.per.server.dto.TaskReceiptRequest;
import com.per.server.service.AgentInstallService;
import com.per.server.service.AgentService;
import com.per.server.service.FileStorageService;
import com.per.server.service.MetricService;
import com.per.server.service.TaskOrchestrator;
import com.per.server.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Agent 端接口：节点注册、心跳上报、命令轮询、任务回执与资源下载
 * 注意：/agent/** 不经过 JWT 认证拦截器，由本模块自行校验注册 token / nodeKey
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final TaskOrchestrator taskOrchestrator;
    private final TaskService taskService;
    private final FileStorageService storageService;
    private final MetricService metricService;
    private final AgentInstallService agentInstallService;

    /**
     * Agent 节点注册
     *
     * @param request 注册请求（token + 主机信息）
     * @return 节点标识与 JMeter 引擎信息
     */
    @PostMapping("/register")
    public R<AgentRegisterVO> register(@RequestBody @Valid AgentRegisterRequest request) {
        return R.ok(agentService.register(request));
    }

    /**
     * Agent 心跳上报
     *
     * @param request 心跳数据（nodeKey + 资源指标）
     * @return 服务端当前时间戳
     */
    @PostMapping("/heartbeat")
    public R<HeartbeatVO> heartbeat(@RequestBody @Valid AgentHeartbeatRequest request) {
        return R.ok(agentService.heartbeat(request));
    }

    /**
     * Agent 命令轮询
     *
     * @param nodeKey 节点唯一标识
     * @return 待执行命令与任务
     */
    @GetMapping("/poll")
    public R<AgentPollVO> poll(@RequestParam String nodeKey) {
        return R.ok(agentService.poll(nodeKey));
    }

    /**
     * Agent 任务回执（READY/RUNNING/FINISHED/STOPPED/FAILED 阶段状态推进）
     *
     * @param request 回执请求（taskId/nodeKey/phase/message）
     * @return 空数据成功响应
     */
    @PostMapping("/task/receipt")
    public R<Void> receipt(@RequestBody TaskReceiptRequest request) {
        taskOrchestrator.handleReceipt(request);
        return R.ok();
    }

    /**
     * Agent 指标上报：接收 10 秒窗口的采样器聚合快照与错误样本。
     * 快照按 (taskId,nodeKey,sampler,windowStart) 幂等入库（重复上报覆盖），
     * 错误样本每窗口最多 10 条、全任务累计保留前 200 条
     *
     * @param request 指标上报请求
     * @return 空数据成功响应
     */
    @PostMapping("/metrics")
    public R<Void> metrics(@RequestBody AgentMetricsRequest request) {
        metricService.report(request);
        return R.ok();
    }

    /**
     * Agent 一键安装脚本下载：动态生成 bash 安装脚本（root 检查、Java 17/21 检测/安装、
     * 下载分发包解压、sed 渲染 config/application.yml、systemd/nohup 常驻启动）。
     * server/token 参数缺省时取平台 per.server.base-url 与当前注册 token（无参访问也可用）
     *
     * @param server 平台服务端地址（可选）
     * @param token  节点注册 token（可选）
     * @return bash 安装脚本文本（Content-Type: application/x-shellscript, UTF-8）
     */
    @GetMapping(value = "/install.sh", produces = "application/x-shellscript;charset=UTF-8")
    public String installScript(@RequestParam(required = false) String server,
                                @RequestParam(required = false) String token) {
        return agentInstallService.buildInstallScript(server, token);
    }

    /**
     * Agent 分发包下载：返回 {per.storage.dir}/agent-dist/per-agent-dist.tar.gz
     * （由 scripts/build-agent-dist.sh 构建后放置）；不存在时返回 404 JSON 提示
     *
     * @return 分发包文件流，或 404 统一错误响应
     */
    @GetMapping({"/dist/download", "/dist/{tag}"})
    public ResponseEntity<?> downloadDist(@PathVariable(value = "tag", required = false) String tag) {
        // tag 为缓存穿透用（如 /agent/dist/v1790678127）：部分内网代理按路径缓存大文件且忽略 query，
        // 时间戳放在路径里可确保每次安装拿到平台最新分发包
        Path path = agentInstallService.agentDistPath();
        if (!Files.isReadable(path)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(R.error("Agent 分发包未上传，请先执行 scripts/build-agent-dist.sh 并放置到平台"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    /**
     * 通用文件下载：数据文件与引擎包共用 {md5} 存储，Agent 下载引擎 zip 与 SHARED 附件时使用
     *
     * @param md5 文件内容的 MD5（同时是存储路径）
     * @return 文件流
     */
    @GetMapping("/files/{md5}")
    public ResponseEntity<FileSystemResource> downloadFile(@PathVariable String md5) {
        Path path = storageService.filePath(md5);
        if (!Files.isReadable(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    /**
     * 任务压测脚本下载：FORM 脚本任务返回按模式重渲染的任务级快照，IMPORTED 返回脚本版本 JMX
     *
     * @param taskId 任务ID
     * @return JMX 文本流
     */
    @GetMapping("/task/{taskId}/script.jmx")
    public ResponseEntity<String> downloadScript(@PathVariable Long taskId) {
        String jmx = taskService.getTaskJmx(taskId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/xml;charset=UTF-8"))
                .body(jmx);
    }

    /**
     * 任务 SPLIT 分片文件下载：返回任务创建时预生成的 CSV 行分片
     *
     * @param taskId    任务ID
     * @param fileId    数据文件ID
     * @param shardIndex 分片序号（0 起，对应各节点）
     * @return 分片文件流
     */
    @GetMapping("/shards/{taskId}/{fileId}/{shardIndex}")
    public ResponseEntity<FileSystemResource> downloadShard(@PathVariable Long taskId,
                                                            @PathVariable Long fileId,
                                                            @PathVariable Integer shardIndex) {
        Path path = storageService.shardPath(taskId, fileId, shardIndex);
        if (!Files.isReadable(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }
}
