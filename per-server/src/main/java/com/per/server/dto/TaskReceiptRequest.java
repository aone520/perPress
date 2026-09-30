package com.per.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Agent 任务回执请求 DTO：节点上报各阶段执行结果
 */
@Data
public class TaskReceiptRequest {

    /** 任务ID */
    @NotNull(message = "taskId不能为空")
    private Long taskId;

    /** 节点唯一标识 */
    @NotBlank(message = "nodeKey不能为空")
    private String nodeKey;

    /** 阶段：READY/RUNNING/FINISHED/FAILED */
    @NotBlank(message = "phase不能为空")
    private String phase;

    /** 附加信息（失败原因等） */
    private String message;
}
