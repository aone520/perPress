package com.per.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per Agent 配置项（前缀 per.agent），映射 application.yml 中的配置。
 */
@Data
@ConfigurationProperties(prefix = "per.agent")
public class AgentProperties {

    /** 管理服务端地址 */
    private String serverUrl = "http://localhost:8080";

    /** 注册令牌 */
    private String registerToken = "CHANGE_ME";

    /** 心跳上报间隔（秒） */
    private long heartbeatIntervalSeconds = 10;

    /** 任务指令轮询间隔（秒） */
    private long pollIntervalSeconds = 2;

    /** 心跳失联自停阈值（秒），达到后停止所有运行中的压测任务 */
    private long heartbeatLostStopSeconds = 60;

    /** 任务工作线程数（PREPARE/START 指令异步执行，与调度线程隔离） */
    private int taskWorkerThreads = 1;

    /** 文件下载请求超时（秒）：引擎包/脚本/附件下载（作用于响应头到达，正文流式不受限） */
    private long downloadTimeoutSeconds = 60;

    /** 文件下载连接超时（秒） */
    private long downloadConnectTimeoutSeconds = 5;

    /** 本地工作目录：node_key 持久化、引擎目录 engine/、任务目录 tasks/ */
    private String dataDir = "./agent-data";

    /** JMeter 堆内存上限（MB）：任务未指定 jmeterHeapMb 时使用的本地默认值 */
    private int jmeterHeapMb = 2048;

    /** 指标上报聚合窗口（毫秒，最小 1000）：默认 3 秒，兼顾实时性与聚合开销 */
    private long metricsWindowMs = 3000;

    /** 已结束任务工作目录保留天数 */
    private int taskRetentionDays = 7;
}
