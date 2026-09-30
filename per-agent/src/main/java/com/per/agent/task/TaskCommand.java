package com.per.agent.task;

/**
 * 任务指令载体：封装轮询接口下发的 {command, task}（task 已反序列化为结构化对象）。
 * <p>PREPARE 时 task 为完整任务详情；START/STOP 时 task 仅携带 taskId。</p>
 *
 * @param command 指令类型（PREPARE/START/STOP）
 * @param task    任务详情
 */
public record TaskCommand(AgentCommand command, TaskSpec task) {

    /**
     * 获取本次指令对应的任务 ID（便捷方法）。
     *
     * @return 任务 ID
     */
    public long taskId() {
        return task.taskId();
    }
}
