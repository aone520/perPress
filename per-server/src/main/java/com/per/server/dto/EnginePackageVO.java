package com.per.server.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 引擎包视图对象：引擎包列表展示
 */
@Data
public class EnginePackageVO {

    /** 引擎包ID */
    private Long id;

    /** 引擎版本号 */
    private String version;

    /** 上传的原始文件名 */
    private String fileName;

    /** 文件大小（字节） */
    private Long size;

    /** 文件 MD5 */
    private String md5;

    /** 是否当前发布版本：1 是 / 0 否 */
    private Integer isCurrent;

    /** 备注 */
    private String remark;

    /** 上传时间 */
    private LocalDateTime createTime;
}
