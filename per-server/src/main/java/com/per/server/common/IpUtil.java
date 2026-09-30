package com.per.server.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 客户端 IP 获取工具：优先取反向代理透传头（X-Forwarded-For / X-Real-IP），
 * 取不到时回退到请求远端地址，主要用于审计日志记录
 */
public final class IpUtil {

    /** 工具类禁止实例化 */
    private IpUtil() {
    }

    /**
     * 获取当前请求的客户端 IP
     *
     * @return 客户端 IP 字符串，无请求上下文时返回空字符串
     */
    public static String getClientIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "";
        }
        HttpServletRequest request = attrs.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            // 多级代理时取第一个
            int idx = ip.indexOf(',');
            return idx > 0 ? ip.substring(0, idx).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }
}
