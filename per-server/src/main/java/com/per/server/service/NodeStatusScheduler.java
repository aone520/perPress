package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.per.server.entity.Node;
import com.per.server.mapper.NodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 节点状态定时任务：周期扫描心跳超时的在线节点并置为离线
 */
@Component
@RequiredArgsConstructor
public class NodeStatusScheduler {

    /** 心跳超时阈值（秒）：超过该时长未上报心跳即判定离线 */
    private static final int HEARTBEAT_TIMEOUT_SECONDS = 30;

    private final NodeMapper nodeMapper;

    /**
     * 每 15 秒执行一次：将最近心跳时间早于 30 秒前且仍为在线状态的节点置为 OFFLINE
     */
    @Scheduled(fixedDelay = 15_000)
    public void markTimeoutNodesOffline() {
        nodeMapper.update(null, new LambdaUpdateWrapper<Node>()
                .set(Node::getStatus, Node.STATUS_OFFLINE)
                .eq(Node::getStatus, Node.STATUS_ONLINE)
                .lt(Node::getLastHeartbeatTime, LocalDateTime.now().minusSeconds(HEARTBEAT_TIMEOUT_SECONDS)));
    }
}
