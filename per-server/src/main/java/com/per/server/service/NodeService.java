package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.per.server.common.BizException;
import com.per.server.common.TokenUtil;
import com.per.server.dto.NodeVO;
import com.per.server.dto.PageVO;
import com.per.server.entity.Node;
import com.per.server.entity.SysConfig;
import com.per.server.entity.TaskNode;
import com.per.server.entity.TestTask;
import com.per.server.mapper.NodeMapper;
import com.per.server.mapper.SysConfigMapper;
import com.per.server.mapper.TaskNodeMapper;
import com.per.server.mapper.TestTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 节点管理服务：节点分页查询、标签维护、删除（含吊销）与注册 token 管理、安装命令生成
 */
@Service
@RequiredArgsConstructor
public class NodeService {

    /** 系统配置键：节点注册 token */
    private static final String CONFIG_REGISTER_TOKEN = "register.token";

    /** 系统配置键：已吊销节点 node_key 列表（逗号分隔），删除节点时写入，心跳命中返回 4090 令 Agent 自停 */
    private static final String CONFIG_REVOKED_NODES = "node.revoked";

    /** 心跳返回码：节点已被管理员删除（Agent 收到后自动停止服务） */
    public static final int CODE_NODE_REVOKED = 4090;

    private final NodeMapper nodeMapper;
    private final SysConfigMapper sysConfigMapper;
    private final AuditService auditService;
    private final TaskNodeMapper taskNodeMapper;
    private final TestTaskMapper taskMapper;

    /** 平台基础地址（用于生成一键安装命令，来自配置 per.server.base-url） */
    @Value("${per.server.base-url}")
    private String baseUrl;

