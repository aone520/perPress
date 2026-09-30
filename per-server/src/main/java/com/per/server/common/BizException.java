package com.per.server.common;

import lombok.Getter;

/**
 * 业务异常类：携带业务错误码，由全局异常处理器统一转换为 R 响应
 */
@Getter
public class BizException extends RuntimeException {

    /** 默认业务错误码 */
    public static final int DEFAULT_CODE = 1000;

    /** 业务错误码 */
    private final int code;

    /**
     * 构造业务异常（默认错误码 1000）
     *
     * @param message 错误描述
     */
    public BizException(String message) {
        this(DEFAULT_CODE, message);
    }

    /**
     * 构造指定错误码的业务异常
     *
     * @param code    业务错误码
     * @param message 错误描述
     */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
