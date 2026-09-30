package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.per.server.entity.TestTask;
import com.per.server.mapper.TestTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务启动调度器：周期扫描到达计划启动时间的定时任务并触发启动。
 * 扫描条件：status=CREATED 且 trigger_type=SCHEDULED 且 scheduled_start_time<=当前时间，
 * 命中后调用 TaskService.start(id, false) 以定时语义启动（保留 SCHEDULED 标记）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskScheduleScheduler {

    private final TestTaskMapper taskMapper;
    private final TaskService taskService;

    /**
     * 每 10 秒执行一次：扫描到期的定时任务并逐条触发启动；
     * 查询异常仅 WARN 不中断调度，单条任务触发失败仅 WARN 不影响其余任务
     */
    @Scheduled(fixedDelay = 10_000)
    public void triggerScheduledTasks() {
        List<TestTask> dueTasks;
        try {
            dueTasks = taskMapper.selectList(new LambdaQueryWrapper<TestTask>()
                    .select(TestTask::getId, TestTask::getTaskNo)
                    .eq(TestTask::getStatus, TestTask.STATUS_CREATED)
                    .eq(TestTask::getTriggerType, TestTask.TRIGGER_SCHEDULED)
                    .le(TestTask::getScheduledStartTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("定时任务扫描查询失败，本轮跳过", e);
            return;
        }
        for (TestTask task : dueTasks) {
            try {
                taskService.start(task.getId(), false);
                log.info("定时任务触发：taskNo={}，taskId={}，计划时间已到达", task.getTaskNo(), task.getId());
            } catch (Exception e) {
                log.warn("定时任务触发失败：taskNo={}，taskId={}", task.getTaskNo(), task.getId(), e);
            }
        }
    }
}
