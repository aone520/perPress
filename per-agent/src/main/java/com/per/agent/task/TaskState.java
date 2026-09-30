package com.per.agent.task;

import java.util.Map;

/**
 * 任务本地状态（持久化于 data-dir/tasks/{taskId}/state.json）：
 * 记录 PREPARED 幂等标记、启动/结束时间戳与 JMeter 属性（START 指令仅携带 taskId，
 * 属性需在 PREPARE 时落盘、START 时读取复用）。
 *
 * @param taskId      任务 ID
 * @param status      状态（PREPARED/RUNNING/FINISHED/FAILED）
 * @param startedAt   JMeter 进程启动时间戳（毫秒，未启动为 null）
 * @param endedAt     进程结束时间戳（毫秒，未结束为 null）
 * @param jmeterProps PREPARE 下发的 JMeter 属性透传表
 * @param jmeterHeapMb PREPARE 下发的任务级堆内存上限（MB，可空；START 仅携带 taskId，需随状态落盘复用）
 */
public record TaskState(
        Long taskId,
        String status,
        Long startedAt,
        Long endedAt,
        Map<String, Object> jmeterProps,
        Integer jmeterHeapMb) {

    /** 状态：资源已准备（PREPARE 指令下载全部完成） */
    public static final String STATUS_PREPARED = "PREPARED";

    /** 状态：JMeter 进程运行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：已结束（正常完成或被停止） */
    public static final String STATUS_FINISHED = "FINISHED";

    /** 状态：失败（准备失败/启动失败/运行异常退出） */
    public static final String STATUS_FAILED = "FAILED";
}
