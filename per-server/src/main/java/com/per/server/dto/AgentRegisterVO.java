package com.per.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Agent 注册响应 DTO：返回节点标识与 JMeter 引擎信息
 */
@Data
@AllArgsConstructor
public class AgentRegisterVO {

    /** 节点唯一标识（后续心跳与轮询的凭证） */
    private String nodeKey;

    /** JMeter 引擎信息（M1 预留为空） */
    private EngineVO engine;
}
