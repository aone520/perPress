package com.per.server.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶梯压测（STEPPED）负载阶梯表展开工具：
 * 将 {start, step, stepSeconds, peak, peakSeconds} 展开为堆叠线程组段列表。
 * 展开规则：
 * - 段 0 负载 = start，从 0 时刻运行到测试结束；
 * - 段 i（i>=1）在 delay = i*stepSeconds 时刻叠加增量负载，运行到测试结束；
 * - 每段增量通常为 step，(peak-start) 不能整除 step 时最后一段为部分增量；
 * - 总时长 = 步进段数*stepSeconds + peakSeconds（峰值平台期）。
 * 任一时刻的瞬时负载 = 该时刻已启动段的负载之和
 */
public final class SteppedPlan {

    /**
     * 私有构造：工具类禁止实例化
     */
    private SteppedPlan() {
    }

    /**
     * 阶梯段：value 为本段叠加的负载（THREADS 模式为线程数增量，TPS 模式为 TPS 增量），
     * delaySeconds 为本段启动延迟，durationSeconds 为本段持续时长（均运行到测试结束）
     */
    public static class Segment {

        /** 本段叠加负载（线程数或 TPS） */
        private final int value;

        /** 本段启动延迟（秒，相对测试起点） */
        private final int delaySeconds;

        /** 本段持续时长（秒，= 总时长 - 延迟） */
        private final int durationSeconds;

        /**
         * 构造阶梯段
         *
         * @param value           本段叠加负载
         * @param delaySeconds    启动延迟（秒）
         * @param durationSeconds 持续时长（秒）
         */
        public Segment(int value, int delaySeconds, int durationSeconds) {
            this.value = value;
            this.delaySeconds = delaySeconds;
            this.durationSeconds = durationSeconds;
        }

        /**
         * 获取本段叠加负载
         *
         * @return 线程数或 TPS
         */
        public int getValue() {
            return value;
        }

        /**
         * 获取本段启动延迟
         *
         * @return 延迟秒数
         */
        public int getDelaySeconds() {
            return delaySeconds;
        }

        /**
         * 获取本段持续时长
         *
         * @return 时长秒数
         */
        public int getDurationSeconds() {
            return durationSeconds;
        }
    }

    /**
     * 展开阶梯负载表。
     * 示例：start=10, step=10, stepSeconds=120, peak=100, peakSeconds=600 →
     * 段数 10，总时长 1680s：段0(10,0,1680)、段1(10,120,1560)…段9(10,1080,600)
     *
     * @param start       起始负载（>=1）
     * @param step        每步增量（>=1）
     * @param stepSeconds 每步持续秒数（>0）
     * @param peak        峰值负载（>=start）
     * @param peakSeconds 峰值平台持续秒数（>0）
     * @return 堆叠段列表（首段含 start，后续段为增量，最后一段可能为部分增量）
     */
    public static List<Segment> expand(int start, int step, int stepSeconds, int peak, int peakSeconds) {
        // 完整步进次数（最后一步可为部分增量，向上取整）
        int steps = (peak - start + step - 1) / step;
        int total = steps * stepSeconds + peakSeconds;
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(start, 0, total));
        for (int i = 1; i <= steps; i++) {
            int delay = i * stepSeconds;
            // 剩余距峰值的缺口，不足一个 step 时取部分增量
            int value = Math.min(step, peak - (start + (i - 1) * step));
            segments.add(new Segment(value, delay, total - delay));
        }
        return segments;
    }

    /**
     * 计算阶梯表总时长（秒）
     *
     * @param start       起始负载
     * @param step        每步增量
     * @param stepSeconds 每步持续秒数
     * @param peak        峰值负载
     * @param peakSeconds 峰值平台持续秒数
     * @return 测试总时长（秒）
     */
    public static int totalDuration(int start, int step, int stepSeconds, int peak, int peakSeconds) {
        int steps = Math.max(0, (peak - start + step - 1) / step);
        return steps * stepSeconds + peakSeconds;
    }
}
