package com.per.server.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据文件视图对象：文件库上传结果与列表展示
 */
@Data
public class FileVO {

    /** 文件ID */
    private Long id;

    /** 原始文件名 */
    private String name;

    /** 文件类型：CSV/JAR/BIN */
    private String fileType;

    /** 文件大小（字节） */
    private Long size;

    /** 文件 MD5 */
    private String md5;

    /** 上传人 */
    private String createBy;

    /** 上传时间 */
    private LocalDateTime createTime;
}
