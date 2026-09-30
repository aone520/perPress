package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据文件实体：对应表 data_file，记录 CSV/JAR/BIN 文件元信息（本地按 md5 存储，同 md5 复用）
 */
@Data
@TableName("data_file")
public class DataFile {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始文件名 */
    private String name;

    /** 文件类型：CSV/JAR/BIN */
    private String fileType;

    /** 文件大小（字节） */
    private Long size;

    /** 文件 MD5（作为存储文件名与去重键） */
    private String md5;

    /** 本地存储路径 */
    private String storagePath;

    /** 上传人 */
    private String createBy;

    /** 上传时间（数据库默认生成） */
    private LocalDateTime createTime;
}
