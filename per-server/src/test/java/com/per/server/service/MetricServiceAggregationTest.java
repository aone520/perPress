package com.per.server.service;

import com.per.server.entity.MetricSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricServiceAggregationTest {

    @Test
    void activeThreadsUseMaxPerNodeThenSumAcrossNodes() {
        MetricService service = new MetricService(null, null, null, null);
        long start = 1_700_000_000_000L;
        MetricSnapshot nodeAFirstSampler = snapshot("node-a", "login", start, 100);
        MetricSnapshot nodeASecondSampler = snapshot("node-a", "query", start, 100);
        MetricSnapshot nodeB = snapshot("node-b", "login", start, 80);

        List<Map<String, Object>> series = service.seriesOf(List.of(nodeAFirstSampler, nodeASecondSampler, nodeB));

        assertEquals(1, series.size());
        assertEquals(180, series.get(0).get("threads"));
    }

    @Test
    void percentilesNeverExceedObservedMax() {
        MetricService service = new MetricService(null, null, null, null);
        long start = 1_700_000_000_000L;
        MetricSnapshot snapshot = snapshot("node-a", "login", start, 10);
        snapshot.setSampleCount(100L);
        snapshot.setSumMs(9_000L);
        snapshot.setMinMs(81);
        snapshot.setMaxMs(105);
        long[] buckets = MetricBuckets.emptyBuckets();
        buckets[20] = 100; // (100,150]，未封顶时高分位会被估算到 105ms 以上
        snapshot.setBuckets(MetricBuckets.toCsv(buckets));

        Map<String, Object> seriesPoint = service.seriesOf(List.of(snapshot)).get(0);
        Map<String, Object> sampler = service.samplersSection(List.of(snapshot), true).get(0);

        assertEquals(105.0, seriesPoint.get("p99"));
        assertEquals(105.0, sampler.get("p999"));
        assertTrue((double) sampler.get("p99") <= (int) sampler.get("maxMs"));
    }

    private MetricSnapshot snapshot(String nodeKey, String sampler, long start, int threads) {
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setNodeKey(nodeKey);
        snapshot.setSampler(sampler);
        snapshot.setWindowStart(start);
        snapshot.setWindowEnd(start + 10_000);
        snapshot.setSampleCount(1L);
        snapshot.setErrorCount(0L);
        snapshot.setBytes(0L);
        snapshot.setSentBytes(0L);
        snapshot.setSumMs(1L);
        snapshot.setMinMs(1);
        snapshot.setMaxMs(1);
        snapshot.setActiveThreads(threads);
        snapshot.setBuckets("1");
        return snapshot;
    }
}
