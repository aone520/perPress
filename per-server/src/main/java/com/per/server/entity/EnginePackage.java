package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * JMeter 引擎包实体：对应表 engine_package，is_current=1 的记录为当前发布版本
 */
@Data
@TableName("engine_package")
public class EnginePackage {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 引擎版本号（如 5.6.3） */
    private String version;

    /** 上传的原始文件名 */
    private String fileName;

    /** 文件大小（字节） */
    private Long size;

    /** 文件 MD5 */
    private String md5;

    /** 本地存储路径 */
    private String storagePath;

    /** 是否当前发布版本：1 是 / 0 否 */
    private Integer isCurrent;

    /** 备注 */
    private String remark;

    /** 上传时间（数据库默认生成） */
    private LocalDateTime createTime;
}
