package com.per.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 压测任务创建请求 DTO：脚本版本 + 模式参数 + 参测节点 + 文件分发策略
 */
@Data
public class TaskCreateRequest {

    /** 任务名称 */
    @NotBlank(message = "name不能为空")
    private String name;

    /** 脚本ID */
    @NotNull(message = "scriptId不能为空")
    private Long scriptId;

    /** 脚本版本号 */
    @NotNull(message = "version不能为空")
    private Integer version;

    /** 压测模式（CONCURRENT 并发 / FIXED_TPS 固定吞吐 / STEPPED 阶梯，默认 CONCURRENT） */
    private String mode = "CONCURRENT";

    /** 模式参数（字段按模式取用，服务端按压测模式校验完整性） */
    @NotNull(message = "config不能为空")
    @Valid
    private Config config;

    /** 参测节点 node_key 列表 */
    @NotEmpty(message = "nodeKeys不能为空")
    private List<String> nodeKeys;

    /** 文件分发策略（可选） */
    @Valid
    private List<FileDispatch> fileDispatch;

    /** 定时启动时间（可选，格式 yyyy-MM-dd HH:mm:ss，东八区；提供且晚于当前时间则为定时任务） */
    private String scheduledStartTime;

    /**
     * 压测模式参数（按模式取用不同字段，服务端按模式做完整性校验并给出中文错误）：
     * CONCURRENT：threads/rampupSeconds(默认60)/durationSeconds(默认300)；
     * FIXED_TPS：tps/durationSeconds/maxThreads(可选，默认 min(tps*2,2000))；
     * STEPPED：unit(THREADS|TPS)/start/step/stepSeconds/peak/peakSeconds/rampupSeconds(默认30)
     */
    @Data
    public static class Config {

        /** CONCURRENT：总并发线程数（按节点均分） */
        private Integer threads;

        /** CONCURRENT/FIXED_TPS：爬坡时长（秒，CONCURRENT 默认 60） */
        private Integer rampupSeconds;

        /** 持续时长（秒；STEPPED 由阶梯表推导不可指定，FIXED_TPS 必填，CONCURRENT 默认 300） */
        private Integer durationSeconds;

        /** FIXED_TPS：目标吞吐（样本数/秒） */
        private Integer tps;

        /** FIXED_TPS：线程数上限（可选，默认 min(tps*2,2000)） */
        private Integer maxThreads;

        /** STEPPED：阶梯单位（THREADS 按线程阶梯 / TPS 按吞吐阶梯） */
        private String unit;

        /** STEPPED：起始负载（>=1） */
        private Integer start;

        /** STEPPED：每步增量（>=1） */
        private Integer step;

        /** STEPPED：每步持续秒数（>0） */
        private Integer stepSeconds;

        /** STEPPED：峰值负载（>=start） */
        private Integer peak;

        /** STEPPED：峰值平台持续秒数（>0） */
        private Integer peakSeconds;

        /** 流量占比（可选）：按执行单元顺序的整数百分比（1-100，总和=100），
         * 仅表单脚本多单元时生效；为空时按单元均分。执行单元=串行组整体/并行组内单个接口 */
        private List<Integer> weights;

        /**
         * 接口级流量漏斗（可选，仅 FIXED_TPS 生效）：key=「组名/接口名」，value=放行百分比(0,100]，
         * 覆盖脚本内接口配置的 trafficPercent 默认值；串行组链路按比例放行迭代（登录100→下单60→支付30）
         */
        private Map<String, Double> funnelPercents;

        /** JMeter 堆内存上限（MB，可选，>=256）：为空时使用节点 Agent 本地配置（默认 2048） */
        private Integer jmeterHeapMb;
    }

    /**
     * 文件分发策略：SHARED 全节点共用原文件 / SPLIT 按行拆分分片
     */
    @Data
    public static class FileDispatch {

        /** 数据文件ID */
        @NotNull(message = "fileId不能为空")
        private Long fileId;

        /** 分发模式：SHARED/SPLIT */
        @NotBlank(message = "mode不能为空")
        private String mode;
    }
}
