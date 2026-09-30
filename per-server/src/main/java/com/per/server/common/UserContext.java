package com.per.server.common;

/**
 * 登录用户上下文：基于 ThreadLocal 保存当前请求的登录用户信息，
 * 由认证拦截器在请求开始时写入、请求结束后清理
 */
public final class UserContext {

    /** 线程级登录用户持有器 */
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 工具类禁止实例化 */
    private UserContext() {
    }

    /**
     * 设置当前线程的登录用户
     *
     * @param user 登录用户信息
     */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /**
     * 获取当前线程的登录用户
     *
     * @return 登录用户信息，未登录时返回 null
     */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 清理当前线程的登录用户（防止线程复用导致的数据串用）
     */
    public static void clear() {
        HOLDER.remove();
    }
}
