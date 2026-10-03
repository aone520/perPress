package com.per.agent.client;

/**
 * 心跳请求体：{nodeKey, cpuUsage, memUsage, memTotal, jvmMemUsed, jvmMemMax,
 * netRecvBps, netSentBps, engineVersion, agentVersion}。
 *
 * @param nodeKey       节点密钥
 * @param cpuUsage      CPU 使用率（0-100）
 * @param memUsage      操作系统内存使用率（0-100）
 * @param memTotal      操作系统内存总量（字节）
 * @param jvmMemUsed    JVM 堆已用（字节）
 * @param jvmMemMax     JVM 堆最大（字节）
 * @param netRecvBps    网络接收速率（字节/秒，物理网卡差分）
 * @param netSentBps    网络发送速率（字节/秒，物理网卡差分）
 * @param engineVersion 当前已部署引擎版本（未部署为 null）
 */
public record HeartbeatRequest(
        String nodeKey,
        double cpuUsage,
        double memUsage,
        long memTotal,
        long jvmMemUsed,
        long jvmMemMax,
        double netRecvBps,
        double netSentBps,
        String engineVersion,
        String agentVersion) {
}
