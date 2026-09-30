package com.per.agent.client;

/**
 * 注册请求体：{token, hostname, ip, os, jvmVersion, agentVersion}。
 *
 * @param token       注册令牌
 * @param hostname    主机名
 * @param ip          本机 IP
 * @param os          操作系统描述
 * @param jvmVersion  JVM 版本
 * @param agentVersion Agent 版本
 */
public record RegisterRequest(
        String token,
        String hostname,
        String ip,
        String os,
        String jvmVersion,
        String agentVersion) {
}
