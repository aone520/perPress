package com.per.server.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent 节点注册请求 DTO
 */
@Data
public class AgentRegisterRequest {

    /** 平台下发的注册 token */
    @NotBlank(message = "token不能为空")
    private String token;

    /** 主机名 */
    @NotBlank(message = "hostname不能为空")
    private String hostname;

    /** IP 地址 */
    private String ip;

    /** 操作系统 */
    private String os;

    /** JVM 版本 */
    private String jvmVersion;

    /** Agent 版本 */
    private String agentVersion;
}
