package com.per.agent.task;

/**
 * 服务端下发指令枚举（M2 任务协议）。
 */
public enum AgentCommand {

    /** 准备任务：下载脚本与附件，全部成功后回执 READY，任一失败回执 FAILED */
    PREPARE,

    /** 启动压测：等待统一起跑时间，启动 JMeter 子进程并回执 RUNNING，进程退出后按退出码回执 FINISHED/FAILED */
    START,

    /** 停止压测：强停对应任务进程并回执 FINISHED */
    STOP
}
