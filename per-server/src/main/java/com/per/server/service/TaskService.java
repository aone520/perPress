package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.per.server.common.BizException;
import com.per.server.common.JmxThreadGroupParser;
import com.per.server.common.TpsCapacity;
import com.per.server.common.UserContext;
import com.per.server.common.WeightSplitter;
import com.per.server.dto.PageVO;
import com.per.server.dto.ScriptFormRequest;
import com.per.server.dto.TaskCreateRequest;
import com.per.server.dto.TaskDetailVO;
import com.per.server.dto.TaskNodeVO;
import com.per.server.dto.TaskVO;
import com.per.server.entity.DataFile;
import com.per.server.entity.ErrorSample;
import com.per.server.entity.MetricSnapshot;
import com.per.server.entity.Node;
import com.per.server.entity.Script;
import com.per.server.entity.ScriptVersion;
import com.per.server.entity.TaskNode;
import com.per.server.entity.TaskScriptSnapshot;
import com.per.server.entity.TestReport;
import com.per.server.entity.TestTask;
import com.per.server.mapper.DataFileMapper;
import com.per.server.mapper.ErrorSampleMapper;
import com.per.server.mapper.MetricSnapshotMapper;
import com.per.server.mapper.NodeMapper;
import com.per.server.mapper.ScriptVersionMapper;
import com.per.server.mapper.TaskNodeMapper;
import com.per.server.mapper.TaskScriptSnapshotMapper;
import com.per.server.mapper.TestReportMapper;
import com.per.server.mapper.TestTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 压测任务服务：任务创建（三模式校验与 props 生成、FORM 快照、SPLIT 文件预分片）、
 * 分页查询、详情、启动与停止
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    /** 任务编号日期格式 */
    private static final DateTimeFormatter TASK_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 定时启动时间入参格式（东八区 yyyy-MM-dd HH:mm:ss） */
    private static final DateTimeFormatter SCHEDULED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 支持的压测模式集合 */
    private static final Set<String> MODES = Set.of("CONCURRENT", "FIXED_TPS", "STEPPED");

    /** 启动前压力机资源保护阈值 */
    private static final double START_MAX_CPU_PERCENT = 85.0;
    private static final double START_MAX_MEMORY_PERCENT = 90.0;

    private final TestTaskMapper taskMapper;
    private final TaskNodeMapper taskNodeMapper;
    private final NodeMapper nodeMapper;
    private final DataFileMapper dataFileMapper;
    private final ScriptVersionMapper scriptVersionMapper;
    private final TaskScriptSnapshotMapper taskScriptSnapshotMapper;
    private final TestReportMapper reportMapper;
    private final MetricSnapshotMapper metricSnapshotMapper;
    private final ErrorSampleMapper errorSampleMapper;
    private final ScriptService scriptService;
    private final FileStorageService storageService;
    private final TaskOrchestrator taskOrchestrator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * 创建任务时应用定时执行配置：提供了未来的定时时间则 SCHEDULED + 记录计划时间，
     * 否则 MANUAL；时间为过去时间或格式非法直接报错（格式 yyyy-MM-dd HH:mm:ss）
     *
     * @param task               任务实体（尚未插入）
     * @param scheduledStartTime 定时启动时间字符串（可空）
     */
    private void applyScheduleOnCreate(TestTask task, String scheduledStartTime) {
        if (!StringUtils.hasText(scheduledStartTime)) {
            task.setTriggerType(TestTask.TRIGGER_MANUAL);
            return;
        }
        LocalDateTime parsed;
        try {
            parsed = parseScheduledStartTime(scheduledStartTime);
        } catch (DateTimeParseException e) {
            throw new BizException("定时启动时间格式非法，应为 yyyy-MM-dd HH:mm:ss");
        }
        if (!parsed.isAfter(LocalDateTime.now())) {
            throw new BizException("定时启动时间必须晚于当前时间");
        }
        task.setTriggerType(TestTask.TRIGGER_SCHEDULED);
        task.setScheduledStartTime(parsed);
    }

    static LocalDateTime parseScheduledStartTime(String text) {
        return LocalDateTime.parse(text.trim(), SCHEDULED_TIME);
    }

    /**
     * 创建压测任务：公共校验后生成 task_no，写 test_task(CREATED)，
     * 再生成 FORM 任务快照、task_node(PENDING) 与 SPLIT 分片
     *
     * @param request 任务创建请求
     * @return 任务详情（含节点明细）
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskDetailVO create(TaskCreateRequest request) {
        PreparedTask prepared = validateRequest(request);
        TestTask task = new TestTask();
        task.setTaskNo("T" + TASK_NO_DATE.format(LocalDateTime.now()) + random4());
        task.setName(request.getName());
        task.setScriptId(request.getScriptId());
        task.setScriptVersionId(prepared.scriptVersion().getId());
        task.setMode(prepared.mode());
        task.setConfigJson(writeJson(request.getConfig()));
        task.setNodeKeys(String.join(",", prepared.nodeKeys()));
        task.setFileDispatchJson(prepared.fileDispatch() == null ? null : writeJson(prepared.fileDispatch()));
        task.setStatus(TestTask.STATUS_CREATED);
        task.setStatusTime(LocalDateTime.now());
        applyScheduleOnCreate(task, request.getScheduledStartTime());
        task.setCreateBy(currentUsername());
        taskMapper.insert(task);
        buildNodesAndArtifacts(task.getId(), prepared.script(), prepared.mode(), request, prepared.nodeKeys(),
                prepared.fileDispatch(), prepared.scriptVersion());
        auditService.record("CREATE_TASK", "创建压测任务 " + task.getTaskNo() + "（" + task.getName() + "）");
        return detail(task.getId());
    }

    /**
     * 复制任务：将源任务的脚本/版本/模式/配置/节点/文件分发转为创建请求，
     * 复用 create() 全链路（快照、节点 props、SPLIT 分片全新生成，校验全量复跑）。
     * 名称自动追加「-副本」后缀（重名递增）；执行方式重置为立即执行
     * （定时任务的计划时间复制后通常已过期，不继承），记录审计日志
     *
     * @param id 源任务ID
     * @return 复制出的新任务详情（CREATED 状态）
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskDetailVO copy(Long id) {
        TestTask source = requireTask(id);
        ScriptVersion sourceVersion = scriptVersionMapper.selectById(source.getScriptVersionId());
        if (sourceVersion == null) {
            throw new BizException("源任务引用的脚本版本不存在，无法复制");
        }
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName(nextTaskCopyName(source.getName()));
        request.setScriptId(source.getScriptId());
        request.setVersion(sourceVersion.getVersion());
        request.setMode(source.getMode());
        request.setNodeKeys(List.of(source.getNodeKeys().split(",")));
        request.setConfig(objectMapper.convertValue(readTree(source.getConfigJson()), TaskCreateRequest.Config.class));
        JsonNode dispatch = readTree(source.getFileDispatchJson());
        if (dispatch != null && dispatch.isArray() && !dispatch.isEmpty()) {
            request.setFileDispatch(objectMapper.convertValue(dispatch,
                    new TypeReference<List<TaskCreateRequest.FileDispatch>>() { }));
        }
        TaskDetailVO created = create(request);
        auditService.record("COPY_TASK", "复制任务 " + source.getTaskNo() + " → " + created.getTaskNo());
        return created;
    }

    /**
     * 生成不与现有任务重名的副本名称：原名-副本、原名-副本2、原名-副本3 ……
     * 追加后缀前先截断源名，保证不超过任务名列宽（VARCHAR(128)）
     *
     * @param sourceName 源任务名称
     * @return 可用的副本名称
     */
    private String nextTaskCopyName(String sourceName) {
        String base = sourceName.substring(0, Math.min(sourceName.length(), 120)) + "-副本";
        if (taskMapper.selectCount(new LambdaQueryWrapper<TestTask>().eq(TestTask::getName, base)) == 0) {
            return base;
        }
        int seq = 2;
        while (taskMapper.selectCount(new LambdaQueryWrapper<TestTask>().eq(TestTask::getName, base + seq)) > 0) {
            seq += 1;
        }
        return base + seq;
    }

    /**
     * 编辑压测任务（仅 CREATED 状态可编辑）：脚本与脚本版本不允许变更；
     * 更新名称/压测模式/模式参数/参测节点/文件分发策略，删除旧 task_node 与 FORM 快照后
     * 重建（SPLIT 分片按新节点数重新生成），记录审计日志
     *
     * @param id      任务ID
     * @param request 任务编辑请求（结构同创建请求）
     * @return 编辑后的任务详情（含重建的节点明细）
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskDetailVO update(Long id, TaskCreateRequest request) {
        TestTask task = requireTask(id);
        if (!TestTask.STATUS_CREATED.equals(task.getStatus())) {
            throw new BizException("仅未启动任务可编辑");
        }
        // 脚本与脚本版本不允许变更：与任务当前记录比对
        ScriptVersion originVersion = scriptVersionMapper.selectById(task.getScriptVersionId());
        if (!Objects.equals(request.getScriptId(), task.getScriptId())
                || originVersion == null || !Objects.equals(request.getVersion(), originVersion.getVersion())) {
            throw new BizException("任务不支持变更脚本或脚本版本");
        }
        PreparedTask prepared = validateRequest(request);
        deleteNodesSnapshotAndShards(task);
        TestTask update = new TestTask();
        update.setId(id);
        update.setName(request.getName());
        update.setMode(prepared.mode());
        update.setConfigJson(writeJson(request.getConfig()));
        update.setNodeKeys(String.join(",", prepared.nodeKeys()));
        update.setFileDispatchJson(prepared.fileDispatch() == null ? null : writeJson(prepared.fileDispatch()));
        taskMapper.updateById(update);
        buildNodesAndArtifacts(id, prepared.script(), prepared.mode(), request, prepared.nodeKeys(),
                prepared.fileDispatch(), prepared.scriptVersion());
        auditService.record("UPDATE_TASK", "编辑压测任务 " + task.getTaskNo() + "（" + request.getName() + "）");
        return detail(id);
    }

    /**
     * 任务请求公共校验（创建与编辑共用）：压测模式合法性、脚本版本存在、导入脚本仅限并发模式、
     * 按模式校验 config 完整性、参测节点均已注册、文件分发策略合法且文件存在
     *
     * @param request 任务创建/编辑请求
     * @return 校验通过后的上下文（模式/脚本版本/脚本/去重节点列表）
     */
    private PreparedTask validateRequest(TaskCreateRequest request) {
        String mode = StringUtils.hasText(request.getMode()) ? request.getMode() : "CONCURRENT";
        if (!MODES.contains(mode)) {
            throw new BizException("压测模式仅支持 CONCURRENT/FIXED_TPS/STEPPED");
        }
        ScriptVersion scriptVersion = scriptService.requireVersion(request.getScriptId(), request.getVersion());
        Script script = scriptService.requireScript(request.getScriptId());
        if (Script.TYPE_IMPORTED.equals(script.getType()) && !"CONCURRENT".equals(mode)) {
            throw new BizException("导入脚本仅支持并发模式，如需 TPS/阶梯请使用表单脚本");
        }
        validateConfig(mode, request.getConfig());
        List<String> nodeKeys = request.getNodeKeys().stream().distinct().toList();
        Long nodeCount = nodeMapper.selectCount(new LambdaQueryWrapper<Node>()
                .in(Node::getNodeKey, nodeKeys));
        if (nodeCount == null || nodeCount != nodeKeys.size()) {
            throw new BizException("存在未注册的参测节点");
        }
        if (request.getFileDispatch() != null) {
            for (TaskCreateRequest.FileDispatch dispatch : request.getFileDispatch()) {
                if (!"SHARED".equals(dispatch.getMode()) && !"SPLIT".equals(dispatch.getMode())) {
                    throw new BizException("文件分发模式仅支持 SHARED/SPLIT");
                }
                DataFile file = dataFileMapper.selectById(dispatch.getFileId());
                if (file == null) {
                    throw new BizException("分发文件不存在：fileId=" + dispatch.getFileId());
                }
            }
        }
        // 自动补全文件分发：用户未指定分发策略时，默认把脚本关联的全部参数文件以 SHARED 模式分发，
        // 防止漏选导致 JMeter 运行时找不到 CSV（线程空转、零请求、任务"正常结束"但报告为空）
        List<TaskCreateRequest.FileDispatch> fileDispatch = request.getFileDispatch();
        if ((fileDispatch == null || fileDispatch.isEmpty()) && StringUtils.hasText(scriptVersion.getFileIds())) {
            fileDispatch = new ArrayList<>();
            for (String idText : scriptVersion.getFileIds().split(",")) {
                Long fid = Long.valueOf(idText.trim());
                TaskCreateRequest.FileDispatch d = new TaskCreateRequest.FileDispatch();
                d.setFileId(fid);
                d.setMode("SHARED");
                fileDispatch.add(d);
            }
        }
        // 参数文件分发完整性校验：脚本版本关联的文件必须全部选择分发，
        // 否则 JMeter 运行时找不到 CSV（线程空转、零请求、任务"正常结束"但报告为空）
        if (StringUtils.hasText(scriptVersion.getFileIds())) {
            Set<Long> dispatched = fileDispatch == null ? Set.of()
                    : fileDispatch.stream()
                    .map(TaskCreateRequest.FileDispatch::getFileId).collect(Collectors.toSet());
            for (String idText : scriptVersion.getFileIds().split(",")) {
                Long id = Long.valueOf(idText.trim());
                if (!dispatched.contains(id)) {
                    DataFile file = dataFileMapper.selectById(id);
                    String name = file == null ? String.valueOf(id) : file.getName();
                    throw new BizException("脚本引用了参数文件「" + name + "」，请在文件分发中选择该文件（公用或拆分）");
                }
            }
        }
        return new PreparedTask(mode, scriptVersion, script, nodeKeys, fileDispatch);
    }

    /**
     * 生成任务的节点明细与附属产物（创建与编辑重建共用）：
     * FORM 脚本按模式重渲染 JMX 写任务级快照；按模式生成各节点 jmeter_props 与 task_node 记录
     * （threads/TPS 均分：整除余数补前几台）；SPLIT 文件按节点数预生成分片并记录 shard_index
     *
     * @param taskId      任务ID
     * @param script      脚本实体
     * @param mode        压测模式
     * @param request     任务创建/编辑请求
     * @param nodeKeys    去重后的参测节点列表
     * @param fileDispatch 校验/补全后的文件分发列表
     */
    private void buildNodesAndArtifacts(Long taskId, Script script, String mode,
                                        TaskCreateRequest request, List<String> nodeKeys,
                                        List<TaskCreateRequest.FileDispatch> fileDispatch,
                                        ScriptVersion scriptVersion) {
        // FORM 脚本按模式重渲染 JMX 固化为任务级快照（下发与脚本下载优先使用）
        if (Script.TYPE_FORM.equals(script.getType())) {
            TaskScriptSnapshot snapshot = new TaskScriptSnapshot();
            snapshot.setTaskId(taskId);
            snapshot.setJmxContent(scriptService.renderModeJmx(request.getScriptId(), mode, request.getConfig()));
            taskScriptSnapshotMapper.insert(snapshot);
        }

        // 解析执行单元（FORM=编排单元，IMPORTED=JMX 线程组）并校验流量占比（多单元时拆分 threads/TPS）
        List<FormScriptJmxBuilder.ExecUnit> units = resolveExecUnits(script, scriptVersion);
        int[] weights = validateWeights(request.getConfig(), units, nodeKeys.size(), mode);
        if (Script.TYPE_IMPORTED.equals(script.getType()) && units != null && units.size() > 1) {
            // 导入脚本多线程组：属性化改写 num_threads 为 ${__P(tgN.threads,原值)} 并固化任务级快照，
            // 使占比拆分后的 -JtgN.threads 参数生效（改写与节点无关，各节点 MD5 一致）
            TaskScriptSnapshot snapshot = new TaskScriptSnapshot();
            snapshot.setTaskId(taskId);
            snapshot.setJmxContent(JmxThreadGroupParser.attributeizeThreads(scriptVersion.getJmxContent()));
            taskScriptSnapshotMapper.insert(snapshot);
        }
        List<Map<String, Object>> propsPerNode =
                buildNodeProps(mode, request.getConfig(), nodeKeys.size(), units, weights);
        Map<Long, Integer> shardCountByFile = new HashMap<>();
        for (TaskCreateRequest.FileDispatch dispatch : fileDispatch == null ? List.<TaskCreateRequest.FileDispatch>of() : fileDispatch) {
            if ("SPLIT".equals(dispatch.getMode())) {
                shardCountByFile.put(dispatch.getFileId(), nodeKeys.size());
            }
        }
        for (int i = 0; i < nodeKeys.size(); i++) {
            TaskNode node = new TaskNode();
            node.setTaskId(taskId);
            node.setNodeKey(nodeKeys.get(i));
            node.setStatus(TaskNode.STATUS_PENDING);
            node.setJmeterProps(writeJson(propsPerNode.get(i)));
            node.setShardIndex(i);
            taskNodeMapper.insert(node);
        }
        shardCountByFile.forEach((fileId, shardCount) ->
                splitFile(dataFileMapper.selectById(fileId), taskId, shardCount));
    }

    /**
     * 删除任务的旧节点明细、FORM 快照与 SPLIT 分片文件（编辑重建前调用）；
     * 分片文件删除失败仅忽略（残留文件不再被引用，不影响功能）
     *
     * @param task 编辑前的任务实体
     */
    private void deleteNodesSnapshotAndShards(TestTask task) {
        deleteTaskShardFiles(task);
        taskNodeMapper.delete(new LambdaQueryWrapper<TaskNode>().eq(TaskNode::getTaskId, task.getId()));
        taskScriptSnapshotMapper.deleteById(task.getId());
    }

    private void deleteTaskShardFiles(TestTask task) {
        Long oldNodeCount = taskNodeMapper.selectCount(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getTaskId, task.getId()));
        JsonNode dispatch = readTree(task.getFileDispatchJson());
        if (oldNodeCount != null && oldNodeCount > 0 && dispatch != null && dispatch.isArray()) {
            for (JsonNode item : dispatch) {
                JsonNode fileIdNode = item.get("fileId");
                if (fileIdNode == null || !fileIdNode.canConvertToLong()) {
                    continue;
                }
                for (int idx = 0; idx < oldNodeCount; idx++) {
                    try {
                        Files.deleteIfExists(storageService.shardPath(task.getId(), fileIdNode.asLong(), idx));
                    } catch (IOException ignored) {
                        // 分片文件删除失败不影响任务编辑（重建后旧分片不再被引用）
                    }
                }
            }
        }
    }

    /**
     * 任务请求校验结果上下文（创建与编辑共用）
     *
     * @param mode         压测模式
     * @param scriptVersion 脚本版本实体
     * @param script       脚本实体
     * @param nodeKeys     去重后的参测节点列表
     */
    private record PreparedTask(String mode, ScriptVersion scriptVersion, Script script,
                                List<String> nodeKeys, List<TaskCreateRequest.FileDispatch> fileDispatch) {
    }

    /**
     * 按压测模式校验 config 字段完整性（中文错误提示）：
     * CONCURRENT 需 threads>0（rampup/duration 缺省 60/300）；FIXED_TPS 需 tps>0 与 durationSeconds>0；
     * STEPPED 需 unit∈{THREADS,TPS}、start>=1、step>=1、peak>=start、stepSeconds>0、peakSeconds>0
     *
     * @param mode   压测模式
     * @param config 模式参数
     */
    private void validateConfig(String mode, TaskCreateRequest.Config config) {
        if (config == null) {
            throw new BizException("config不能为空");
        }
        if (config.getJmeterHeapMb() != null && config.getJmeterHeapMb() < 256) {
            throw new BizException("jmeterHeapMb 至少 256（MB），过小会导致 JMeter 无法正常运行");
        }
        switch (mode) {
            case "CONCURRENT" -> {
                if (config.getThreads() == null) {
                    throw new BizException("CONCURRENT模式threads不能为空");
                }
                if (config.getThreads() < 1) {
                    throw new BizException("threads必须大于0");
                }
                if (config.getDurationSeconds() != null && config.getDurationSeconds() < 1) {
                    throw new BizException("durationSeconds必须大于0");
                }
            }
            case "FIXED_TPS" -> {
                if (config.getTps() == null) {
                    throw new BizException("FIXED_TPS模式tps不能为空");
                }
                if (config.getTps() < 1) {
                    throw new BizException("tps必须大于0");
                }
                if (config.getDurationSeconds() == null) {
                    throw new BizException("FIXED_TPS模式durationSeconds不能为空");
                }
                if (config.getDurationSeconds() < 1) {
                    throw new BizException("durationSeconds必须大于0");
                }
                if (config.getMaxThreads() != null && config.getMaxThreads() < 1) {
                    throw new BizException("maxThreads必须大于0");
                }
                validateExpectedResponseMs(config);
            }
            case "STEPPED" -> {
                if (!"THREADS".equals(config.getUnit()) && !"TPS".equals(config.getUnit())) {
                    throw new BizException("STEPPED模式unit仅支持THREADS/TPS");
                }
                if (config.getStart() == null) {
                    throw new BizException("STEPPED模式start不能为空");
                }
                if (config.getStart() < 1) {
                    throw new BizException("start必须大于等于1");
                }
                if (config.getStep() == null) {
                    throw new BizException("STEPPED模式step不能为空");
                }
                if (config.getStep() < 1) {
                    throw new BizException("step必须大于等于1");
                }
                if (config.getPeak() == null) {
                    throw new BizException("STEPPED模式peak不能为空");
                }
                if (config.getPeak() < config.getStart()) {
                    throw new BizException("peak必须大于等于start");
                }
                if (config.getStepSeconds() == null || config.getStepSeconds() < 1) {
                    throw new BizException("stepSeconds必须大于0");
                }
                if (config.getPeakSeconds() == null || config.getPeakSeconds() < 1) {
                    throw new BizException("peakSeconds必须大于0");
                }
                if (config.getRampupSeconds() != null && config.getRampupSeconds() < 0) {
                    throw new BizException("rampupSeconds不能为负数");
                }
                if ("TPS".equals(config.getUnit())) {
                    validateExpectedResponseMs(config);
                }
            }
            default -> throw new BizException("压测模式仅支持 CONCURRENT/FIXED_TPS/STEPPED");
        }
    }

    /**
     * 按压测模式生成各节点 jmeter_props：
     * - 单单元（导入脚本/无编排的表单脚本）：沿用既有均分逻辑（见 buildNodePropsLegacy）
     * - 多单元（表单脚本编排了并行接口/多组）：压力总量先按占比拆到执行单元（余数补给占比大的单元），
     *   再按节点均分（share/shareLong，余数补前几台，每台最小 1）：
     *   CONCURRENT → tg{u}.threads；FIXED_TPS → tg{u}.threads/tg{u}.perThreadPerMin；
     *   STEPPED → 每段×每单元 tg{seg}x{u}.*（TPS 单位按目标响应时间估算线程并配置每线程吞吐）
     *
     * @param mode      压测模式
     * @param config    模式参数
     * @param nodeCount 参测节点数
     * @param units     表单脚本执行单元列表（导入脚本传 null）
     * @param weights   占比数组（单单元/未启用占比传 null）
     * @return 每节点的 props Map 列表
     */
    private List<Map<String, Object>> buildNodeProps(String mode, TaskCreateRequest.Config config, int nodeCount,
                                                     List<FormScriptJmxBuilder.ExecUnit> units, int[] weights) {
        if (units == null || units.size() <= 1) {
            return buildNodePropsLegacy(mode, config, nodeCount);
        }
        int unitCount = units.size();
        List<Map<String, Object>> result = new ArrayList<>();
        switch (mode) {
            case "CONCURRENT" -> {
                int rampup = config.getRampupSeconds() == null ? 60 : config.getRampupSeconds();
                int duration = config.getDurationSeconds() == null ? 300 : config.getDurationSeconds();
                int[] unitThreads = WeightSplitter.splitInt(config.getThreads(), weights);
                for (int i = 0; i < nodeCount; i++) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    for (int u = 0; u < unitCount; u++) {
                        props.put("tg" + u + ".threads", share(unitThreads[u], nodeCount, i));
                        props.put("tg" + u + ".rampup", rampup);
                        props.put("tg" + u + ".duration", duration);
                    }
                    result.add(props);
                }
            }
            case "FIXED_TPS" -> {
                int maxThreads = config.getMaxThreads() == null
                        ? TpsCapacity.defaultThreads(config.getTps(), config.getExpectedResponseMs()) : config.getMaxThreads();
                // 启动爬坡时长可配（默认 10s，与 JMX 渲染默认一致）
                int rampup = config.getRampupSeconds() == null ? 10 : config.getRampupSeconds();
                // CTT 吞吐属性为 samples/min（tps×60），节点均分
                long samplesPerMinTotal = (long) config.getTps() * 60;
                int[] unitThreads = WeightSplitter.splitInt(maxThreads, weights);
                long[] unitSamplesPerMin = WeightSplitter.splitLong(samplesPerMinTotal, weights);
                for (int i = 0; i < nodeCount; i++) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    for (int u = 0; u < unitCount; u++) {
                        int nodeThreads = share(Math.max(1, unitThreads[u]), nodeCount, i);
                        props.put("tg" + u + ".threads", nodeThreads);
                        props.put("tg" + u + ".rampup", rampup);
                        props.put("tg" + u + ".duration", config.getDurationSeconds());
                        // CTT 每线程限速（mode=0）：节点吞吐(samples/min) ÷ 节点线程数
                        props.put("tg" + u + ".perThreadPerMin",
                                shareLong(unitSamplesPerMin[u], nodeCount, i) / (double) nodeThreads);
                    }
                    result.add(props);
                }
            }
            case "STEPPED" -> {
                int rampup = config.getRampupSeconds() == null ? 30 : config.getRampupSeconds();
                boolean tpsUnit = "TPS".equals(config.getUnit());
                List<SteppedPlan.Segment> segments = SteppedPlan.expand(config.getStart(), config.getStep(),
                        config.getStepSeconds(), config.getPeak(), config.getPeakSeconds());
                for (int i = 0; i < nodeCount; i++) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    for (int s = 0; s < segments.size(); s++) {
                        SteppedPlan.Segment segment = segments.get(s);
                        int[] unitValues = WeightSplitter.splitInt(segment.getValue(), weights);
                        for (int u = 0; u < unitCount; u++) {
                            String prefix = "tg" + s + "x" + u;
                            props.put(prefix + ".rampup", rampup);
                            props.put(prefix + ".delay", segment.getDelaySeconds());
                            props.put(prefix + ".duration", segment.getDurationSeconds());
                            if (tpsUnit) {
                                int nodeThreads = share(TpsCapacity.defaultThreads(
                                        Math.max(1, unitValues[u]), config.getExpectedResponseMs()), nodeCount, i);
                                props.put(prefix + ".threads", nodeThreads);
                                // CTT 每线程限速（mode=0）：节点段吞吐(samples/min) ÷ 节点线程数，各段独立
                                props.put(prefix + ".perThreadPerMin",
                                        shareLong((long) Math.max(1, unitValues[u]) * 60, nodeCount, i)
                                                / (double) nodeThreads);
                            } else {
                                props.put(prefix + ".threads", share(Math.max(1, unitValues[u]), nodeCount, i));
                            }
                        }
                    }
                    result.add(props);
                }
            }
            default -> throw new BizException("压测模式仅支持 CONCURRENT/FIXED_TPS/STEPPED");
        }
        return result;
    }

    /**
     * 单单元（导入脚本/无编排表单脚本）节点 props 生成：
     * - CONCURRENT：{threads（均分）, rampup, duration}；
     * - FIXED_TPS：{tg0.threads（按 TPS 与预期响应时间估算后均分）, tg0.rampup=60,
     *   tg0.duration, tg0.perThreadPerMin（按节点目标 TPS 换算）}；
     * - STEPPED：每段 {tg{i}.threads 或 tg{i}.perThreadPerMin（TPS 单位按段目标 TPS 与预期响应时间估算）,
     *   tg{i}.rampup（默认30）, tg{i}.delay, tg{i}.duration}
     *
     * @param mode      压测模式
     * @param config    模式参数
     * @param nodeCount 参测节点数
     * @return 每节点的 props Map 列表
     */
    private List<Map<String, Object>> buildNodePropsLegacy(String mode, TaskCreateRequest.Config config, int nodeCount) {
        List<Map<String, Object>> result = new ArrayList<>();
        switch (mode) {
            case "CONCURRENT" -> {
                int rampup = config.getRampupSeconds() == null ? 60 : config.getRampupSeconds();
                int duration = config.getDurationSeconds() == null ? 300 : config.getDurationSeconds();
                for (int i = 0; i < nodeCount; i++) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("threads", share(config.getThreads(), nodeCount, i));
                    props.put("rampup", rampup);
                    props.put("duration", duration);
                    result.add(props);
                }
            }
            case "FIXED_TPS" -> {
                int maxThreads = config.getMaxThreads() == null
                        ? TpsCapacity.defaultThreads(config.getTps(), config.getExpectedResponseMs()) : config.getMaxThreads();
                // 启动爬坡时长可配（默认 10s，与 JMX 渲染默认一致）
                int rampup = config.getRampupSeconds() == null ? 10 : config.getRampupSeconds();
                // CTT 吞吐属性为 samples/min（tps×60），节点均分
                long samplesPerMinTotal = (long) config.getTps() * 60;
                for (int i = 0; i < nodeCount; i++) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    int nodeThreads = share(maxThreads, nodeCount, i);
                    props.put("tg0.threads", nodeThreads);
                    props.put("tg0.rampup", rampup);
                    props.put("tg0.duration", config.getDurationSeconds());
                    // CTT 每线程限速（mode=0）：节点吞吐 ÷ 节点线程数
                    props.put("tg0.perThreadPerMin", shareLong(samplesPerMinTotal, nodeCount, i) / (double) nodeThreads);
                    result.add(props);
                }
            }
            case "STEPPED" -> {
                int rampup = config.getRampupSeconds() == null ? 30 : config.getRampupSeconds();
                boolean tpsUnit = "TPS".equals(config.getUnit());
                List<SteppedPlan.Segment> segments = SteppedPlan.expand(config.getStart(), config.getStep(),
                        config.getStepSeconds(), config.getPeak(), config.getPeakSeconds());
                for (int i = 0; i < nodeCount; i++) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    for (int s = 0; s < segments.size(); s++) {
                        SteppedPlan.Segment segment = segments.get(s);
                        props.put("tg" + s + ".rampup", rampup);
                        props.put("tg" + s + ".delay", segment.getDelaySeconds());
                        props.put("tg" + s + ".duration", segment.getDurationSeconds());
                        if (tpsUnit) {
                            int nodeThreads = share(TpsCapacity.defaultThreads(
                                    segment.getValue(), config.getExpectedResponseMs()), nodeCount, i);
                            props.put("tg" + s + ".threads", nodeThreads);
                            // CTT 每线程限速（mode=0）：节点段吞吐 ÷ 节点线程数
                            props.put("tg" + s + ".perThreadPerMin",
                                    shareLong((long) segment.getValue() * 60, nodeCount, i) / (double) nodeThreads);
                        } else {
                            props.put("tg" + s + ".threads", share(segment.getValue(), nodeCount, i));
                        }
                    }
                    result.add(props);
                }
            }
            default -> throw new BizException("压测模式仅支持 CONCURRENT/FIXED_TPS/STEPPED");
        }
        return result;
    }

    /**
     * 解析脚本的执行单元列表：
     * - FORM：编排单元（串行组整体/并行组内单接口）；
     * - IMPORTED：JMX 普通线程组（排除 setUp/tearDown），≤1 个线程组时返回 null 走均分逻辑；
     * - 无法解析（非表单无定义/导入无线程组）返回 null
     *
     * @param script        脚本实体
     * @param scriptVersion 任务引用的脚本版本（IMPORTED 解析其 JMX 内容）
     * @return 执行单元列表，或 null
     */
    private List<FormScriptJmxBuilder.ExecUnit> resolveExecUnits(Script script, ScriptVersion scriptVersion) {
        if (Script.TYPE_IMPORTED.equals(script.getType())) {
            List<JmxThreadGroupParser.ThreadGroupInfo> groups =
                    JmxThreadGroupParser.parse(scriptVersion.getJmxContent());
            if (groups.size() <= 1) {
                return null;
            }
            return groups.stream()
                    .map(g -> new FormScriptJmxBuilder.ExecUnit(
                            StringUtils.hasText(g.name()) ? g.name() : "线程组", List.of()))
                    .toList();
        }
        if (!Script.TYPE_FORM.equals(script.getType()) || !StringUtils.hasText(script.getFormDef())) {
            return null;
        }
        ScriptFormRequest.FormDef formDef;
        try {
            formDef = objectMapper.readValue(script.getFormDef(), ScriptFormRequest.FormDef.class);
        } catch (Exception e) {
            throw new BizException("表单定义解析失败：" + e.getMessage());
        }
        return FormScriptJmxBuilder.expandUnits(FormScriptJmxBuilder.normalizeGroups(formDef));
    }

    /**
     * 校验流量占比配置并返回生效的占比数组：
     * - 无执行单元（导入单线程组脚本/无编排信息）：不允许传 weights
     * - 单单元：忽略 weights（返回 null）
     * - 多单元（表单编排单元 / 导入脚本多线程组）：weights 必填，每项 1-100 整数、Σ=100，
     *   且最小拆分后每单元每节点 ≥1（线程/TPS）
     *
     * @param config    模式参数（含 weights）
     * @param units     执行单元列表（无单元为 null）
     * @param nodeCount 参测节点数
     * @param mode      压测模式
     * @return 生效占比数组（单单元/无单元返回 null）
     */
    private int[] validateWeights(TaskCreateRequest.Config config,
                                  List<FormScriptJmxBuilder.ExecUnit> units, int nodeCount, String mode) {
        List<Integer> weights = config.getWeights();
        if (units == null) {
            if (weights != null && !weights.isEmpty()) {
                throw new BizException("导入脚本不支持流量占比配置");
            }
            return null;
        }
        if (units.size() <= 1) {
            return null;
        }
        if (weights == null || weights.isEmpty()) {
            throw new BizException("该脚本包含 " + units.size() + " 个执行单元，请设置各单元流量占比");
        }
        int[] effective = WeightSplitter.ensureWeights(weights, units.size());
        int sum = 0;
        for (int w : effective) {
            sum += w;
        }
        if (sum != 100) {
            throw new BizException("流量占比总和必须等于 100，当前为 " + sum);
        }
        validateMinSplit(mode, config, effective, nodeCount, units);
        return effective;
    }

    /**
     * 最小拆分校验：每个执行单元按占比拆分后必须满足每节点 ≥1（并发模式查并发数、
     * TPS 类模式查吞吐与线程数），否则报错并提示所需最小总量：
     * 所需总量 = ⌈100 × 节点数 / 占比⌉
     *
     * @param mode      压测模式
     * @param config    模式参数
     * @param weights   占比数组
     * @param nodeCount 参测节点数
     * @param units     执行单元列表
     */
    private void validateMinSplit(String mode, TaskCreateRequest.Config config, int[] weights,
                                  int nodeCount, List<FormScriptJmxBuilder.ExecUnit> units) {
        switch (mode) {
            case "CONCURRENT" -> {
                for (int u = 0; u < units.size(); u++) {
                    long got = (long) config.getThreads() * weights[u] / 100;
                    if (got < nodeCount) {
                        throw new BizException("「" + units.get(u).name() + "」占比 " + weights[u]
                                + "% 仅分得 " + got + " 并发，低于节点数 " + nodeCount
                                + "，总并发需 ≥ " + minTotal(nodeCount, weights[u]));
                    }
                }
            }
            case "FIXED_TPS" -> {
                for (int u = 0; u < units.size(); u++) {
                    long gotTps = (long) config.getTps() * weights[u] / 100;
                    if (gotTps < nodeCount) {
                        throw new BizException("「" + units.get(u).name() + "」占比 " + weights[u]
                                + "% 仅分得 " + gotTps + " TPS，低于节点数 " + nodeCount
                                + "，总 TPS 需 ≥ " + minTotal(nodeCount, weights[u]));
                    }
                    int maxThreads = config.getMaxThreads() == null
                            ? TpsCapacity.defaultThreads(config.getTps(), config.getExpectedResponseMs()) : config.getMaxThreads();
                    long gotThreads = (long) maxThreads * weights[u] / 100;
                    if (gotThreads < nodeCount) {
                        throw new BizException("「" + units.get(u).name() + "」占比 " + weights[u]
                                + "% 仅分得 " + gotThreads + " 线程，低于节点数 " + nodeCount
                                + "，线程数需 ≥ " + minTotal(nodeCount, weights[u]));
                    }
                }
            }
            case "STEPPED" -> {
                boolean tpsUnit = "TPS".equals(config.getUnit());
                List<SteppedPlan.Segment> segments = SteppedPlan.expand(config.getStart(), config.getStep(),
                        config.getStepSeconds(), config.getPeak(), config.getPeakSeconds());
                for (int s = 0; s < segments.size(); s++) {
                    for (int u = 0; u < units.size(); u++) {
                        long got = (long) segments.get(s).getValue() * weights[u] / 100;
                        String unitName = tpsUnit ? "TPS" : "并发";
                        String totalName = tpsUnit ? "每段 TPS" : "每段并发";
                        if (got < nodeCount) {
                            throw new BizException("第 " + (s + 1) + " 段「" + units.get(u).name() + "」占比 "
                                    + weights[u] + "% 仅分得 " + got + " " + unitName + "，低于节点数 "
                                    + nodeCount + "，" + totalName + "需 ≥ " + minTotal(nodeCount, weights[u]));
                        }
                    }
                }
            }
            default -> throw new BizException("压测模式仅支持 CONCURRENT/FIXED_TPS/STEPPED");
        }
    }

    /**
     * 计算满足"每节点至少 1"的最小总量：⌈100 × 节点数 / 占比⌉
     *
     * @param nodeCount 节点数
     * @param weight    占比（1-100）
     * @return 最小总量
     */
    private long minTotal(int nodeCount, int weight) {
        return (100L * nodeCount + weight - 1) / weight;
    }

    private void validateExpectedResponseMs(TaskCreateRequest.Config config) {
        if (config.getExpectedResponseMs() != null
                && (config.getExpectedResponseMs() < 1 || config.getExpectedResponseMs() > 60_000)) {
            throw new BizException("expectedResponseMs必须在1到60000之间");
        }
    }

    /**
     * 整数均分：整除余数依次补到前 remainder 台节点（每台最小 1，保证节点可运行）
     *
     * @param total     总量
     * @param nodeCount 节点数
     * @param index     节点序号（0 起）
     * @return 该节点分得的份额
     */
    private int share(int total, int nodeCount, int index) {
        int base = total / nodeCount;
        int remainder = total % nodeCount;
        int value = base + (index < remainder ? 1 : 0);
        return Math.max(1, value);
    }

    /**
     * 长整数均分（TPS×60 换算 samples/min 后按节点均分），每台最小 1
     *
     * @param total     总量
     * @param nodeCount 节点数
     * @param index     节点序号（0 起）
     * @return 该节点分得的份额
     */
    private long shareLong(long total, int nodeCount, int index) {
        long base = total / nodeCount;
        long remainder = total % nodeCount;
        long value = base + (index < remainder ? 1 : 0);
        return Math.max(1, value);
    }

    /**
     * 获取任务的 JMX 脚本内容：FORM 脚本任务优先返回按模式渲染的任务级快照，
     * 否则返回脚本版本原始内容（Agent 脚本下载端点使用）
     *
     * @param taskId 任务ID
     * @return JMX 文本
     */
    public String getTaskJmx(Long taskId) {
        TestTask task = requireTask(taskId);
        TaskScriptSnapshot snapshot = taskScriptSnapshotMapper.selectById(taskId);
        return snapshot != null ? snapshot.getJmxContent()
                : scriptService.getJmxByVersionId(task.getScriptVersionId());
    }

    /**
     * 分页查询任务列表（keyword 模糊匹配 name/taskNo，status 精确过滤，按 id 倒序）
     *
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     * @param keyword 关键词
     * @param status  任务状态精确过滤
     * @return 任务分页结果（含脚本名称联查）
     */
    public PageVO<TaskVO> page(long page, long size, String keyword, String status) {
        LambdaQueryWrapper<TestTask> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(TestTask::getName, keyword).or().like(TestTask::getTaskNo, keyword));
        }
        if (StringUtils.hasText(status)) {
            List<String> statuses = java.util.Arrays.stream(status.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .toList();
            if (statuses.size() == 1) {
                wrapper.eq(TestTask::getStatus, statuses.get(0));
            } else if (!statuses.isEmpty()) {
                wrapper.in(TestTask::getStatus, statuses);
            }
        }
        wrapper.orderByDesc(TestTask::getId);
        Page<TestTask> result = taskMapper.selectPage(new Page<>(page, size), wrapper);
        Map<Long, String> scriptNames = scriptService.nameMap(result.getRecords().stream()
                .map(TestTask::getScriptId).toList());
        List<TaskVO> records = result.getRecords().stream()
                .map(t -> toVO(t, scriptNames)).toList();
        return PageVO.of(records, result.getTotal());
    }

    /**
     * 查询任务详情：基本信息 + 参测节点执行明细列表
     *
     * @param id 任务ID
     * @return 任务详情
     */
    public TaskDetailVO detail(Long id) {
        TestTask task = requireTask(id);
        Map<Long, String> scriptNames = scriptService.nameMap(List.of(task.getScriptId()));
        TaskDetailVO vo = new TaskDetailVO();
        copyBase(vo, task, scriptNames);
        List<TaskNode> nodes = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getTaskId, id)
                .orderByAsc(TaskNode::getId));
        // 批量补节点主机名/IP（展示用，便于识别是哪台机器）
        Map<String, Node> nodeInfoMap = nodeMapper.selectList(new LambdaQueryWrapper<Node>()
                        .in(Node::getNodeKey, nodes.stream().map(TaskNode::getNodeKey).toList()))
                .stream().collect(Collectors.toMap(Node::getNodeKey, n -> n, (a, b) -> a));
        vo.setNodes(nodes.stream().map(n -> toNodeVO(n, nodeInfoMap.get(n.getNodeKey()))).toList());
        return vo;
    }

    /**
     * 启动任务：CREATED → PREPARING，等待各节点 Agent 轮询取 PREPARE 命令（手动入口）
     *
     * @param id 任务ID
     */
    public void start(Long id) {
        start(id, true);
    }

    /**
     * 启动任务（手动与定时共用）：CREATED → PREPARING，等待各节点 Agent 轮询取 PREPARE 命令。
     * 手动启动时触发方式置 MANUAL 并清空定时启动时间；定时触发（manual=false）保留 SCHEDULED 标记与计划时间
     *
     * @param id     任务ID
     * @param manual 是否手动启动（true 手动 / false 定时调度触发）
     */
    public synchronized void start(Long id, boolean manual) {
        TestTask task = requireTask(id);
        if (!TestTask.STATUS_CREATED.equals(task.getStatus())) {
            throw new BizException("仅CREATED状态的任务可启动，当前：" + task.getStatus());
        }
        requireNodesOnline(id);
        taskMapper.update(null, new LambdaUpdateWrapper<TestTask>()
                .set(TestTask::getStatus, TestTask.STATUS_PREPARING)
                .set(TestTask::getStatusTime, LocalDateTime.now())
                .set(TestTask::getTriggerType, manual ? TestTask.TRIGGER_MANUAL : TestTask.TRIGGER_SCHEDULED)
                .set(TestTask::getScheduledStartTime, manual ? null : task.getScheduledStartTime())
                .eq(TestTask::getId, id));
        auditService.record("START_TASK", "启动压测任务 " + task.getTaskNo()
                + (manual ? "" : "（定时触发）"));
    }

    /**
     * 启动前校验参测节点全部在线：离线（或已删除）节点无法接收 PREPARE 命令，
     * 若放行会导致任务挂死 PREPARING（虽有准备超时兜底，但应尽早失败并点名问题节点）。
     *
     * @param taskId 任务ID
     */
    private void requireNodesOnline(Long taskId) {
        List<TaskNode> taskNodes = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getTaskId, taskId));
        List<String> nodeKeys = taskNodes.stream().map(TaskNode::getNodeKey).toList();
        if (nodeKeys.isEmpty()) {
            return;
        }
        List<Node> nodes = nodeMapper.selectList(new LambdaQueryWrapper<Node>()
                .in(Node::getNodeKey, nodeKeys));
        Map<String, Node> byKey = new HashMap<>();
        for (Node node : nodes) {
            byKey.put(node.getNodeKey(), node);
        }
        List<String> offline = new ArrayList<>();
        for (String key : nodeKeys) {
            Node node = byKey.get(key);
            if (node == null || !Node.STATUS_ONLINE.equals(node.getStatus())) {
                offline.add(node == null ? key : node.getHostname() + "(" + node.getIp() + ")");
            }
        }
        if (!offline.isEmpty()) {
            throw new BizException("以下节点当前离线，无法启动任务：" + String.join("、", offline)
                    + "。请等待节点上线或编辑任务移除该节点");
        }
        List<String> engineMissing = nodes.stream()
                .filter(n -> !StringUtils.hasText(n.getEngineVersion()))
                .map(n -> n.getHostname() + "(" + n.getIp() + ")")
                .toList();
        if (!engineMissing.isEmpty()) {
            throw new BizException("以下节点尚未完成 JMeter 引擎部署：" + String.join("、", engineMissing));
        }
        Set<String> engineVersions = nodes.stream().map(Node::getEngineVersion).collect(Collectors.toSet());
        if (engineVersions.size() > 1) {
            throw new BizException("参测节点 JMeter 引擎版本不一致：" + String.join("、", engineVersions));
        }
        List<String> overloaded = nodes.stream()
                .filter(n -> (n.getCpuUsage() != null && n.getCpuUsage() >= START_MAX_CPU_PERCENT)
                        || (n.getMemUsage() != null && n.getMemUsage() >= START_MAX_MEMORY_PERCENT))
                .map(n -> n.getHostname() + "(CPU " + valueOrDash(n.getCpuUsage())
                        + "%，内存 " + valueOrDash(n.getMemUsage()) + "%）")
                .toList();
        if (!overloaded.isEmpty()) {
            throw new BizException("以下节点当前资源占用过高，拒绝启动：" + String.join("、", overloaded));
        }
        requireNodesExclusive(taskId, nodeKeys, byKey);
    }

    private void requireNodesExclusive(Long taskId, List<String> nodeKeys, Map<String, Node> nodeInfo) {
        List<TestTask> activeTasks = taskMapper.selectList(new LambdaQueryWrapper<TestTask>()
                .ne(TestTask::getId, taskId)
                .in(TestTask::getStatus, TestTask.STATUS_PREPARING,
                        TestTask.STATUS_RUNNING, TestTask.STATUS_STOPPING));
        if (activeTasks.isEmpty()) {
            return;
        }
        List<Long> activeTaskIds = activeTasks.stream().map(TestTask::getId).toList();
        List<TaskNode> conflicts = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                .in(TaskNode::getTaskId, activeTaskIds)
                .in(TaskNode::getNodeKey, nodeKeys)
                .notIn(TaskNode::getStatus, TaskNode.STATUS_STOPPED, TaskNode.STATUS_FINISHED,
                        TaskNode.STATUS_FAILED, TaskNode.STATUS_EXCLUDED));
        if (conflicts.isEmpty()) {
            return;
        }
        Map<Long, String> taskNames = activeTasks.stream()
                .collect(Collectors.toMap(TestTask::getId, TestTask::getTaskNo));
        List<String> details = conflicts.stream().map(conflict -> {
            Node node = nodeInfo.get(conflict.getNodeKey());
            String host = node == null ? conflict.getNodeKey() : node.getHostname() + "(" + node.getIp() + ")";
            return host + " 被任务 " + taskNames.getOrDefault(conflict.getTaskId(), String.valueOf(conflict.getTaskId())) + " 占用";
        }).distinct().toList();
        throw new BizException("压力节点默认独占，存在执行中冲突：" + String.join("；", details));
    }

    private String valueOrDash(Double value) {
        return value == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * 停止任务：PREPARING/RUNNING → STOPPING，Agent 轮询取 STOP 命令
     *
     * @param id 任务ID
     */
    public void stop(Long id) {
        TestTask task = requireTask(id);
        if (!TestTask.STATUS_PREPARING.equals(task.getStatus())
                && !TestTask.STATUS_RUNNING.equals(task.getStatus())) {
            throw new BizException("仅PREPARING/RUNNING状态的任务可停止，当前：" + task.getStatus());
        }
        TestTask update = new TestTask();
        update.setId(id);
        update.setStatus(TestTask.STATUS_STOPPING);
        update.setStatusTime(LocalDateTime.now());
        taskMapper.updateById(update);
        taskOrchestrator.markStopRequested(id);
        auditService.record("STOP_TASK", "停止压测任务 " + task.getTaskNo());
    }

    /**
     * 删除任务：仅终态（FINISHED/FAILED/PARTIAL_FAILED/CANCELLED）或未启动（CREATED）任务可删，
     * 连同全部关联数据一并清理——压测报告、指标快照、错误样本、任务脚本快照、节点执行明细，
     * 记录审计日志。执行中任务须先停止并等收敛后再删
     *
     * @param id 任务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        TestTask task = requireTask(id);
        String status = task.getStatus();
        if (!TestTask.STATUS_CREATED.equals(status) && !TestTask.STATUS_FINISHED.equals(status)
                && !TestTask.STATUS_FAILED.equals(status)
                && !TestTask.STATUS_PARTIAL_FAILED.equals(status)
                && !TestTask.STATUS_CANCELLED.equals(status)) {
            throw new BizException("仅已创建/已完成/失败状态的任务可删除，请先停止执行中的任务，当前：" + status);
        }
        reportMapper.delete(new LambdaQueryWrapper<TestReport>()
                .eq(TestReport::getTaskId, id));
        metricSnapshotMapper.delete(new LambdaQueryWrapper<MetricSnapshot>()
                .eq(MetricSnapshot::getTaskId, id));
        errorSampleMapper.delete(new LambdaQueryWrapper<ErrorSample>()
                .eq(ErrorSample::getTaskId, id));
        deleteTaskShardFiles(task);
        taskScriptSnapshotMapper.delete(new LambdaQueryWrapper<TaskScriptSnapshot>()
                .eq(TaskScriptSnapshot::getTaskId, id));
        taskNodeMapper.delete(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getTaskId, id));
        taskMapper.deleteById(id);
        auditService.record("DELETE_TASK", "删除压测任务 " + task.getTaskNo() + "（状态 " + status + "，含报告与指标数据）");
    }

    /**
     * 预生成 SPLIT 分片文件：CSV 首行为表头逐份补写、数据行均分；非 CSV 直接按行均分。
     * 分片落盘 {storage}/shard-{taskId}-{fileId}-{idx}
     *
     * @param file       数据文件实体
     * @param taskId     任务ID
     * @param shardCount 分片数量（= 节点数）
     */
    private void splitFile(DataFile file, Long taskId, int shardCount) {
        Path source = storageService.filePath(file.getMd5());
        boolean csv = "CSV".equals(file.getFileType());
        long totalLines;
        String header = null;
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (csv) {
                header = first;
            }
            totalLines = first == null ? 0 : 1;
            while (reader.readLine() != null) {
                totalLines++;
            }
        } catch (IOException e) {
            throw new BizException("读取待拆分文件失败：" + file.getName());
        }
        long dataLines = Math.max(0, totalLines - (csv && totalLines > 0 ? 1 : 0));
        long base = dataLines / shardCount;
        long remainder = dataLines % shardCount;
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            if (csv) {
                reader.readLine();
            }
            for (int idx = 0; idx < shardCount; idx++) {
                long count = base + (idx < remainder ? 1 : 0);
                Path target = storageService.shardPath(taskId, file.getId(), idx);
                Files.createDirectories(target.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    if (header != null) {
                        writer.write(header);
                        writer.newLine();
                    }
                    for (long lineIndex = 0; lineIndex < count; lineIndex++) {
                        String line = reader.readLine();
                        if (line == null) {
                            throw new IOException("文件行数在拆分过程中发生变化");
                        }
                        writer.write(line);
                        writer.newLine();
                    }
                }
            }
        } catch (IOException e) {
            throw new BizException("生成分片文件失败：" + e.getMessage());
        }
    }

    /**
     * 生成 4 位随机数字（任务编号后缀）
     *
     * @return 4 位数字字符串（不足左补 0）
     */
    private String random4() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /**
     * 校验任务存在，不存在抛业务异常
     *
     * @param id 任务ID
     * @return 任务实体
     */
    public TestTask requireTask(Long id) {
        TestTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException("任务不存在");
        }
        return task;
    }

    /**
     * 获取当前登录用户名（未登录时返回 null）
     *
     * @return 当前用户名
     */
    private String currentUsername() {
        return UserContext.get() == null ? null : UserContext.get().getUsername();
    }

    /**
     * 对象序列化为 JSON 字符串（失败抛业务异常）
     *
     * @param value 任意可序列化对象
     * @return JSON 字符串
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("JSON序列化失败：" + e.getMessage());
        }
    }

    /**
     * 任务实体转视图对象（含脚本名称）
     *
     * @param task        任务实体
     * @param scriptNames 脚本ID → 名称映射
     * @return 任务视图对象
     */
    private TaskVO toVO(TestTask task, Map<Long, String> scriptNames) {
        TaskVO vo = new TaskVO();
        copyBase(vo, task, scriptNames);
        return vo;
    }

    /**
     * 复制任务基本信息到视图对象（config/nodeKeys/fileDispatch 由 JSON 解析）
     *
     * @param vo          目标视图对象
     * @param task        任务实体
     * @param scriptNames 脚本ID → 名称映射
     */
    private void copyBase(TaskVO vo, TestTask task, Map<Long, String> scriptNames) {
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setName(task.getName());
        vo.setScriptId(task.getScriptId());
        vo.setScriptName(scriptNames.get(task.getScriptId()));
        vo.setScriptVersionId(task.getScriptVersionId());
        ScriptVersion scriptVersion = scriptVersionMapper.selectById(task.getScriptVersionId());
        vo.setScriptVersion(scriptVersion == null ? null : scriptVersion.getVersion());
        vo.setMode(task.getMode());
        vo.setConfig(readTree(task.getConfigJson()));
        vo.setNodeKeys(StringUtils.hasText(task.getNodeKeys())
                ? List.of(task.getNodeKeys().split(",")) : List.of());
        vo.setFileDispatch(readTree(task.getFileDispatchJson()));
        vo.setStatus(task.getStatus());
        // 执行方式与定时启动时间（MANUAL 立即 / SCHEDULED 定时）
        vo.setTriggerType(task.getTriggerType());
        vo.setScheduledStartTime(task.getScheduledStartTime());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        vo.setCreateBy(task.getCreateBy());
        vo.setCreateTime(task.getCreateTime());
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

    /**
     * 任务节点实体转视图对象（jmeter_props 解析为 JSON 节点）
     *
     * @param node 任务节点实体
     * @return 任务节点视图对象
     */
    private TaskNodeVO toNodeVO(TaskNode node, Node nodeInfo) {
        TaskNodeVO vo = new TaskNodeVO();
        vo.setId(node.getId());
        vo.setNodeKey(node.getNodeKey());
        // 主机名/IP 展示（节点已被删除时为空，前端回退显示 nodeKey）
        if (nodeInfo != null) {
            vo.setHostname(nodeInfo.getHostname());
            vo.setIp(nodeInfo.getIp());
        }
        vo.setStatus(node.getStatus());
        vo.setJmeterProps(readTree(node.getJmeterProps()));
        vo.setShardIndex(node.getShardIndex());
        vo.setErrorMsg(node.getErrorMsg());
        vo.setStartTime(node.getStartTime());
        vo.setEndTime(node.getEndTime());
        return vo;
    }
}
