package com.per.agent.client;

/**
 * 注册响应 data 结构：{nodeKey, engine:{version,md5,url}}。
 *
 * @param nodeKey 服务端分配的节点密钥
 * @param engine  引擎发布信息（M1 为 null）
 */
public record RegisterResponse(String nodeKey, EngineInfo engine) {
}
