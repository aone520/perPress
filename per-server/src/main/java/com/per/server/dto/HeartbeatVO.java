package com.per.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 心跳响应 DTO：返回服务端时间用于时钟对齐，并附带当前发布引擎信息（M2 扩展，未发布时为 null）
 */
@Data
@AllArgsConstructor
public class HeartbeatVO {

    /** 服务端当前时间戳（毫秒） */
    private Long serverTime;

    /** 当前发布 JMeter 引擎信息（version/md5/url，未发布任何引擎时为 null） */
    private EngineVO engine;
}
