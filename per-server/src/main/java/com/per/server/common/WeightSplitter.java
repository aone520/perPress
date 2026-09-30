package com.per.server.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 流量占比整数拆分工具：把总量按整数百分比（Σ=100）拆分到各执行单元，
 * 整除余数按占比降序逐个 +1（占比相同按单元顺序），保证拆分后总量不缩水。
 * 供 FormScriptJmxBuilder（JMX 内嵌默认值）与 TaskService（节点级 props）共用。
 */
public final class WeightSplitter {

    /** 工具类禁止实例化 */
    private WeightSplitter() {
    }

    /**
     * 归一化权重：null/空时按单元数均分（余数补给靠前单元），否则原样返回
     *
     * @param weights   权重列表（可空）
     * @param unitCount 单元数
     * @return 长度为 unitCount 的权重数组
     */
    public static int[] ensureWeights(List<Integer> weights, int unitCount) {
        if (weights == null || weights.isEmpty()) {
            int[] even = new int[unitCount];
            int base = 100 / unitCount;
            int remainder = 100 % unitCount;
            for (int i = 0; i < unitCount; i++) {
                even[i] = base + (i < remainder ? 1 : 0);
            }
            return even;
        }
        if (weights.size() != unitCount) {
            throw new BizException("流量占比数量（" + weights.size() + "）与执行单元数（" + unitCount + "）不一致");
        }
        int[] result = new int[unitCount];
        for (int i = 0; i < unitCount; i++) {
            Integer w = weights.get(i);
            if (w == null || w < 1 || w > 100) {
                throw new BizException("流量占比必须为 1-100 的整数：第" + (i + 1) + "项=" + w);
            }
            result[i] = w;
        }
        return result;
    }

    /**
     * 按权重整数拆分总量：base=total*w/100，余数按占比降序（同占比按序）逐个 +1，Σ结果=total
     *
     * @param total   总量
     * @param weights 权重数组（长度≥1）
     * @return 拆分结果（长度与 weights 一致）
     */
    public static int[] splitInt(int total, int[] weights) {
        long[] result = splitLong(total, weights);
        int[] ints = new int[result.length];
        for (int i = 0; i < result.length; i++) {
            ints[i] = (int) result[i];
        }
        return ints;
    }

    /**
     * 按权重整数拆分总量（long 版，用于 TPS×60）：base=total*w/100，
     * 余数按占比降序（同占比按序）逐个 +1，Σ结果=total
     *
     * @param total   总量
     * @param weights 权重数组（长度≥1）
     * @return 拆分结果（长度与 weights 一致）
     */
    public static long[] splitLong(long total, int[] weights) {
        int unitCount = weights.length;
        long[] result = new long[unitCount];
        long allocated = 0;
        for (int i = 0; i < unitCount; i++) {
            result[i] = total * weights[i] / 100;
            allocated += result[i];
        }
        long remainder = total - allocated;
        // 余数按占比降序补给（同占比按单元顺序），保证总量不缩水
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < unitCount; i++) {
            order.add(i);
        }
        order.sort((a, b) -> weights[b] != weights[a] ? weights[b] - weights[a] : a - b);
        for (int i = 0; i < remainder; i++) {
            result[order.get((int) (i % unitCount))] += 1;
        }
        return result;
    }
}
