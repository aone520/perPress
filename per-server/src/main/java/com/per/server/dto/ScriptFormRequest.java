package com.per.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 表单生成脚本请求 DTO：threadGroupName + samplers + thinkTimeMs 渲染为标准 JMX
 */
@Data
public class ScriptFormRequest {

    /** 脚本名称 */
    @NotBlank(message = "name不能为空")
    private String name;

    /** 脚本描述 */
    private String description;

    /** 表单场景定义 */
    @NotNull(message = "formDef不能为空")
    @Valid
    private FormDef formDef;

    /**
     * 表单场景定义：全局配置 + 线程组 + 采样器集合 + 思考时间；
     * groups 存在时以其为编排结构（组内串行/并行），无 groups 时 samplers 视为单串行组（旧数据兼容）
     */
    @Data
    public static class FormDef {

        /** 全局配置（目标环境 + 自定义变量），旧数据无此字段为 null，行为不变 */
        @Valid
        private Config config;

        /** 线程组名称 */
        private String threadGroupName;

        /** HTTP 采样器列表（旧结构字段；groups 存在时被忽略） */
        @Valid
        private List<Sampler> samplers;

        /** 思考时间（毫秒），大于 0 时渲染 ConstantTimer */
        private Long thinkTimeMs;

        /** 接口编排分组列表（新结构）：SERIAL 组内串行、PARALLEL 组内接口各自独立并行 */
        @Valid
        private List<Group> groups;
    }

    /**
     * 编排分组：execution 决定组内接口的执行关系
     */
    @Data
    public static class Group {

        /** 组名称（展示与任务占比单元命名用） */
        private String name;

        /** 执行方式：SERIAL 组内串行（组渲染 1 个线程组）/ PARALLEL 组内接口彼此并行（每接口 1 个线程组） */
        private String execution;

        /** 组内 HTTP 采样器列表 */
        @NotEmpty(message = "组内samplers不能为空")
        @Valid
        private List<Sampler> samplers;
    }

    /**
     * HTTP 采样器定义
     */
    @Data
    public static class Sampler {

        /** 采样器名称 */
        private String name;

        /** 请求方法：GET/POST/PUT/DELETE 等 */
        private String method;

        /** 完整请求 URL（http://host:port/path?a=b） */
        @NotBlank(message = "url不能为空")
        private String url;

        /** 自定义请求头 */
        private List<Header> headers;

        /** 请求体（POST/PUT 原始文本） */
        private String body;

        /** 响应断言列表 */
        private List<Assertion> assertions;

        /** 参数提取器列表：从本接口的响应/请求信息提取变量，供串行组内后续接口以 ${refName} 引用 */
        private List<Extractor> extractors;

        /** 引用的 CSV 数据文件列表 */
        private List<CsvRef> csvRefs;

        /**
         * 接口流量占比（%）：模拟串行链路的业务漏斗（如登录100→查价100→下单60→支付30），
         * 仅 FIXED_TPS 模式且 SERIAL 组内生效，渲染为 JMeter ThroughputController（百分比模式）；
         * 为空或 100 表示全量执行（不渲染控制器，结构与旧版一致）；各接口比例独立判定
         */
        private Double trafficPercent;
    }

    /**
     * 自定义请求头
     */
    @Data
    public static class Header {

        /** 头名称 */
        private String k;

        /** 头值 */
        private String v;
    }

    /**
     * 响应断言：TEXT 匹配响应文本包含 / CODE 匹配响应码相等
     */
    @Data
    public static class Assertion {

        /** 断言类型：TEXT/CODE */
        private String type;

        /** 期望值 */
        private String expect;
    }

    /**
     * 参数提取器：从采样器的响应/请求信息中提取值存入 JMeter 变量（refName），
     * 串行组内后续采样器以 ${refName} 引用；仅 SERIAL 组内有效（并行组各接口独立线程，变量不互通）
     */
    @Data
    public static class Extractor {

        /** 提取类型：REGEX 正则提取器 / JSON JSONPath 提取器 / BOUNDARY 边界提取器 */
        private String type;

        /** 引用名（变量名），后续接口以 ${refName} 使用 */
        private String refName;

        /** 提取表达式：REGEX 为正则（用分组+模板取值）；JSON 为 JSONPath（如 $.data.token）；BOUNDARY 为左边界 */
        private String expression;

        /** 右边界（仅 BOUNDARY 类型：左右边界之间为提取值） */
        private String rightBoundary;

        /** 提取来源：RESPONSE_BODY 响应体（默认）/ RESPONSE_HEADERS 响应头 / REQUEST_HEADERS 请求头 / URL / RESPONSE_CODE 响应码 / RESPONSE_MESSAGE 响应消息 */
        private String source;

        /** REGEX 模板（默认 $1$ 取第一个分组） */
        private String template;

        /** 匹配序号（默认 1 取第一个匹配；0 随机；-1 全部存入 refName_N） */
        private Integer matchNumber;

        /** 未提取到时的默认值（建议非空，便于断言定位） */
        private String defaultValue;
    }

    /**
     * 全局配置：统一目标环境（协议/域名/端口）+ 自定义变量；
     * 采样器 url 为相对路径（无 scheme）时用环境补全为绝对地址，换环境只需改一处；
     * 自定义变量注入 JMX TestPlan 的 User Defined Variables，接口任意位置以 ${name} 引用
     */
    @Data
    public static class Config {

        /** 全局协议：http/https（默认 http） */
        private String protocol;

        /** 全局域名（host，可含端口外的主机名/IP） */
        private String host;

        /** 全局端口（可空 = 协议默认端口不渲染） */
        private Integer port;

        /** 自定义变量列表（注入 TestPlan User Defined Variables） */
        @Valid
        private List<Variable> variables;
    }

    /**
     * 自定义变量：name/value 键值对，渲染为 TestPlan UDV 条目
     */
    @Data
    public static class Variable {

        /** 变量名（接口中以 ${name} 引用） */
        private String name;

        /** 变量值（支持 ${otherVar} 引用其它变量） */
        private String value;
    }

    /**
     * CSV 数据文件引用（渲染为 CSVDataSet）
     */
    @Data
    public static class CsvRef {

        /** 数据文件ID */
        @NotNull(message = "fileId不能为空")
        private Long fileId;

        /** 变量名列表（逗号分隔） */
        private String varNames;

        /** 分隔符（默认逗号） */
        private String delimiter;

        /** 是否忽略首行（默认 false；true 时 CSVDataSet 跳过文件首行，适配带表头的参数文件） */
        private Boolean ignoreFirstLine;

        /** 是否循环读取（默认 true） */
        private Boolean recycle;

        /** 共享模式（默认 all） */
        private String shareMode;
    }
}
