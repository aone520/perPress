package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测试报告实体：对应表 test_report，任务结束后固化的聚合报告（task_id 唯一，重复聚合覆盖更新）
 */
@Data
@TableName("test_report")
public class TestReport {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务ID（唯一） */
    private Long taskId;

    /** 汇总指标 JSON（任务级总量/TPS/RT 分位等） */
    private String summaryJson;

    /** 采样器级指标 JSON */
    private String samplersJson;

    /** 节点级指标 JSON */
    private String nodesJson;

    /** 错误分析 JSON（按码统计/Top采样器/明细/时间线） */
    private String errorsJson;

    /** 全任务时间序列 JSON（与 /metrics series 相同） */
    private String seriesJson;

    /** 固化时间（数据库默认生成） */
    private LocalDateTime createTime;
}
