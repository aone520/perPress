package com.per.server.dto;

import lombok.Data;

/**
 * 脚本新增版本请求 DTO：jmxContent 与 formDef 二选一（formDef 将重新渲染 JMX）
 */
@Data
public class ScriptVersionCreateRequest {

    /** 直接提供的 JMX 内容（与 formDef 二选一） */
    private String jmxContent;

    /** 表单场景定义（与 jmxContent 二选一，渲染生成新 JMX） */
    private ScriptFormRequest.FormDef formDef;

    /** 版本备注 */
    private String remark;

    /** 关联文件 id（逗号分隔，可选） */
    private String fileIds;
}
