package com.per.agent.client;

/**
 * 任务回执请求体（M2 任务协议）：{taskId, nodeKey, phase, message}，
 * POST {server}/agent/task/receipt。
 *
 * @param taskId 任务 ID
 * @param nodeKey 节点密钥
 * @param phase   回执阶段：READY/RUNNING/FINISHED/FAILED
 * @param message 附加说明（失败原因、停止原因等）
 */
public record TaskReceipt(Long taskId, String nodeKey, String phase, String message) {

    /** 回执阶段：资源准备完成（脚本与附件下载校验通过） */
    public static final String PHASE_READY = "READY";

    /** 回执阶段：压测进程已启动 */
    public static final String PHASE_RUNNING = "RUNNING";

    /** 回执阶段：压测结束（正常完成或被服务端停止） */
    public static final String PHASE_FINISHED = "FINISHED";

    /** 回执阶段：失败（准备/启动/运行任一环节） */
    public static final String PHASE_FAILED = "FAILED";
}
