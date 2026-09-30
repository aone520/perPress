package com.per.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.per.server.common.JwtUtil;
import com.per.server.common.LoginUser;
import com.per.server.common.R;
import com.per.server.common.RequireAdmin;
import com.per.server.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：校验请求头 Authorization: Bearer {token}，
 * 解析成功后写入 UserContext，并根据 @RequireAdmin 注解校验管理员角色；
 * /agent/** 不在本拦截器拦截范围内（由 Agent 面自行校验）
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 令牌请求头前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** JWT 工具 */
    private final JwtUtil jwtUtil;

    /** JSON 序列化器（用于向客户端写出认证失败响应） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 请求前置处理：解析并校验 JWT，注入登录上下文，校验管理员注解
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param handler  目标处理器
     * @return true 放行继续执行；false 中断请求
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检请求直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // 非 Controller 方法（静态资源等）放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            writeError(response, 401, "未登录或缺少token");
            return false;
        }
        LoginUser user = jwtUtil.parseToken(auth.substring(BEARER_PREFIX.length()).trim());
        if (user == null) {
            writeError(response, 401, "token无效或已过期");
            return false;
        }
        UserContext.set(user);
        if (!checkAdmin(handlerMethod, user)) {
            writeError(response, 403, "无权限执行该操作");
            return false;
        }
        return true;
    }

    /**
     * 请求完成后清理 ThreadLocal，防止线程复用导致数据串用
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param handler  目标处理器
     * @param ex       处理过程中抛出的异常（可为 null）
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 检查目标方法是否标注 @RequireAdmin 且当前用户是否为管理员
     *
     * @param handlerMethod 目标处理方法
     * @param user          当前登录用户
     * @return true 校验通过（未要求管理员或已具备管理员角色）；false 权限不足
     */
    private boolean checkAdmin(HandlerMethod handlerMethod, LoginUser user) {
        RequireAdmin requireAdmin = handlerMethod.getMethodAnnotation(RequireAdmin.class);
        if (requireAdmin == null) {
            requireAdmin = handlerMethod.getBeanType().getAnnotation(RequireAdmin.class);
        }
        return requireAdmin == null || "ADMIN".equals(user.getRole());
    }

    /**
     * 向客户端写出 JSON 格式的错误响应并结束请求
     *
     * @param response 当前响应
     * @param code     错误码（401 未登录 / 403 无权限）
     * @param message  错误提示
     */
    private void writeError(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.error(code, message)));
    }
}
