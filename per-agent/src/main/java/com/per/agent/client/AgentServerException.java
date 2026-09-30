package com.per.agent.client;

/**
 * 服务端业务异常：统一响应体 {"code":..,"message":..,"data":..} 中 code != 0 时抛出。
 * <p>常见错误码：4011=节点未注册需重新注册，1001=注册 token 无效。</p>
 */
public class AgentServerException extends RuntimeException {

    /** 业务错误码：注册 token 无效 */
    public static final int CODE_TOKEN_INVALID = 1001;

    /** 业务错误码：节点未注册（node_key 失效，需重新注册） */
    public static final int CODE_NODE_NOT_REGISTERED = 4011;

    /** 服务端返回的业务错误码（网络/协议类异常使用负数） */
    private final int code;

    /**
     * 构造服务端业务异常。
     *
     * @param code    业务错误码
     * @param message 错误描述
     */
    public AgentServerException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取服务端业务错误码。
     *
     * @return 业务错误码
     */
    public int getCode() {
        return code;
    }
}