    /**
     * 分页查询节点列表（按 id 倒序）
     *
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     * @param keyword 关键词，模糊匹配 hostname / ip / nodeKey
     * @param status  节点状态精确过滤（ONLINE/OFFLINE）
     * @param label   标签精确过滤（匹配 labels 逗号分隔中的单项）
     * @return 节点分页结果（labels 已拆为数组）
     */
    public PageVO<NodeVO> page(long page, long size, String keyword, String status, String label) {
        LambdaQueryWrapper<Node> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(Node::getHostname, keyword)
                    .or().like(Node::getIp, keyword)
                    .or().like(Node::getNodeKey, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Node::getStatus, status);
        }
        if (StringUtils.hasText(label)) {
            // labels 为逗号分隔存储，使用 FIND_IN_SET 精确匹配单项标签
            wrapper.apply("FIND_IN_SET({0}, labels) > 0", label);
        }
        wrapper.orderByDesc(Node::getId);
        Page<Node> result = nodeMapper.selectPage(new Page<>(page, size), wrapper);
        List<NodeVO> records = result.getRecords().stream().map(this::toVO).toList();
        return PageVO.of(records, result.getTotal());
    }

    /**
     * 更新节点标签：传入列表去空、去重后保存为逗号分隔字符串
     *
     * @param id     节点ID
     * @param labels 标签列表
     */
    public void updateLabels(Long id, List<String> labels) {
        Node node = requireNode(id);
        List<String> cleaned = labels == null ? List.of() : labels.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        Node update = new Node();
        update.setId(node.getId());
        update.setLabels(String.join(",", cleaned));
        nodeMapper.updateById(update);
    }

    /**
     * 删除节点：先校验无执行中的任务引用（任务状态 PREPARING/RUNNING/STOPPING），
     * 再吊销 node_key（该机 Agent 心跳时收到 4090 自动停止服务），
     * 最后删除节点记录并记录审计日志。
     * CREATED（未启动）任务的 PENDING 节点引用不阻止删除——删除后任务启动时由
     * 在线校验兜底拦截；终态任务的残留节点状态同样不阻止
     *
     * @param id 节点ID
     */
    public void delete(Long id) {
        Node node = requireNode(id);
        // 校验：该节点被「执行中」的任务引用时禁止删除（先按节点明细状态粗筛，再按任务状态精筛）
        List<TaskNode> refs = taskNodeMapper.selectList(new LambdaQueryWrapper<TaskNode>()
                .eq(TaskNode::getNodeKey, node.getNodeKey())
                .in(TaskNode::getStatus,
                        TaskNode.STATUS_PENDING, TaskNode.STATUS_DOWNLOADING,
                        TaskNode.STATUS_READY, TaskNode.STATUS_RUNNING)
                .select(TaskNode::getTaskId));
        if (!refs.isEmpty()) {
            List<Long> taskIds = refs.stream().map(TaskNode::getTaskId).distinct().toList();
            Long activeTasks = taskMapper.selectCount(new LambdaQueryWrapper<TestTask>()
                    .in(TestTask::getId, taskIds)
                    .in(TestTask::getStatus,
                            TestTask.STATUS_PREPARING, TestTask.STATUS_RUNNING, TestTask.STATUS_STOPPING));
            if (activeTasks != null && activeTasks > 0) {
                throw new BizException("该节点正在参与执行中的压测任务（共 " + activeTasks
                        + " 个），请先停止/等任务结束后再删除节点");
            }
        }
        revokeNodeKey(node.getNodeKey());
        nodeMapper.deleteById(id);
        auditService.record("DELETE_NODE", "删除节点 " + node.getHostname() + "(" + node.getIp()
                + ")，该机 Agent 将自动停止");
    }

    /**
     * 判断节点是否已被吊销（管理员删除）：Agent 心跳/轮询前置校验使用
     *
     * @param nodeKey 节点唯一标识
     * @return true 表示已吊销（已被删除）
     */
    public boolean isRevoked(String nodeKey) {
        return revokedKeys().contains(nodeKey);
    }

    /**
     * 将 node_key 追加到吊销列表（sys_config 键 node.revoked，逗号分隔，幂等）
     *
     * @param nodeKey 被删除节点的唯一标识
     */
    private void revokeNodeKey(String nodeKey) {
        List<String> keys = revokedKeys();
        if (keys.contains(nodeKey)) {
            return;
        }
        keys.add(nodeKey);
        saveRevokedKeys(keys);
    }

    /**
     * 读取吊销节点列表（空值安全）
     *
     * @return 吊销的 node_key 集合
     */
    private List<String> revokedKeys() {
        SysConfig config = sysConfigMapper.selectById(CONFIG_REVOKED_NODES);
        List<String> keys = new ArrayList<>();
        if (config != null && StringUtils.hasText(config.getConfigValue())) {
            Arrays.stream(config.getConfigValue().split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .forEach(keys::add);
        }
        return keys;
    }

    /**
     * 保存吊销节点列表（upsert：先 update，无记录则 insert）
     *
     * @param keys 吊销的 node_key 集合
     */
    private void saveRevokedKeys(List<String> keys) {
        String value = String.join(",", keys);
        SysConfig config = new SysConfig();
        config.setConfigKey(CONFIG_REVOKED_NODES);
        config.setConfigValue(value);
        if (sysConfigMapper.updateById(config) <= 0) {
            sysConfigMapper.insert(config);
        }
    }

    /**
     * 获取当前节点注册 token（读取 sys_config；异常缺失时自动生成兜底）
     *
     * @return 32 位随机 hex 注册 token
     */
    public String getRegisterToken() {
        SysConfig config = sysConfigMapper.selectById(CONFIG_REGISTER_TOKEN);
        if (config != null && StringUtils.hasText(config.getConfigValue())) {
            return config.getConfigValue();
        }
        return saveRegisterToken();
    }

    /**
     * 重置节点注册 token：重新生成并覆盖保存，记录审计日志
     *
     * @return 新的 32 位随机 hex 注册 token
     */
    public String resetRegisterToken() {
        String token = saveRegisterToken();
        auditService.record("RESET_REGISTER_TOKEN", "重置节点注册token");
        return token;
    }

    /**
     * 生成节点一键安装命令：curl 拉取平台动态生成的 install.sh（内嵌 server 与 token 参数）后 sudo 执行
     *
     * @return 安装命令字符串
     */
    public String installCommand() {
        return String.format("curl -fsSL \"%s/agent/install.sh?server=%s&token=%s\" | sudo bash",
                baseUrl, baseUrl, getRegisterToken());
    }

    /**
     * 校验节点存在，不存在抛业务异常
     *
     * @param id 节点ID
     * @return 节点实体
     */
    private Node requireNode(Long id) {
        Node node = nodeMapper.selectById(id);
        if (node == null) {
            throw new BizException("节点不存在");
        }
        return node;
    }

    /**
     * 生成新的注册 token 并保存到 sys_config（存在则覆盖）
     *
     * @return 新生成的 token
     */
    private String saveRegisterToken() {
        String token = TokenUtil.randomHex(32);
        SysConfig config = new SysConfig();
        config.setConfigKey(CONFIG_REGISTER_TOKEN);
        config.setConfigValue(token);
        if (sysConfigMapper.selectById(CONFIG_REGISTER_TOKEN) == null) {
            sysConfigMapper.insert(config);
        } else {
            sysConfigMapper.updateById(config);
        }
        return token;
    }

    /**
     * 节点实体转视图对象（labels 逗号分隔字符串拆为数组）
     *
     * @param node 节点实体
     * @return 节点视图对象
     */
    private NodeVO toVO(Node node) {
        NodeVO vo = new NodeVO();
        vo.setId(node.getId());
        vo.setNodeKey(node.getNodeKey());
        vo.setHostname(node.getHostname());
        vo.setIp(node.getIp());
        vo.setOs(node.getOs());
        vo.setJvmVersion(node.getJvmVersion());
        vo.setAgentVersion(node.getAgentVersion());
        vo.setEngineVersion(node.getEngineVersion());
        vo.setLabels(StringUtils.hasText(node.getLabels())
                ? Arrays.stream(node.getLabels().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList()
                : List.of());
        vo.setStatus(node.getStatus());
        vo.setCpuUsage(node.getCpuUsage());
        vo.setMemUsage(node.getMemUsage());
        vo.setMemTotal(node.getMemTotal());
        vo.setJvmMemUsed(node.getJvmMemUsed());
        vo.setJvmMemMax(node.getJvmMemMax());
        vo.setNetRecvBps(node.getNetRecvBps());
        vo.setNetSentBps(node.getNetSentBps());
        vo.setLastHeartbeatTime(node.getLastHeartbeatTime());
        vo.setCreateTime(node.getCreateTime());
        return vo;
    }
}
