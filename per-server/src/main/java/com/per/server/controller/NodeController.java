package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.common.RequireAdmin;
import com.per.server.dto.LabelsUpdateRequest;
import com.per.server.dto.NodeVO;
import com.per.server.dto.PageVO;
import com.per.server.service.NodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节点管理接口：节点分页查询、标签维护、删除、注册 token 管理与安装命令生成
 */
@RestController
@RequestMapping("/api/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final NodeService nodeService;

    /**
     * 分页查询节点列表
     *
     * @param page    页码，默认 1
     * @param size    每页条数，默认 10
     * @param keyword 关键词（模糊匹配 hostname/ip/nodeKey）
     * @param status  状态精确过滤（ONLINE/OFFLINE）
     * @param label   标签精确过滤
     * @return 节点分页数据（labels 已拆为数组）
     */
    @GetMapping
    public R<PageVO<NodeVO>> page(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String label) {
        return R.ok(nodeService.page(page, size, keyword, status, label));
    }

    /**
     * 更新节点标签
     *
     * @param id      节点ID
     * @param request 标签列表请求
     * @return 空数据成功响应
     */
    @PutMapping("/{id}/labels")
    public R<Void> updateLabels(@PathVariable Long id, @RequestBody @Valid LabelsUpdateRequest request) {
        nodeService.updateLabels(id, request.getLabels());
        return R.ok();
    }

    /**
     * 删除节点（仅管理员）
     *
     * @param id 节点ID
     * @return 空数据成功响应
     */
    @DeleteMapping("/{id}")
    @RequireAdmin
    public R<Void> delete(@PathVariable Long id) {
        nodeService.delete(id);
        return R.ok();
    }

    /**
     * 获取当前节点注册 token（仅管理员）
     *
     * @return 注册 token 字符串
     */
    @GetMapping("/register-token")
    @RequireAdmin
    public R<String> registerToken() {
        return R.ok(nodeService.getRegisterToken());
    }

    /**
     * 重置节点注册 token（仅管理员）
     *
     * @return 新生成的注册 token
     */
    @PostMapping("/register-token/reset")
    @RequireAdmin
    public R<String> resetRegisterToken() {
        return R.ok(nodeService.resetRegisterToken());
    }

    /**
     * 获取节点一键安装命令
     *
     * @return 安装命令字符串
     */
    @GetMapping("/install-command")
    public R<String> installCommand() {
        return R.ok(nodeService.installCommand());
    }
}
