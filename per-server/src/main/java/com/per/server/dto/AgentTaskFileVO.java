package com.per.server.dto;

import lombok.Data;

/**
 * Agent 任务下发文件视图对象：poll PREPARE 载荷中的文件下载信息
 */
@Data
public class AgentTaskFileVO {

    /** 数据文件ID */
    private Long fileId;

    /** 文件原始名（Agent 落盘后的引用名） */
    private String name;

    /** 文件 MD5（SHARED 模式供完整性校验） */
    private String md5;

    /** 文件大小（字节） */
    private Long size;

    /** 分发模式：SHARED/SPLIT */
    private String mode;

    /** SPLIT 模式分片序号（SHARED 为 null） */
    private Integer shardIndex;

    /** SPLIT 模式分片总数（SHARED 为 null） */
    private Integer shardCount;

    /** 下载地址（完整 URL） */
    private String downloadUrl;
}
