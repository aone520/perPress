package com.per.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.per.agent.client.PollResponse;
import com.per.agent.client.ServerClient;
import com.per.agent.common.Jsons;
import com.per.agent.task.AgentCommand;
import com.per.agent.task.TaskCommand;
import com.per.agent.task.TaskExecutor;
import com.per.agent.task.TaskSpec;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务指令轮询任务（M2）：仅在节点完成注册后轮询 /agent/poll，
 * 按 command（PREPARE/START/STOP）分发到任务执行器。
 * <p>PREPARE/START 可能耗时（下载脚本附件、启动 JMeter 子进程），投递到独立任务工作线程池
 * （task-worker）异步执行，保证 poll 调度立即返回；STOP 为快速强停操作，直接在调度线程内联执行，
 * 避免排队阻塞。方法体异常全捕获（ERROR 日志），确保调度永不中断。</p>
 */
@Slf4j
@Component
public class PollTask {

    private final AgentLifecycle lifecycle;
    private final ServerClient serverClient;
    private final TaskExecutor taskExecutor;
    private final ExecutorService taskWorkerExecutor;

    /**
     * 构造轮询任务。
     *
     * @param lifecycle          生命周期（注册状态与 nodeKey）
     * @param serverClient       服务端客户端
     * @param taskExecutor       任务执行器
     * @param taskWorkerExecutor 任务工作线程池（PREPARE/START 异步执行）
     */
    public PollTask(AgentLifecycle lifecycle,
                    ServerClient serverClient,
                    TaskExecutor taskExecutor,
                    @Qualifier("taskWorkerExecutor") ExecutorService taskWorkerExecutor) {
        this.lifecycle = lifecycle;
        this.serverClient = serverClient;
        this.taskExecutor = taskExecutor;
        this.taskWorkerExecutor = taskWorkerExecutor;
    }

    /**
     * 轮询调度入口：fixedDelay=轮询间隔，首次启动延迟 5s；
     * 方法体异常全捕获（ERROR 日志），确保调度永不中断。
     */
    @Scheduled(fixedDelayString = "${per.agent.poll-interval-seconds}000", initialDelay = 5000)
    public void poll() {
        try {
            doPoll();
        } catch (Throwable t) {
            log.error("[Poll] 指令轮询任务异常: {}", t.getMessage(), t);
        }
    }

    /**
     * 执行一次轮询：未注册跳过；无指令忽略；未知指令类型或缺少任务信息告警忽略；
     * 有效指令（PREPARE/START/STOP）构造 TaskCommand 后分发到任务执行器。
     */
    private void doPoll() {
        if (!lifecycle.isRegistered()) {
            return;
        }
        taskExecutor.reportRecoveredFailures();
        PollResponse response = serverClient.poll(lifecycle.currentNodeKey());
        if (response == null || response.command() == null || response.command().isBlank()) {
            return; // 无指令
        }
        AgentCommand command = parseCommand(response.command());
        if (command == null) {
            log.warn("[Poll] 收到未知指令类型，忽略: command={}, task={}", response.command(), response.task());
            return;
        }
        TaskSpec task = parseTask(response.task());
        if (task == null || task.taskId() == null) {
            log.warn("[Poll] 指令缺少任务信息，忽略: command={}, task={}", response.command(), response.task());
            return;
        }
        log.info("[Poll] 收到服务端指令: command={}, taskId={}, taskNo={}", command, task.taskId(), task.taskNo());
        dispatch(new TaskCommand(command, task));
    }

    /**
     * 指令分发：PREPARE/START 投递任务工作线程异步执行；STOP 内联快速执行（强停不排队）。
     *
     * @param command 任务指令载体
     */
    private void dispatch(TaskCommand command) {
        switch (command.command()) {
            case PREPARE -> submitWorker(() -> taskExecutor.handlePrepare(command.task()));
            case START -> submitWorker(() -> taskExecutor.handleStart(command.taskId(), command.task().startAt()));
            case STOP -> {
                try {
                    taskExecutor.handleStop(command.taskId());
                } catch (Throwable t) {
                    log.error("[Poll] STOP 指令执行异常: taskId={}", command.taskId(), t);
                }
            }
            default -> log.warn("[Poll] 未处理的指令类型: {}", command.command());
        }
    }

    /**
     * 投递动作到任务工作线程池（内部再包一层异常捕获，投递失败与执行失败均不影响调度）。
     *
     * @param action 待异步执行的动作
     */
    private void submitWorker(ThrowingRunnable action) {
        try {
            taskWorkerExecutor.execute(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    log.error("[Poll] 任务指令异步执行异常", t);
                }
            });
        } catch (Throwable t) {
            log.error("[Poll] 任务指令投递失败（任务工作线程池异常）", t);
        }
    }

    /**
     * 将指令字符串解析为枚举：PREPARE/START/STOP，未知值返回 null。
     *
     * @param command 指令字符串
     * @return 指令枚举，未知返回 null
     */
    private AgentCommand parseCommand(String command) {
        try {
            return AgentCommand.valueOf(command.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 将轮询响应中的任务详情 JSON 反序列化为 TaskSpec，缺失/非法返回 null。
     *
     * @param task 任务详情原始 JSON
     * @return 任务详情对象，非法返回 null
     */
    private TaskSpec parseTask(JsonNode task) {
        if (task == null || task.isNull() || !task.isObject()) {
            return null;
        }
        try {
            return Jsons.MAPPER.convertValue(task, TaskSpec.class);
        } catch (Exception e) {
            log.warn("[Poll] 任务详情解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 可抛异常的动作封装（任务工作线程内统一捕获）。
     */
    @FunctionalInterface
    private interface ThrowingRunnable {

        /**
         * 执行动作。
         *
         * @throws Exception 任意异常
         */
        void run() throws Exception;
    }
}
