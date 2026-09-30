package com.per.agent.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 轮询响应 data 结构：{command, task}。
 *
 * @param command 指令类型（PREPARE/START/STOP，无指令时 data 为 null 或 command 为空）
 * @param task    任务详情原始 JSON（PREPARE 为完整任务结构，START/STOP 仅含 taskId）
 */
public record PollResponse(String command, JsonNode task) {
}
