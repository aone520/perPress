package com.per.server.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 脚本详情视图对象：脚本基本信息 + 表单定义（FORM 类型）+ 版本摘要列表
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ScriptDetailVO extends ScriptVO {

    /** 表单场景定义（FORM 类型时为解析后的 JSON 节点，否则为 null） */
    private JsonNode formDef;

    /** 版本摘要列表（按版本号倒序） */
    private List<ScriptVersionVO> versions;
}
