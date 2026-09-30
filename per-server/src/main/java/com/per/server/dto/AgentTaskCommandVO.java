package com.per.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 简单任务命令载荷（poll START/STOP 命令的 task 部分）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskCommandVO {

    /** 任务ID */
    private Long taskId;
}
