package com.per.server.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 简单任务命令载荷（poll START/STOP 命令的 task 部分）
 */
@Data
@NoArgsConstructor
public class AgentTaskCommandVO {

    /** 任务ID */
    private Long taskId;

    /** START 指令统一起跑时间（服务端 epoch 毫秒；STOP 可空） */
    private Long startAt;

    public AgentTaskCommandVO(Long taskId) {
        this.taskId = taskId;
    }

    public AgentTaskCommandVO(Long taskId, Long startAt) {
        this.taskId = taskId;
        this.startAt = startAt;
    }
}
