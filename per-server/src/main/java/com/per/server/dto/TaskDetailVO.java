package com.per.server.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 压测任务详情视图对象：任务基本信息 + 参测节点执行明细列表
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskDetailVO extends TaskVO {

    /** 参测节点执行明细 */
    private List<TaskNodeVO> nodes;
}
