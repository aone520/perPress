package com.per.agent.client;

/**
 * 引擎发布信息：{version, md5, url}（M1 平台未发布引擎时该结构为 null）。
 *
 * @param version 引擎版本号
 * @param md5     引擎包 MD5 校验值
 * @param url     引擎包下载地址
 */
public record EngineInfo(String version, String md5, String url) {
}
