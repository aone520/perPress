package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.per.server.common.BizException;
import com.per.server.dto.AgentPollVO;
import com.per.server.dto.AgentTaskCommandVO;
import com.per.server.dto.AgentTaskDispatchVO;
import com.per.server.dto.AgentTaskFileVO;
import com.per.server.dto.TaskReceiptRequest;
import com.per.server.entity.DataFile;
import com.per.server.entity.Node;
import com.per.server.entity.ScriptVersion;
import com.per.server.entity.TaskNode;
import com.per.server.entity.TaskScriptSnapshot;
import com.per.server.entity.TestTask;
import com.per.server.mapper.DataFileMapper;
import com.per.server.mapper.NodeMapper;
import com.per.server.mapper.ScriptVersionMapper;
import com.per.server.mapper.TaskNodeMapper;
import com.per.server.mapper.TaskScriptSnapshotMapper;
import com.per.server.mapper.TestTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 任务编排服务：驱动任务状态机（创建→下发→节点准备→启动→结束）。
 * poll 时产出 PREPARE/START/STOP 命令；接收 Agent 各阶段回执；周期巡检收尾任务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskOrchestrator {

    /** 节点终态集合（任务收尾判定用） */
    private static final Set<String> NODE_TERMINAL_STATUS = Set.of(
            TaskNode.STATUS_STOPPED, TaskNode.STATUS_FINISHED, TaskNode.STATUS_FAILED, TaskNode.STATUS_EXCLUDED);

    /** STOPPING 状态强制收尾的超时阈值（秒） */
    private static final int STOP_TIMEOUT_SECONDS = 120;

    /** PREPARING 状态准备超时阈值（秒）：超时仍有节点无回执则整体失败（节点离线/Agent 异常防挂死） */
    private static final int PREPARE_TIMEOUT_SECONDS = 300;

    /** RUNNING 中节点心跳失联判定阈值（秒，心跳间隔 10s，连续 12 次未上报判定失联） */
    private static final int HEARTBEAT_LOST_SECONDS = 120;

    /** 全节点 READY 后预留的统一起跑等待时间 */
    private static final int START_DELAY_SECONDS = 5;

    private final TestTaskMapper taskMapper;
    private final TaskNodeMapper taskNodeMapper;
    private final DataFileMapper dataFileMapper;
    private final ScriptVersionMapper scriptVersionMapper;
    private final TaskScriptSnapshotMapper taskScriptSnapshotMapper;
    private final NodeMapper nodeMapper;
    private final FileStorageService storageService;
    private final ReportService reportService;
    private final ObjectMapper objectMapper;

    /**
     * 命令轮询决策（AgentService.poll 调用）：
     * 优先级 STOP &gt; PREPARE &gt; START——
     * 1) 任务 STOPPING 且本节点非终态 → STOP；
     * 2) 任务 PREPARING 且本节点 PENDING/DOWNLOADING → PREPARE（含完整下发载荷，置 DOWNLOADING，可重试幂等）；
     * 3) 任务 RUNNING 且本节点 READY → START（节点上报 RUNNING 后不再重复下发，幂等）
     *
     * @param nodeKey 节点唯一标识
     * @return 轮询结果（无命令时 command/task 均为 null）
     */
    public AgentPollVO pollCommand(String nodeKey) {
        List<TaskNode> nodes = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getNodeKey, nodeKey)
                .orderByDesc(TaskNode::getId));
        for (TaskNode node : nodes) {
            TestTask task = taskMapper.selectById(node.getTaskId());
            if (task == null) {
                continue;
            }
            if (TestTask.STATUS_STOPPING.equals(task.getStatus()) && !NODE_TERMINAL_STATUS.contains(node.getStatus())) {
                return new AgentPollVO("STOP", new AgentTaskCommandVO(task.getId()));
            }
            if (TestTask.STATUS_PREPARING.equals(task.getStatus())
                    && (TaskNode.STATUS_PENDING.equals(node.getStatus())
                    || TaskNode.STATUS_DOWNLOADING.equals(node.getStatus()))) {
                return new AgentPollVO("PREPARE", buildDispatch(task, node));
            }
            if (TestTask.STATUS_RUNNING.equals(task.getStatus())
                    && TaskNode.STATUS_READY.equals(node.getStatus())) {
                Long startAt = task.getStartTime() == null ? null
                        : task.getStartTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                return new AgentPollVO("START", new AgentTaskCommandVO(task.getId(), startAt));
            }
        }
        return new AgentPollVO(null, null);
    }

    /**
     * 构建 PREPARE 下发载荷：JMX 内容与 MD5、文件下载列表（SHARED/SPLIT）、节点级 JMeter 参数；
     * 构建成功后将本节点置 DOWNLOADING
     *
     * @param task 任务实体
     * @param node 该节点的任务节点实体
     * @return PREPARE 命令的 task 载荷
     */
    private AgentTaskDispatchVO buildDispatch(TestTask task, TaskNode node) {
        ScriptVersion scriptVersion = scriptVersionMapper.selectById(task.getScriptVersionId());
        if (scriptVersion == null) {
            throw new BizException(4012, "任务脚本版本不存在");
        }
        String baseUrl = storageService.getBaseUrl();
        int shardCount = task.getNodeKeys() == null ? 1 : task.getNodeKeys().split(",").length;
        List<AgentTaskFileVO> files = new ArrayList<>();
        if (StringUtils.hasText(task.getFileDispatchJson())) {
            JsonNode dispatchArray = readTree(task.getFileDispatchJson());
            if (dispatchArray != null) {
                for (JsonNode item : dispatchArray) {
                    long fileId = item.path("fileId").asLong();
                    String mode = item.path("mode").asText("SHARED");
                    DataFile file = dataFileMapper.selectById(fileId);
                    if (file == null) {
                        throw new BizException(4012, "任务分发文件不存在：fileId=" + fileId);
                    }
                    AgentTaskFileVO fileVO = new AgentTaskFileVO();
                    fileVO.setFileId(file.getId());
                    fileVO.setName(file.getName());
                    fileVO.setMd5(file.getMd5());
                    fileVO.setSize(file.getSize());
                    fileVO.setMode(mode);
                    if ("SPLIT".equals(mode)) {
                        fileVO.setShardIndex(node.getShardIndex() == null ? 0 : node.getShardIndex());
                        fileVO.setShardCount(shardCount);
                        fileVO.setDownloadUrl(baseUrl + "/agent/shards/" + task.getId() + "/" + fileId + "/" + fileVO.getShardIndex());
                    } else {
                        fileVO.setDownloadUrl(baseUrl + "/agent/files/" + file.getMd5());
                    }
                    files.add(fileVO);
                }
            }
        }
        AgentTaskDispatchVO dispatch = new AgentTaskDispatchVO();
        dispatch.setTaskId(task.getId());
        dispatch.setTaskNo(task.getTaskNo());
        dispatch.setBaseUrl(baseUrl);
        // FORM 脚本任务优先使用按模式重渲染的任务级 JMX 快照（任务创建时固化）
        String jmxContent = resolveJmx(task, scriptVersion);
        dispatch.setScriptMd5(DigestUtils.md5DigestAsHex(jmxContent.getBytes(StandardCharsets.UTF_8)));
        dispatch.setJmxContent(jmxContent);
        dispatch.setFiles(files);
        dispatch.setJmeterProps(readTree(node.getJmeterProps()));
        // 任务级 JMeter 堆内存设置（可空，Agent 侧空值回退本地默认）
        JsonNode config = readTree(task.getConfigJson());
        if (config != null && config.path("jmeterHeapMb").isInt()) {
            dispatch.setJmeterHeapMb(config.path("jmeterHeapMb").asInt());
        }
        taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                .set(TaskNode::getStatus, TaskNode.STATUS_DOWNLOADING)
                .eq(TaskNode::getId, node.getId()));
        return dispatch;
    }

    /**
     * 解析任务实际下发的 JMX 内容：存在任务级脚本快照（FORM 脚本按模式重渲染）则优先，
     * 否则回退脚本版本原始内容（IMPORTED 脚本）
     *
     * @param task          任务实体
     * @param scriptVersion 任务引用的脚本版本
     * @return JMX 文本
     */
    private String resolveJmx(TestTask task, ScriptVersion scriptVersion) {
        TaskScriptSnapshot snapshot = taskScriptSnapshotMapper.selectById(task.getId());
        return snapshot != null ? snapshot.getJmxContent() : scriptVersion.getJmxContent();
    }

    /**
     * 处理 Agent 任务回执：
     * READY —— 本节点置 READY；若该任务全部节点 READY → 任务置 RUNNING、生成统一 startAt（下次 poll 返回 START）；
     * RUNNING —— 本节点置 RUNNING 并记录 start_time；FINISHED/STOPPED/FAILED —— 本节点置终态并记录 end_time
     *
     * @param request 回执请求（taskId/nodeKey/phase/message）
     */
    public void handleReceipt(TaskReceiptRequest request) {
        Long taskId = request.getTaskId();
        TaskNode node = taskNodeMapper.selectOne(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getTaskId, request.getTaskId())
                .eq(TaskNode::getNodeKey, request.getNodeKey()));
        if (node == null) {
            throw new BizException(4012, "任务节点不存在");
        }
        switch (request.getPhase()) {
            case "READY" -> {
                taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                        .set(TaskNode::getStatus, TaskNode.STATUS_READY)
                        .eq(TaskNode::getId, node.getId()));
                tryStartTask(request.getTaskId());
            }
            case "RUNNING" -> taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                    .set(TaskNode::getStatus, TaskNode.STATUS_RUNNING)
                    .set(TaskNode::getStartTime, LocalDateTime.now())
                    .eq(TaskNode::getId, node.getId()));
            case "FINISHED" -> taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                    .set(TaskNode::getStatus, TaskNode.STATUS_FINISHED)
                    .set(TaskNode::getEndTime, LocalDateTime.now())
                    .eq(TaskNode::getId, node.getId()));
            case "STOPPED" -> taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                    .set(TaskNode::getStatus, TaskNode.STATUS_STOPPED)
                    .set(TaskNode::getEndTime, LocalDateTime.now())
                    .eq(TaskNode::getId, node.getId()));
            case "FAILED" -> {
                taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                        .set(TaskNode::getStatus, TaskNode.STATUS_FAILED)
                        .set(TaskNode::getErrorMsg, request.getMessage())
                        .set(TaskNode::getEndTime, LocalDateTime.now())
                        .eq(TaskNode::getId, node.getId()));
                // 任一节点准备失败且已无节点在准备中（其余均 READY/FAILED）时任务整体失败：
                // 否则 READY 节点将永远等不到全员就绪的 START，任务挂死在 PREPARING
                failTaskIfNoPreparing(taskId, request.getMessage());
            }
            default -> throw new BizException("回执phase不合法，仅支持 READY/RUNNING/FINISHED/STOPPED/FAILED");
        }
    }

    /**
     * 任务处于 PREPARING 且无 PENDING/DOWNLOADING 节点、存在 FAILED 节点时：
     * 将任务置 FAILED 并收尾（READY 节点置 EXCLUDED 排除，避免后续再派 START）。
     *
     * @param taskId   任务ID
     * @param errorMsg 失败原因（取首个 FAILED 节点的错误信息）
     */
    private void failTaskIfNoPreparing(Long taskId, String errorMsg) {
        TestTask task = taskMapper.selectById(taskId);
        if (task == null || !TestTask.STATUS_PREPARING.equals(task.getStatus())) {
            return;
        }
        List<TaskNode> nodes = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getTaskId, taskId));
        boolean hasFailed = nodes.stream().anyMatch(n -> TaskNode.STATUS_FAILED.equals(n.getStatus()));
        boolean preparing = nodes.stream().anyMatch(n ->
                TaskNode.STATUS_PENDING.equals(n.getStatus())
                        || TaskNode.STATUS_DOWNLOADING.equals(n.getStatus()));
        if (!hasFailed || preparing) {
            return;
        }
        // READY 节点排除（记录结束时间），任务整体失败收尾
        for (TaskNode n : nodes) {
            if (TaskNode.STATUS_READY.equals(n.getStatus())) {
                taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                        .set(TaskNode::getStatus, TaskNode.STATUS_EXCLUDED)
                        .set(TaskNode::getEndTime, LocalDateTime.now())
                        .eq(TaskNode::getId, n.getId()));
            }
        }
        taskMapper.update(null, new LambdaUpdateWrapper<TestTask>()
                .set(TestTask::getStatus, TestTask.STATUS_FAILED)
                .set(TestTask::getStatusTime, LocalDateTime.now())
                .set(TestTask::getEndTime, LocalDateTime.now())
                .eq(TestTask::getId, taskId));
        log.warn("任务 {} 有节点准备失败且无节点准备中，任务整体置 FAILED：{}", task.getTaskNo(), errorMsg);
    }

    /**
     * 若任务处于 PREPARING 且全部节点 READY，则将任务置 RUNNING 并记录 start_time
     * （节点保持 READY，待 Agent 下次 poll 领取 START 命令后上报 RUNNING）
     *
     * @param taskId 任务ID
     */
    private void tryStartTask(Long taskId) {
        TestTask task = taskMapper.selectById(taskId);
        if (task == null || !TestTask.STATUS_PREPARING.equals(task.getStatus())) {
            return;
        }
        List<TaskNode> nodes = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getTaskId, taskId));
        boolean allReady = nodes.stream().allMatch(n -> TaskNode.STATUS_READY.equals(n.getStatus()));
        if (allReady) {
            LocalDateTime now = LocalDateTime.now();
            taskMapper.update(null, new LambdaUpdateWrapper<TestTask>()
                    .set(TestTask::getStatus, TestTask.STATUS_RUNNING)
                    .set(TestTask::getStatusTime, now)
                    .set(TestTask::getStartTime, now.plusSeconds(START_DELAY_SECONDS))
                    .eq(TestTask::getId, taskId));
            log.info("任务 {} 全部节点就绪，进入 RUNNING，{} 秒后统一起跑", task.getTaskNo(), START_DELAY_SECONDS);
        }
    }

    /**
     * 记录 STOP 请求时间（TaskService.stop 调用，用于 STOPPING 超时强制收尾）
     *
     * @param taskId 任务ID
     */
    public void markStopRequested(Long taskId) {
        taskMapper.update(null, new LambdaUpdateWrapper<TestTask>()
                .set(TestTask::getStatusTime, LocalDateTime.now())
                .eq(TestTask::getId, taskId));
    }

    /**
     * 每 5 秒巡检：
     * 1) PREPARING 任务：超 300s 仍有节点 PENDING/DOWNLOADING（无回执，节点离线/Agent 异常）→
     *    这些节点置 FAILED 后复用 failTaskIfNoPreparing 整体收尾，防止挂死；
     * 2) RUNNING 任务：非终态节点心跳超 120s 未更新（Agent 失联）→ 置 FAILED；
     *    全部节点终态 → 存在成功（FINISHED/STOPPED）则任务 FINISHED，全部 FAILED 则任务 FAILED；
     * 3) STOPPING 任务：全部节点终态 → FINISHED；超过 120s 仍有非终态节点 → 非终态节点强制 STOPPED 后任务 FINISHED；
     * 收尾时记录 end_time、清理计时记录；置 FINISHED 成功后聚合固化测试报告
     */
    @Scheduled(fixedDelay = 5_000)
    public void inspect() {
        List<TestTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<TestTask>()
                .in(TestTask::getStatus, TestTask.STATUS_PREPARING,
                        TestTask.STATUS_RUNNING, TestTask.STATUS_STOPPING));
        for (TestTask task : tasks) {
            List<TaskNode> nodes = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                    .eq(TaskNode::getTaskId, task.getId()));
            if (TestTask.STATUS_PREPARING.equals(task.getStatus())) {
                inspectPreparing(task, nodes);
                continue;
            }
            if (TestTask.STATUS_RUNNING.equals(task.getStatus())) {
                markHeartbeatLostNodes(task, nodes);
                nodes = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                        .eq(TaskNode::getTaskId, task.getId()));
            }
            boolean allTerminal = nodes.stream().allMatch(n -> NODE_TERMINAL_STATUS.contains(n.getStatus()));
            if (TestTask.STATUS_STOPPING.equals(task.getStatus())) {
                LocalDateTime requestedAt = task.getStatusTime();
                if (allTerminal) {
                    finishTask(task, TestTask.STATUS_CANCELLED);
                } else if (requestedAt != null
                        && requestedAt.plusSeconds(STOP_TIMEOUT_SECONDS).isBefore(LocalDateTime.now())) {
                    taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                            .set(TaskNode::getStatus, TaskNode.STATUS_STOPPED)
                            .set(TaskNode::getEndTime, LocalDateTime.now())
                            .eq(TaskNode::getTaskId, task.getId())
                            .notIn(TaskNode::getStatus, NODE_TERMINAL_STATUS));
                    finishTask(task, TestTask.STATUS_CANCELLED);
                    log.warn("任务 {} STOPPING 超 120s，强制收尾为 CANCELLED", task.getTaskNo());
                }
            } else if (allTerminal) {
                finishTask(task, resolveFinalStatus(nodes));
            }
        }
    }

    static String resolveFinalStatus(List<TaskNode> nodes) {
        long successCount = nodes.stream().filter(n -> TaskNode.STATUS_FINISHED.equals(n.getStatus())).count();
        long failedCount = nodes.stream().filter(n -> TaskNode.STATUS_FAILED.equals(n.getStatus())).count();
        if (!nodes.isEmpty() && successCount == nodes.size()) {
            return TestTask.STATUS_FINISHED;
        }
        if (!nodes.isEmpty() && failedCount == nodes.size()) {
            return TestTask.STATUS_FAILED;
        }
        return TestTask.STATUS_PARTIAL_FAILED;
    }

    /**
     * PREPARING 任务的超时兜底：自首次观察到 PREPARING 起超过阈值，仍存在 PENDING/DOWNLOADING 节点时，
     * 将这些节点置 FAILED（准备超时无回执），并复用 failTaskIfNoPreparing 收尾任务（READY 节点置 EXCLUDED）。
     *
     * @param task  PREPARING 状态的任务
     * @param nodes 该任务的全部节点
     */
    private void inspectPreparing(TestTask task, List<TaskNode> nodes) {
        LocalDateTime since = task.getStatusTime() == null ? task.getCreateTime() : task.getStatusTime();
        if (since == null) {
            since = LocalDateTime.now();
        }
        if (!since.plusSeconds(PREPARE_TIMEOUT_SECONDS).isBefore(LocalDateTime.now())) {
            return;
        }
        boolean waiting = nodes.stream().anyMatch(n ->
                TaskNode.STATUS_PENDING.equals(n.getStatus())
                        || TaskNode.STATUS_DOWNLOADING.equals(n.getStatus()));
        if (!waiting) {
            return;
        }
        String errorMsg = "准备超时（超 " + PREPARE_TIMEOUT_SECONDS + "s 无回执，节点离线或 Agent 异常）";
        taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                .set(TaskNode::getStatus, TaskNode.STATUS_FAILED)
                .set(TaskNode::getErrorMsg, errorMsg)
                .set(TaskNode::getEndTime, LocalDateTime.now())
                .eq(TaskNode::getTaskId, task.getId())
                .in(TaskNode::getStatus, TaskNode.STATUS_PENDING, TaskNode.STATUS_DOWNLOADING));
        log.warn("任务 {} {}", task.getTaskNo(), errorMsg);
        failTaskIfNoPreparing(task.getId(), errorMsg);
    }

    /**
     * RUNNING 任务的失联兜底：非终态节点对应 Node 心跳超阈值未更新（或节点记录缺失）时，
     * 将任务节点置 FAILED（节点失联）；后续由 allTerminal 判定自动收尾任务。
     * Agent 侧心跳失联会自停本地 JMeter，此处仅收敛服务端状态机。
     *
     * @param task  RUNNING 状态的任务
     * @param nodes 该任务的全部节点
     */
    private void markHeartbeatLostNodes(TestTask task, List<TaskNode> nodes) {
        List<String> activeKeys = nodes.stream()
                .filter(n -> !NODE_TERMINAL_STATUS.contains(n.getStatus()))
                .map(TaskNode::getNodeKey)
                .toList();
        if (activeKeys.isEmpty()) {
            return;
        }
        List<Node> nodeInfos = nodeMapper.selectList(new LambdaQueryWrapper<Node>()
                .in(Node::getNodeKey, activeKeys));
        java.util.Map<String, Node> byKey = new java.util.HashMap<>();
        for (Node info : nodeInfos) {
            byKey.put(info.getNodeKey(), info);
        }
        LocalDateTime now = LocalDateTime.now();
        for (TaskNode taskNode : nodes) {
            if (NODE_TERMINAL_STATUS.contains(taskNode.getStatus())) {
                continue;
            }
            Node info = byKey.get(taskNode.getNodeKey());
            boolean lost = info == null || info.getLastHeartbeatTime() == null
                    || info.getLastHeartbeatTime().plusSeconds(HEARTBEAT_LOST_SECONDS).isBefore(now);
            if (!lost) {
                continue;
            }
            String errorMsg = "节点失联（心跳超 " + HEARTBEAT_LOST_SECONDS + "s 未上报）";
            taskNodeMapper.update(null, new LambdaUpdateWrapper<TaskNode>()
                    .set(TaskNode::getStatus, TaskNode.STATUS_FAILED)
                    .set(TaskNode::getErrorMsg, errorMsg)
                    .set(TaskNode::getEndTime, now)
                    .eq(TaskNode::getId, taskNode.getId()));
            log.warn("任务 {} 节点 {} {}", task.getTaskNo(), taskNode.getNodeKey(), errorMsg);
        }
    }

    /**
     * 收尾任务：置目标状态并记录 end_time；
     * 可出报告的终态在事务内聚合固化测试报告（聚合失败仅告警，不影响状态机）
     *
     * @param task        任务实体
     * @param finalStatus 最终状态（FINISHED/FAILED/PARTIAL_FAILED/CANCELLED）
     */
    private void finishTask(TestTask task, String finalStatus) {
        taskMapper.update(null, new LambdaUpdateWrapper<TestTask>()
                .set(TestTask::getStatus, finalStatus)
                .set(TestTask::getStatusTime, LocalDateTime.now())
                .set(TestTask::getEndTime, LocalDateTime.now())
                .eq(TestTask::getId, task.getId()));
        log.info("任务 {} 收尾为 {}", task.getTaskNo(), finalStatus);
        if (TestTask.STATUS_FINISHED.equals(finalStatus)
                || TestTask.STATUS_PARTIAL_FAILED.equals(finalStatus)
                || TestTask.STATUS_CANCELLED.equals(finalStatus)) {
            try {
                reportService.aggregate(task.getId());
            } catch (Exception e) {
                log.warn("任务 {} 报告聚合失败：{}", task.getTaskNo(), e.getMessage(), e);
            }
        }
    }

    /**
     * JSON 字符串解析为树节点（空串/失败返回 null）
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
            return null;
        }
    }
}
