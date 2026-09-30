package com.per.agent.common;

/**
 * Agent 版本号常量：随功能迭代递增。
 * 注册与心跳均携带，平台节点列表可见，用于识别待升级机器
 * （分发包升级后重启，节点列表的 Agent 版本列应变为新版本号）。
 */
public final class AgentVersion {

    /** 当前 Agent 版本 */
    public static final String VERSION = "1.0.1";

    private AgentVersion() {
    }
}
