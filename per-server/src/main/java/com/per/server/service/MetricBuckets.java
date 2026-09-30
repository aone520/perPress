package com.per.server.service;

import java.util.Arrays;

/**
 * 响应时间对数桶统一定义（Server 与 Agent 双端约定一致，不可单方面变更）：
 * 共 38 个桶，桶 i 的上界（毫秒）依次为
 * {1,2,3,4,5,6,7,8,9,10,12,15,20,25,30,40,50,60,80,100,150,200,300,400,600,800,1000,
 *  1500,2000,3000,4000,6000,8000,10000,15000,20000,30000,60000}；
 * 响应时间 &lt;= 上界落入该桶（首个满足的桶），桶 0 下界为 0，桶 i 下界为上一桶上界；
 * 超过 60000ms 一律计入最后一桶。
 * 分位数算法：桶计数累加定位目标序号，桶内上下界线性插值
 */
public final class MetricBuckets {

    /** 38 个桶的毫秒上界（有序递增，最后一桶为 60000，超限也计入） */
    public static final int[] UPPER_BOUNDS = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            12, 15, 20, 25, 30, 40, 50, 60, 80, 100,
            150, 200, 300, 400, 600, 800, 1000, 1500, 2000, 3000,
            4000, 6000, 8000, 10000, 15000, 20000, 30000, 60000};

    /** 桶数量（38） */
    public static final int BUCKET_COUNT = UPPER_BOUNDS.length;

    /**
     * 私有构造：常量工具类禁止实例化
     */
    private MetricBuckets() {
    }

    /**
     * 新建全 0 桶计数数组
     *
     * @return 长度为 38 的全 0 数组
     */
    public static long[] emptyBuckets() {
        return new long[BUCKET_COUNT];
    }

    /**
     * 解析逗号分隔的桶计数字符串（Agent 上报格式 "c1,c2,..."）
     *
     * @param csv 逗号分隔的桶计数，空串/异常输入返回全 0 桶
     * @return 长度为 38 的桶计数数组
     */
    public static long[] parse(String csv) {
        long[] buckets = emptyBuckets();
        if (csv == null || csv.isBlank()) {
            return buckets;
        }
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length && i < BUCKET_COUNT; i++) {
            try {
                buckets[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException ignored) {
                buckets[i] = 0;
            }
        }
        return buckets;
    }

    /**
     * 桶计数数组转逗号分隔字符串（与 parse 互逆，主要用于调试与测试）
     *
     * @param buckets 桶计数数组
     * @return 逗号分隔的字符串
     */
    public static String toCsv(long[] buckets) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buckets.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(buckets[i]);
        }
        return sb.toString();
    }

    /**
     * 将 add 桶计数累加进 target（多节点/多窗口合并）
     *
     * @param target 合并目标数组（长度须为 38）
     * @param add    被合并数组（长度须为 38）
     */
    public static void merge(long[] target, long[] add) {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            target[i] += add[i];
        }
    }

    /**
     * 基于桶计数估算响应时间 ≤ thresholdMs 的样本数（APDEX 满意/可容忍计数用）：
     * 上界 ≤ 阈值的桶全量计入，阈值恰好落在某桶内时按线性比例近似
     *
     * @param buckets     桶计数数组（长度 38）
     * @param thresholdMs 阈值（毫秒）
     * @return 估算样本数
     */
    public static double countAtMost(long[] buckets, double thresholdMs) {
        if (buckets == null || buckets.length == 0 || thresholdMs <= 0) {
            return 0;
        }
        double count = 0;
        double lower = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            double upper = UPPER_BOUNDS[i];
            if (upper <= thresholdMs) {
                count += buckets[i];
            } else if (thresholdMs > lower) {
                // 阈值落在 (lower, upper] 桶内：按区间位置线性分摊
                count += buckets[i] * (thresholdMs - lower) / (upper - lower);
                break;
            } else {
                break;
            }
            lower = upper;
        }
        return count;
    }

    /**
     * 计算 APDEX 满意度指数（行业标准，JMeter Dashboard 首屏指标）：
     * apdex = (satisfied + tolerating/2) / total，
     * 其中 satisfied = RT≤satisfiedMs 的样本数，tolerating = satisfiedMs&lt;RT≤toleratingMs 的样本数
     *
     * @param buckets      桶计数数组（长度 38）
     * @param satisfiedMs  满意阈值（毫秒，默认惯例 500）
     * @param toleratingMs 可容忍阈值（毫秒，默认惯例 1500）
     * @return APDEX 值（0~1，三位小数；无样本返回 0）
     */
    public static double apdex(long[] buckets, double satisfiedMs, double toleratingMs) {
        if (buckets == null || buckets.length == 0) {
            return 0;
        }
        long total = 0;
        for (long c : buckets) {
            total += c;
        }
        if (total == 0) {
            return 0;
        }
        double satisfied = countAtMost(buckets, satisfiedMs);
        double tolerated = countAtMost(buckets, toleratingMs) - satisfied;
        return Math.round((satisfied + tolerated / 2) / total * 1000) / 1000.0;
    }

    /**
     * 基于桶计数计算分位数：累计计数定位目标序号所在桶，桶内上下界线性插值。
     * 例如 100 个样本全落 (80,100] 桶时 p50=90、p95=99
     *
     * @param buckets 桶计数数组（长度 38）
     * @param p       分位点（0-100，如 95 表示 p95）
     * @return 分位数值（毫秒，保留插值小数）；无样本返回 0
     */
    public static double percentile(long[] buckets, double p) {
        if (buckets == null || buckets.length == 0) {
            return 0;
        }
        long total = 0;
        for (long c : buckets) {
            total += c;
        }
        if (total == 0) {
            return 0;
        }
        // 目标序号（从 1 开始），最低取 1 保证有样本时返回有效值
        double rank = Math.max(1, p / 100.0 * total);
        long cumulative = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            cumulative += buckets[i];
            if (buckets[i] > 0 && cumulative >= rank) {
                double lower = i == 0 ? 0 : UPPER_BOUNDS[i - 1];
                double upper = UPPER_BOUNDS[i];
                // 桶内相对位置（0~1），线性插值
                double pos = (rank - (cumulative - buckets[i])) / buckets[i];
                pos = Math.max(0, Math.min(1, pos));
                return lower + (upper - lower) * pos;
            }
        }
        return UPPER_BOUNDS[BUCKET_COUNT - 1];
    }

    /**
     * 判断给定桶计数数组与预期是否逐桶一致（测试断言用）
     *
     * @param actual   实际数组
     * @param expected 期望数组
     * @return 一致返回 true
     */
    public static boolean sameAs(long[] actual, long[] expected) {
        return Arrays.equals(actual, expected);
    }
}
