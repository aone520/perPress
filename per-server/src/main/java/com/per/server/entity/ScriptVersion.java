package com.per.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脚本版本实体：对应表 script_version，每个版本对应一份 JMX 内容与关联文件
 */
@Data
@TableName("script_version")
public class ScriptVersion {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属脚本ID */
    private Long scriptId;

    /** 版本号（同一脚本内递增） */
    private Integer version;

    /** JMX 脚本内容 */
    private String jmxContent;

    /** 关联文件 id（逗号分隔） */
    private String fileIds;

    /** 版本备注 */
    private String remark;

    /** 创建人 */
    private String createBy;

    /** 创建时间（数据库默认生成） */
    private LocalDateTime createTime;
}
