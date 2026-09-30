package com.per.server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 节点标签更新请求 DTO
 */
@Data
public class LabelsUpdateRequest {

    /** 标签列表（保存时合并为逗号分隔） */
    @NotNull(message = "labels不能为空")
    private List<String> labels;
}
