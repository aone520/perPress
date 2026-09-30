package com.per.server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 脚本调试请求 DTO：携带当前编辑中的表单定义（未保存也可调试）；
 * 不做字段级联校验（@Valid），缺 URL 等问题以单接口 error 形式返回，保证整链路可跑
 */
@Data
public class ScriptDebugRequest {

    /** 表单场景定义（当前编辑器内容原样提交） */
    @NotNull(message = "formDef不能为空")
    private ScriptFormRequest.FormDef formDef;
}
