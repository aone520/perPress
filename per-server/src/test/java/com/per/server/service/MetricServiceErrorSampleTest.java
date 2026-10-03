package com.per.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetricServiceErrorSampleTest {

    @Test
    void truncatesExternalTextToDatabaseLimits() {
        assertEquals(256, MetricService.truncate("L".repeat(300), 256).length());
        assertEquals(255, MetricService.truncate("Non HTTP response code: " + "X".repeat(300), 255).length());
        assertEquals(1024, MetricService.truncate("M".repeat(1500), 1024).length());
        assertEquals("short", MetricService.truncate("short", 255));
        assertNull(MetricService.truncate(null, 255));
    }
}
