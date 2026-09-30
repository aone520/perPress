package com.per.server.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 压测任务视图对象：任务列表展示
 */
@Data
public class TaskVO {

    /** 任务ID */
    private Long id;

    /** 任务编号（T+yyyyMMdd+4位随机） */
    private String taskNo;

    /** 任务名称 */
    private String name;

    /** 脚本ID */
    private Long scriptId;

    /** 脚本名称（联查展示用） */
    private String scriptName;

    /** 脚本版本ID */
    private Long scriptVersionId;

    /** 脚本版本号 */
    private Integer scriptVersion;

    /** 压测模式：CONCURRENT */
    private String mode;

    /** 模式参数（config_json 解析后的 JSON 节点） */
    private JsonNode config;

    /** 参测节点 node_key 数组 */
    private List<String> nodeKeys;

    /** 文件分发策略（file_dispatch_json 解析后的 JSON 节点） */
    private JsonNode fileDispatch;

    /** 任务状态：CREATED/PREPARING/RUNNING/STOPPING/FINISHED/FAILED */
    private String status;

    /** 触发方式：MANUAL 立即手动 / SCHEDULED 定时 */
    private String triggerType;

    /** 定时启动时间（trigger_type=SCHEDULED 时有值，手动启动后清空） */
    private LocalDateTime scheduledStartTime;

    /** 任务开始时间 */
    private LocalDateTime startTime;

    /** 任务结束时间 */
    private LocalDateTime endTime;

    /** 创建人 */
    private String createBy;

    /** 创建时间 */
    private LocalDateTime createTime;
}
