package com.per.agent.core;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 使用心跳响应估算服务端时钟，供多 Agent 按同一 startAt 起跑。 */
@Component
public class ServerClock {

    private final AtomicLong offsetMs = new AtomicLong();

    public void update(long serverTime, long requestStartedAt, long responseReceivedAt) {
        long midpoint = requestStartedAt + (responseReceivedAt - requestStartedAt) / 2;
        offsetMs.set(serverTime - midpoint);
    }

    public long now() {
        return System.currentTimeMillis() + offsetMs.get();
    }

    public long offsetMs() {
        return offsetMs.get();
    }
}
