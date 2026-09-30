package com.per.server.dto;

import lombok.Data;

import java.util.List;

/**
 * 脚本调试结果 VO：每个接口一条 Item（请求/响应/断言/提取明细），用于编辑页调试抽屉展示
 */
@Data
public class ScriptDebugVO {

    /** 逐接口调试明细（按分组顺序） */
    private List<Item> items;

    /**
     * 单接口调试结果
     */
    @Data
    public static class Item {

        /** 分组序号（0 基） */
        private Integer groupIndex;

        /** 分组名称 */
        private String groupName;

        /** 分组执行方式：SERIAL/PARALLEL */
        private String execution;

        /** 组内接口序号（0 基） */
        private Integer samplerIndex;

        /** 接口名称 */
        private String name;

        /** 请求方法 */
        private String method;

        /** 实际请求 URL（变量替换 + 全局环境拼接后） */
        private String url;

        /** 实际发送的请求头 */
        private List<Kv> requestHeaders;

        /** 实际发送的请求体 */
        private String requestBody;

        /** 响应状态码（请求异常时为 null） */
        private Integer statusCode;

        /** 响应头 */
        private List<Kv> responseHeaders;

        /** 响应体（截断至 64KB） */
        private String responseBody;

        /** 请求耗时（毫秒，请求异常时为 null） */
        private Long elapsedMs;

        /** 请求是否成功（状态码 < 400 且无连接异常） */
        private boolean success;

        /** 综合结论：请求成功且断言全部通过（无断言时等同 success） */
        private boolean ok;

        /** 异常信息（连接失败/URL 非法等，正常为 null） */
        private String error;

        /** 断言执行结果 */
        private List<AssertionResult> assertions;

        /** 提取器执行结果 */
        private List<ExtractorResult> extractors;
    }

    /**
     * 断言执行结果
     */
    @Data
    public static class AssertionResult {

        /** 断言类型：TEXT/CODE */
        private String type;

        /** 期望值 */
        private String expect;

        /** 是否通过 */
        private boolean passed;
    }

    /**
     * 提取器执行结果
     */
    @Data
    public static class ExtractorResult {

        /** 引用名 */
        private String refName;

        /** 是否命中 */
        private boolean hit;

        /** 提取到的值（未命中时为默认值） */
        private String value;
    }

    /**
     * 头部键值对
     */
    @Data
    public static class Kv {

        /** 头名称 */
        private String k;

        /** 头值 */
        private String v;

        /**
         * 构造头部键值对
         *
         * @param k 头名称
         * @param v 头值
         */
        public Kv(String k, String v) {
            this.k = k;
            this.v = v;
        }
    }
}
