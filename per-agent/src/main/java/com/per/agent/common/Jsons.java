package com.per.agent.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON 工具类：提供全局共享的 Jackson ObjectMapper 及序列化/反序列化快捷方法。
 */
public final class Jsons {

    /** 全局共享 ObjectMapper：忽略未知字段，保证服务端契约演进时的兼容性 */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    /**
     * 私有构造器：工具类禁止实例化。
     */
    private Jsons() {
    }

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param value 待序列化对象
     * @return JSON 字符串
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败: " + value, e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 反序列化对象
     */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 反序列化失败: " + json, e);
        }
    }
}
