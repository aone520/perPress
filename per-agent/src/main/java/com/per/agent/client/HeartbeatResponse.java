package com.per.agent.client;

/**
 * 心跳响应 data 结构：{serverTime}（引擎信息为 M2 扩展字段，M1 恒为 null）。
 *
 * @param serverTime 服务端时间戳（毫秒）
 * @param engine     引擎发布信息（扩展字段，M1 为 null）
 */
public record HeartbeatResponse(Long serverTime, EngineInfo engine) {
}
