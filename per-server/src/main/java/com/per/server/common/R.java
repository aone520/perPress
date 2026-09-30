package com.per.server.common;

import lombok.Data;

/**
 * 统一响应包装类
 * 约定：code=0 表示成功；code=1000 表示业务错误；code=401 表示未登录；code=403 表示无权限；
 * Agent 面另有专用错误码（如 1001 注册token无效、4011 节点未注册）
 *
 * @param <T> 响应数据类型
 */
@Data
public class R<T> {

    /** 响应码：0 成功，非 0 失败 */
    private int code;

    /** 响应提示信息 */
    private String message;

    /** 响应数据 */
    private T data;

    /**
     * 构造成功响应（无数据）
     *
     * @param <T> 响应数据类型
     * @return code=0 的统一响应对象
     */
    public static <T> R<T> ok() {
        return ok(null);
    }

    /**
     * 构造成功响应
     *
     * @param <T> 响应数据类型
     * @param data 响应数据
     * @return code=0 的统一响应对象
     */
    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    /**
     * 构造业务错误响应（默认错误码 1000）
     *
     * @param <T> 响应数据类型
     * @param message 错误提示信息
     * @return code=1000 的统一响应对象
     */
    public static <T> R<T> error(String message) {
        return error(BizException.DEFAULT_CODE, message);
    }

    /**
     * 构造指定错误码的错误响应
     *
     * @param <T> 响应数据类型
     * @param code    错误码
     * @param message 错误提示信息
     * @return 统一响应对象
     */
    public static <T> R<T> error(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
