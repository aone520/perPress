package com.per.server.dto;

import lombok.Data;

import java.util.List;

/**
 * 文件预览视图对象：CSV/TXT 文本文件前 N 行内容（供前端表格/文本展示）
 */
@Data
public class FilePreviewVO {

    /** 文件ID */
    private Long id;

    /** 原始文件名 */
    private String name;

    /** 文件类型（CSV/TXT） */
    private String fileType;

    /** 文件大小（字节） */
    private Long size;

    /** 预览行内容（已按上限截断） */
    private List<String> lines;

    /** 是否因超过预览行数上限被截断（完整内容请下载查看） */
    private boolean truncated;
}
