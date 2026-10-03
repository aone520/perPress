package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.dto.PageVO;
import com.per.server.dto.TaskCreateRequest;
import com.per.server.dto.TaskDetailVO;
import com.per.server.dto.TaskMetricsVO;
import com.per.server.dto.TaskReportVO;
import com.per.server.dto.TaskVO;
import com.per.server.service.MetricService;
import com.per.server.service.ReportService;
import com.per.server.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 压测任务管理接口：任务创建、分页查询、详情、启动与停止
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final MetricService metricService;
    private final ReportService reportService;

    /**
     * 创建压测任务（校验脚本版本与节点存在；SPLIT 文件预生成分片），状态 CREATED
     *
     * @param request 任务创建请求
     * @return 任务详情（含节点明细）
     */
    @PostMapping
    public R<TaskDetailVO> create(@RequestBody @Valid TaskCreateRequest request) {
        return R.ok(taskService.create(request));
    }

    /**
     * 编辑压测任务（仅 CREATED 状态可编辑，否则业务错误"仅未启动任务可编辑"）：
     * 请求体结构同创建接口，更新名称/模式/模式参数/参测节点/文件分发策略，
     * 旧 task_node 删除后重建（SPLIT 分片按新节点数重新生成）；脚本与版本不允许变更
     *
     * @param id      任务ID
     * @param request 任务编辑请求（结构同创建请求）
     * @return 编辑后的任务详情（含重建的节点明细）
     */
    @PutMapping("/{id}")
    public R<TaskDetailVO> update(@PathVariable Long id, @RequestBody @Valid TaskCreateRequest request) {
        return R.ok(taskService.update(id, request));
    }

    /**
     * 分页查询任务列表
     *
     * @param page    页码，默认 1
     * @param size    每页条数，默认 10
     * @param keyword 关键词（模糊匹配任务名/任务编号）
     * @param status  任务状态精确过滤
     * @return 任务分页数据
     */
    @GetMapping
    public R<PageVO<TaskVO>> page(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String status) {
        return R.ok(taskService.page(page, size, keyword, status));
    }

    /**
     * 查询任务详情（含参测节点执行明细）
     *
     * @param id 任务ID
     * @return 任务详情
     */
    @GetMapping("/{id}")
    public R<TaskDetailVO> detail(@PathVariable Long id) {
        return R.ok(taskService.detail(id));
    }

    /**
     * 启动任务：CREATED → PREPARING，等待 Agent 轮询取 PREPARE 命令
     *
     * @param id 任务ID
     * @return 空数据成功响应
     */
    @PostMapping("/{id}/start")
    public R<Void> start(@PathVariable Long id) {
        taskService.start(id);
        return R.ok();
    }

    /**
     * 停止任务：→ STOPPING，Agent 轮询取 STOP 命令
     *
     * @param id 任务ID
     * @return 空数据成功响应
     */
    @PostMapping("/{id}/stop")
    public R<Void> stop(@PathVariable Long id) {
        taskService.stop(id);
        return R.ok();
    }

    /**
     * 删除任务：仅终态（FINISHED/FAILED/PARTIAL_FAILED/CANCELLED）或未启动（CREATED）任务可删，
     * 连同报告、指标快照、错误样本、脚本快照、节点明细一并清理
     *
     * @param id 任务ID
     * @return 空数据成功响应
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return R.ok();
    }

    /**
     * 一键复制任务：源任务的脚本/版本/模式/配置/节点/文件分发转为新任务（CREATED），
     * 名称自动追加「-副本」后缀，执行方式重置为立即执行
     *
     * @param id 源任务ID
     * @return 复制出的新任务详情
     */
    @PostMapping("/{id}/copy")
    public R<TaskDetailVO> copy(@PathVariable Long id) {
        return R.ok(taskService.copy(id));
    }

    /**
     * 查询任务实时指标：各节点快照按 (窗口,采样器) 合并，
     * 返回窗口时间序列/采样器指标/总量（任务未结束也可查，用于实时监控）
     *
     * @param id 任务ID
     * @return 实时指标（series/samplers/total）
     */
    @GetMapping("/{id}/metrics")
    public R<TaskMetricsVO> metrics(@PathVariable Long id,
                                    @RequestParam(required = false) Long afterWindow,
                                    @RequestParam(defaultValue = "true") boolean includeSummary) {
        taskService.requireTask(id);
        return R.ok(metricService.taskMetrics(id, afterWindow, includeSummary));
    }

    /**
     * 查询任务测试报告：有固化报告直接返回（finalized=true），
     * 未结束任务实时聚合返回（finalized=false）
     *
     * @param id 任务ID
     * @return 测试报告（summary/samplers/nodes/errors/series）
     */
    @GetMapping("/{id}/report")
    public R<TaskReportVO> report(@PathVariable Long id) {
        return R.ok(reportService.report(id));
    }

    /**
     * 导出任务离线 HTML 报告（单文件自包含、内联 CSS、无外链与 JS 依赖、打印友好）：
     * 以附件形式下载 report-{taskNo}.html，数据口径与 /report 一致（有固化读固化，否则实时聚合）
     *
     * @param id 任务ID
     * @return HTML 附件响应
     */
    @GetMapping(value = "/{id}/report/export", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> exportReport(@PathVariable Long id) {
        String taskNo = taskService.requireTask(id).getTaskNo();
        String html = reportService.exportHtml(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + taskNo + ".html")
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html);
    }
}
