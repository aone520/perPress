package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.per.server.common.IpUtil;
import com.per.server.common.LoginUser;
import com.per.server.common.UserContext;
import com.per.server.dto.PageVO;
import com.per.server.entity.AuditLog;
import com.per.server.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 审计日志服务：负责记录与查询平台关键操作审计日志
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 记录一条审计日志：操作人与 IP 自动从当前请求上下文获取
     *
     * @param action 操作类型（简短英文，如 LOGIN / DELETE_NODE）
     * @param detail 操作描述（中文）
     */
    public void record(String action, String detail) {
        LoginUser current = UserContext.get();
        AuditLog log = new AuditLog();
        if (current != null) {
            log.setUserId(current.getUid());
            log.setUsername(current.getUsername());
        }
        log.setAction(action);
        log.setDetail(detail);
        log.setIp(IpUtil.getClientIp());
        auditLogMapper.insert(log);
    }

    /**
     * 分页查询审计日志（按 id 倒序）
     *
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     * @param keyword 关键词，模糊匹配 username / action
     * @return 审计日志分页结果
     */
    public PageVO<AuditLog> page(long page, long size, String keyword) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(AuditLog::getUsername, keyword)
                    .or().like(AuditLog::getAction, keyword));
        }
        wrapper.orderByDesc(AuditLog::getId);
        Page<AuditLog> result = auditLogMapper.selectPage(new Page<>(page, size), wrapper);
        return PageVO.of(result.getRecords(), result.getTotal());
    }
}
