package com.per.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JMeter 引擎信息视图对象（M1 预留，各字段恒为 null）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EngineVO {

    /** 引擎版本 */
    private String version;

    /** 引擎安装包 MD5 */
    private String md5;

    /** 引擎下载地址 */
    private String url;
}
