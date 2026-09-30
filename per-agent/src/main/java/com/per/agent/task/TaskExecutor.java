package com.per.agent.task;

/**
 * 压测任务执行器接口：按服务端指令（PREPARE/START/STOP）驱动压测任务生命周期（M2 任务协议）。
 */
public interface TaskExecutor {

    /**
     * 处理 PREPARE 指令：下载脚本（MD5 校验）与附件（SHARED 校验 MD5），全部成功回执 READY，
     * 任一失败回执 FAILED；重复 PREPARE 依据 state.json 幂等跳过。
     *
     * @param task 任务详情
     * @throws Exception 执行过程中的任意异常
     */
    void handlePrepare(TaskSpec task) throws Exception;

    /**
     * 处理 START 指令：校验已 PREPARED 且引擎就绪后启动 JMeter 子进程，回执 RUNNING；
     * 进程退出后按退出码回执 FINISHED/FAILED。
     *
     * @param taskId 任务 ID
     * @throws Exception 执行过程中的任意异常
     */
    void handleStart(long taskId) throws Exception;

    /**
     * 处理 STOP 指令：销毁对应任务进程（3 秒未退强杀）并回执 FINISHED。
     *
     * @param taskId 任务 ID
     * @throws Exception 执行过程中的任意异常
     */
    void handleStop(long taskId) throws Exception;

    /**
     * 查询任务是否运行中（M3 指标采集使用，本里程碑仅预留接口）。
     *
     * @param taskId 任务 ID
     * @return true 表示对应 JMeter 进程仍在运行
     */
    boolean isRunning(long taskId);

    /**
     * 停止所有运行中的压测任务（心跳失联自停时调用）。
     */
    void stopAllRunning();
}
