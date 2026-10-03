package com.per.server.common;

/** TPS 模式线程容量估算：并发约等于 TPS×响应时间，并预留 50% 调度余量。 */
public final class TpsCapacity {

    public static final int DEFAULT_EXPECTED_RESPONSE_MS = 100;
    public static final int MAX_AUTO_THREADS = 50_000;
    private static final double SAFETY_FACTOR = 1.5;

    private TpsCapacity() {
    }

    public static int defaultThreads(int tps, Integer expectedResponseMs) {
        int responseMs = expectedResponseMs == null ? DEFAULT_EXPECTED_RESPONSE_MS : expectedResponseMs;
        long threads = (long) Math.ceil(tps * responseMs / 1000.0 * SAFETY_FACTOR);
        return (int) Math.max(1, Math.min(threads, MAX_AUTO_THREADS));
    }
}
