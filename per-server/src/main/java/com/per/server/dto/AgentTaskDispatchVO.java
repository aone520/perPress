package com.per.server.dto;

import lombok.Data;

import java.util.List;

/**
 * Agent 任务下发载荷（poll PREPARE 命令的 task 部分）
 */
@Data
public class AgentTaskDispatchVO {

    /** 任务ID */
    private Long taskId;

    /** 任务编号 */
    private String taskNo;

    /** 服务端基础地址（拼装其他接口用） */
    private String baseUrl;

    /** JMX 脚本内容 MD5（供完整性校验） */
    private String scriptMd5;

    /** JMX 脚本内容（Agent 落盘为 test.jmx） */
    private String jmxContent;

    /** 待下载文件列表（含分发模式与下载地址） */
    private List<AgentTaskFileVO> files;

    /** 该节点 JMeter -J 参数（threads/rampup/duration 按节点均分后） */
    private Object jmeterProps;

    /** JMeter 堆内存上限（MB，可空）：任务级配置，空时 Agent 使用本地默认 */
    private Integer jmeterHeapMb;
}
