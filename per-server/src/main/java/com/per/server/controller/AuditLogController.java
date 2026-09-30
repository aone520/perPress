package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.common.RequireAdmin;
import com.per.server.dto.PageVO;
import com.per.server.entity.AuditLog;
import com.per.server.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志接口：审计日志分页查询（仅管理员）
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    /**
     * 分页查询审计日志
     *
     * @param page    页码，默认 1
     * @param size    每页条数，默认 10
     * @param keyword 关键词（模糊匹配 username/action）
     * @return 审计日志分页数据
     */
    @GetMapping
    @RequireAdmin
    public R<PageVO<AuditLog>> page(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) String keyword) {
        return R.ok(auditService.page(page, size, keyword));
    }
}
