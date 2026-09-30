package com.per.server.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 脚本版本摘要视图对象：脚本详情中的版本列表项
 */
@Data
public class ScriptVersionVO {

    /** 版本记录ID */
    private Long id;

    /** 版本号 */
    private Integer version;

    /** 版本备注 */
    private String remark;

    /** 关联文件 id（逗号分隔） */
    private String fileIds;

    /** JMX 中的普通线程组名列表（按出现顺序；导入脚本流量占比按此展开执行单元，表单脚本忽略） */
    private List<String> threadGroups;

    /** 创建人 */
    private String createBy;

    /** 创建时间 */
    private LocalDateTime createTime;
}
