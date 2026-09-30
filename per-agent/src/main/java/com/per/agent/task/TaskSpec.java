package com.per.agent.task;

import java.util.List;
import java.util.Map;

/**
 * 服务端下发的压测任务详情（PREPARE 指令 task 载体，M2 任务协议）：
 * {taskId, taskNo, baseUrl, scriptMd5, jmxContent?, files:[...], jmeterProps:{threads,rampup,duration,...}}。
 *
 * @param taskId      任务 ID（全局唯一，本地目录与回执均以它为键）
 * @param taskNo      任务编号（展示用）
 * @param baseUrl     资源下载基地址（脚本与附件下载地址 = baseUrl + 相对路径）
 * @param scriptMd5   压测脚本 script.jmx 的 MD5 校验值（空表示免校验）
 * @param jmxContent  服务端内嵌的 JMX 脚本文本（非空时直接落盘，为空时走下载地址）
 * @param files       附件列表（数据文件等，落地文件名使用 file.name 保持 JMX 相对引用）
 * @param jmeterProps JMeter 属性透传表（全部按 -J{k}={v} 拼入命令行）
 * @param jmeterHeapMb 任务级 JMeter 堆内存上限（MB，可空；空时使用 Agent 本地默认配置）
 */
public record TaskSpec(
        Long taskId,
        String taskNo,
        String baseUrl,
        String scriptMd5,
        String jmxContent,
        List<TaskFile> files,
        Map<String, Object> jmeterProps,
        Integer jmeterHeapMb) {
}
