package com.per.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Agent 命令轮询响应 DTO（M1 恒为空，M2 返回待执行命令/任务）
 */
@Data
@AllArgsConstructor
public class AgentPollVO {

    /** 待执行命令（M1 恒为 null） */
    private Object command;

    /** 待执行任务（M1 恒为 null） */
    private Object task;
}
