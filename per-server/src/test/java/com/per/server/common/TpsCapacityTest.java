package com.per.server.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TpsCapacityTest {

    @Test
    void estimatesThreadsFromTpsAndExpectedResponseTime() {
        assertEquals(3_000, TpsCapacity.defaultThreads(20_000, 100));
        assertEquals(7_500, TpsCapacity.defaultThreads(50_000, 100));
        assertEquals(22_500, TpsCapacity.defaultThreads(50_000, 300));
    }

    @Test
    void capsAutomaticThreadCount() {
        assertEquals(TpsCapacity.MAX_AUTO_THREADS, TpsCapacity.defaultThreads(100_000, 1_000));
    }
}
