package com.per.agent.task;

/**
 * 任务附件描述（M2 任务协议）：{fileId, name, md5, size, mode, shardIndex, shardCount, downloadUrl}。
 *
 * @param fileId      附件 ID
 * @param name        落地文件名（保持 JMX 中的相对引用，可能含相对子目录）
 * @param md5         附件 MD5 校验值（SHARED 模式下校验；SPLIT 分片时该值指向原始全量文件，不做校验）
 * @param size        附件大小（字节，展示用）
 * @param mode        分发模式：SHARED=每节点全量共享 / SPLIT=按节点分片
 * @param shardIndex  分片序号（SPLIT 模式有效，从 0 开始）
 * @param shardCount  分片总数（SPLIT 模式有效）
 * @param downloadUrl 下载相对地址（SPLIT 已是分片地址，直接拼 baseUrl 下载）
 */
public record TaskFile(
        Long fileId,
        String name,
        String md5,
        Long size,
        String mode,
        Integer shardIndex,
        Integer shardCount,
        String downloadUrl) {

    /**
     * 是否共享模式附件（全量内容，下载后需做 MD5 校验）。
     *
     * @return true 表示 SHARED 模式
     */
    public boolean isShared() {
        return "SHARED".equalsIgnoreCase(mode);
    }
}
