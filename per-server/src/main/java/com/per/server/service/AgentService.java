package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.per.server.common.BizException;
import com.per.server.dto.AgentHeartbeatRequest;
import com.per.server.dto.AgentPollVO;
import com.per.server.dto.AgentRegisterRequest;
import com.per.server.dto.AgentRegisterVO;
import com.per.server.dto.HeartbeatVO;
import com.per.server.entity.Node;
import com.per.server.entity.NodeResourceSample;
import com.per.server.entity.SysConfig;
import com.per.server.entity.TaskNode;
import com.per.server.mapper.NodeMapper;
import com.per.server.mapper.NodeResourceSampleMapper;
import com.per.server.mapper.SysConfigMapper;
import com.per.server.mapper.TaskNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agent 端服务：处理节点注册、心跳上报与命令轮询（/agent/** 免 JWT，由本服务自行校验凭证）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** 系统配置键：节点注册 token */
    private static final String CONFIG_REGISTER_TOKEN = "register.token";

    private final NodeMapper nodeMapper;
    private final SysConfigMapper sysConfigMapper;
    private final EnginePackageService enginePackageService;
    private final TaskOrchestrator taskOrchestrator;
    private final NodeService nodeService;
    private final TaskNodeMapper taskNodeMapper;
    private final NodeResourceSampleMapper resourceSampleMapper;

    /**
     * Agent 节点注册：校验注册 token；同 hostname+ip 的节点已存在则复用其 node_key 并更新信息，
     * 否则生成 UUID 作为 node_key 插入新节点；注册后节点置为在线并记录心跳时间；
     * 响应附带当前发布引擎信息（未发布任何引擎时为 null）
     *
     * @param request 注册请求（token + 主机信息）
     * @return 节点标识与 JMeter 引擎信息
     */
    public AgentRegisterVO register(AgentRegisterRequest request) {
        SysConfig config = sysConfigMapper.selectById(CONFIG_REGISTER_TOKEN);
        String savedToken = config == null ? null : config.getConfigValue();
        if (!StringUtils.hasText(savedToken) || !savedToken.equals(request.getToken())) {
            throw new BizException(1001, "注册token无效");
        }
        Node node = nodeMapper.selectOne(new LambdaQueryWrapper<Node>()
                .eq(Node::getHostname, request.getHostname())
                .eq(Node::getIp, request.getIp())
                .last("LIMIT 1"));
        if (node != null) {
            // 复用已存在节点的 node_key，刷新基础信息
            Node update = new Node();
            update.setId(node.getId());
            update.setOs(request.getOs());
            update.setJvmVersion(request.getJvmVersion());
            update.setAgentVersion(request.getAgentVersion());
            update.setStatus(Node.STATUS_ONLINE);
            update.setLastHeartbeatTime(LocalDateTime.now());
            nodeMapper.updateById(update);
        } else {
            node = new Node();
            node.setNodeKey(UUID.randomUUID().toString());
            node.setHostname(request.getHostname());
            node.setIp(request.getIp());
            node.setOs(request.getOs());
            node.setJvmVersion(request.getJvmVersion());
            node.setAgentVersion(request.getAgentVersion());
            node.setStatus(Node.STATUS_ONLINE);
            node.setLastHeartbeatTime(LocalDateTime.now());
            nodeMapper.insert(node);
        }
        return new AgentRegisterVO(node.getNodeKey(), enginePackageService.currentEngine());
    }

    /**
     * 心跳上报：校验 node_key，更新节点资源字段、引擎版本、在线状态与最近心跳时间；
     * 响应附带当前发布引擎信息（未发布任何引擎时为 null）
     *
     * @param request 心跳请求（nodeKey + 资源指标）
     * @return 服务端当前时间戳与引擎信息
     */
    public HeartbeatVO heartbeat(AgentHeartbeatRequest request) {
        // 吊销校验：节点被管理员删除后，返回 4090 令该机 Agent 自动停止服务
        if (nodeService.isRevoked(request.getNodeKey())) {
            throw new BizException(NodeService.CODE_NODE_REVOKED,
                    "节点已被管理员移除，Agent 将自动停止；如需恢复请重新执行安装命令");
        }
        Node node = requireNodeByKey(request.getNodeKey());
        Node update = new Node();
        update.setId(node.getId());
        update.setCpuUsage(request.getCpuUsage());
        update.setMemUsage(request.getMemUsage());
        update.setMemTotal(request.getMemTotal());
        update.setJvmMemUsed(request.getJvmMemUsed());
        update.setJvmMemMax(request.getJvmMemMax());
        update.setNetRecvBps(request.getNetRecvBps());
        update.setNetSentBps(request.getNetSentBps());
        if (StringUtils.hasText(request.getEngineVersion())) {
            update.setEngineVersion(request.getEngineVersion());
        }
        if (StringUtils.hasText(request.getAgentVersion())) {
            update.setAgentVersion(request.getAgentVersion());
        }
        update.setStatus(Node.STATUS_ONLINE);
        update.setLastHeartbeatTime(LocalDateTime.now());
        nodeMapper.updateById(update);
        // 任务运行期间采样压力机资源：复用现有低频心跳通道落库（不触碰压测指标链路），
        // 报告据此绘制各节点 CPU%/MEM% 曲线；节点无运行中任务时不写库
        recordResourceSamples(request);
        return new HeartbeatVO(System.currentTimeMillis(), enginePackageService.currentEngine());
    }

    /**
     * 记录压力机资源采样：查该节点处于 RUNNING 状态的任务关联，逐任务插入一条资源快照。
     * 启动阶段已执行节点独占校验，正常情况下只会命中一个活动任务；这里保留逐条处理，
     * 用于兼容升级前遗留数据或极端竞态，采样异常不影响心跳主流程。
     *
     * @param request 心跳请求（含 CPU/内存指标）
     */
    private void recordResourceSamples(AgentHeartbeatRequest request) {
        try {
            List<TaskNode> runningNodes = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                    .eq(TaskNode::getNodeKey, request.getNodeKey())
                    .eq(TaskNode::getStatus, TaskNode.STATUS_RUNNING));
            for (TaskNode taskNode : runningNodes) {
                NodeResourceSample sample = new NodeResourceSample();
                sample.setTaskId(taskNode.getTaskId());
                sample.setNodeKey(request.getNodeKey());
                sample.setCpuUsage(request.getCpuUsage());
                sample.setMemUsage(request.getMemUsage());
                sample.setMemTotal(request.getMemTotal());
                sample.setJvmMemUsed(request.getJvmMemUsed());
                sample.setJvmMemMax(request.getJvmMemMax());
                sample.setNetRecvBps(request.getNetRecvBps());
                sample.setNetSentBps(request.getNetSentBps());
                resourceSampleMapper.insert(sample);
            }
        } catch (Exception e) {
            log.warn("压力机资源采样落库失败（nodeKey={}）：{}", request.getNodeKey(), e.getMessage());
        }
    }

    /**
     * 命令轮询：校验 node_key 后委托任务编排服务决策待执行命令（PREPARE/START/STOP，无则为空）
     *
     * @param nodeKey 节点唯一标识
     * @return 轮询结果（command/task，无命令时均为 null）
     */
    public AgentPollVO poll(String nodeKey) {
        // 吊销校验：与心跳一致，被删除节点返回 4090（Agent 主要经心跳感知并自停）
        if (nodeService.isRevoked(nodeKey)) {
            throw new BizException(NodeService.CODE_NODE_REVOKED,
                    "节点已被管理员移除，Agent 将自动停止；如需恢复请重新执行安装命令");
        }
        requireNodeByKey(nodeKey);
        return taskOrchestrator.pollCommand(nodeKey);
    }

    /**
     * 根据 nodeKey 校验节点存在，不存在抛业务异常（错误码 4011）
     *
     * @param nodeKey 节点唯一标识
     * @return 节点实体
     */
    private Node requireNodeByKey(String nodeKey) {
        Node node = nodeMapper.selectOne(new LambdaQueryWrapper<Node>()
                .eq(Node::getNodeKey, nodeKey));
        if (node == null) {
            throw new BizException(4011, "节点未注册");
        }
        return node;
    }
}
