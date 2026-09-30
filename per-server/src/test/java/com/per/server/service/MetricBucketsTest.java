package com.per.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MetricBuckets 分位数算法自证测试：
 * 覆盖桶解析/合并、单桶线性插值、跨桶定位、空数据兜底与超 60s 计入末桶语义
 */
class MetricBucketsTest {

    /**
     * 构造仅第 idx 个桶有 count 个样本的桶数组
     *
     * @param idx   桶下标
     * @param count 样本数
     * @return 桶计数数组
     */
    private long[] onlyBucket(int idx, long count) {
        long[] buckets = MetricBuckets.emptyBuckets();
        buckets[idx] = count;
        return buckets;
    }

    /**
     * 验证桶定义规模：38 桶、首桶上界 1、末桶上界 60000
     */
    @Test
    void testBucketDefinition() {
        assertEquals(38, MetricBuckets.BUCKET_COUNT);
        assertEquals(38, MetricBuckets.UPPER_BOUNDS.length);
        assertEquals(1, MetricBuckets.UPPER_BOUNDS[0]);
        assertEquals(60000, MetricBuckets.UPPER_BOUNDS[37]);
    }

    /**
     * 验证单桶线性插值：100 样本全落 (80,100] 桶（下标 19），
     * p50=90、p95=99、p100=100；10 样本全落 (0,1] 桶（下标 0）时 p50=0.5
     */
    @Test
    void testSingleBucketInterpolation() {
        long[] buckets = onlyBucket(19, 100);
        assertEquals(90.0, MetricBuckets.percentile(buckets, 50), 1e-9);
        assertEquals(99.0, MetricBuckets.percentile(buckets, 95), 1e-9);
        assertEquals(100.0, MetricBuckets.percentile(buckets, 100), 1e-9);

        long[] first = onlyBucket(0, 10);
        assertEquals(0.5, MetricBuckets.percentile(first, 50), 1e-9);
        assertEquals(1.0, MetricBuckets.percentile(first, 100), 1e-9);
    }

    /**
     * 验证跨桶累加定位：桶 9（(9,10]）10 个 + 桶 19（(80,100]）10 个共 20 样本，
     * p50 命中桶 9 末位 = 10；p75 命中桶 19 中位 = 90；p55 命中桶 19 首 10% 位置 = 82
     */
    @Test
    void testCrossBucketRanking() {
        long[] buckets = MetricBuckets.emptyBuckets();
        buckets[9] = 10;   // (9,10]
        buckets[19] = 10;  // (80,100]
        assertEquals(10.0, MetricBuckets.percentile(buckets, 50), 1e-9);
        assertEquals(90.0, MetricBuckets.percentile(buckets, 75), 1e-9);
        assertEquals(82.0, MetricBuckets.percentile(buckets, 55), 1e-9);
    }

    /**
     * 验证解析与合并：parse 逗号串逐位还原、merge 多节点桶相加、toCsv/parse 互逆
     */
    @Test
    void testParseAndMerge() {
        long[] parsed = MetricBuckets.parse("1,2,3");
        assertEquals(1, parsed[0]);
        assertEquals(2, parsed[1]);
        assertEquals(3, parsed[2]);
        assertEquals(0, parsed[3]);

        long[] node1 = MetricBuckets.parse("5,0,0");
        long[] node2 = MetricBuckets.parse("3,1,0");
        MetricBuckets.merge(node1, node2);
        assertArrayEquals(new long[]{8, 1, 0}, java.util.Arrays.copyOf(node1, 3));

        // toCsv 输出固定 38 桶长度，与 parse 互逆
        long[] roundTrip = MetricBuckets.parse(MetricBuckets.toCsv(MetricBuckets.parse("8,1,0")));
        assertEquals(8, roundTrip[0]);
        assertEquals(1, roundTrip[1]);
        assertEquals(0, roundTrip[2]);

        // 空/异常输入兜底为全 0 桶
        assertEquals(0, MetricBuckets.parse(null)[0]);
        assertEquals(0, MetricBuckets.parse("  ")[0]);
    }

    /**
     * 验证空数据与末桶超限：无样本分位数返回 0；全部样本计入末桶时 p999=60000
     * （Agent 侧约定响应时间 &gt; 60000 一律计入最后一桶）
     */
    @Test
    void testEmptyAndOverflowBucket() {
        assertEquals(0, MetricBuckets.percentile(MetricBuckets.emptyBuckets(), 95));
        assertEquals(0, MetricBuckets.percentile(null, 95));

        // 末桶区间 (30000,60000]：p99.9 桶内插值 = 30000+0.999*30000；p100 恰为上界
        long[] overflow = onlyBucket(37, 50);
        assertEquals(59970.0, MetricBuckets.percentile(overflow, 99.9), 1e-9);
        assertEquals(60000.0, MetricBuckets.percentile(overflow, 100), 1e-9);
    }
}
