package com.per.agent.monitor;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import org.springframework.stereotype.Component;

/**
 * 系统资源采集器：为心跳上报提供本机资源快照。
 * <p>采集方案（二选一，本实现选用 oshi）：
 * 1) oshi：CPU 使用率通过两次 getSystemCpuLoadTicks 采样间隔 1s 计算差值；
 *    操作系统内存已用/总量通过 oshi GlobalMemory 获取（更贴近真实物理内存）；
 *    JVM 堆已用/最大通过 MemoryMXBean 获取。
 * 2) 备选（oshi 不可用时）：com.sun.management.OperatingSystemMXBean 的
 *    getSystemCpuLoad()/getCpuLoad() 取 CPU，Runtime.getRuntime() 取 JVM 内存。</p>
 */
@Slf4j
@Component
public class SystemResourceCollector {

    /** CPU 两次采样间隔（毫秒） */
    private static final long CPU_SAMPLE_INTERVAL_MS = 1000L;

    /** oshi CPU 处理器（进程内复用） */
    private final CentralProcessor processor;

    /** oshi 内存信息（进程内复用） */
    private final GlobalMemory memory;

    /** JVM 内存 MXBean */
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    /**
     * 初始化 oshi 硬件抽象层（进程内只加载一次本地库）。
     */
    public SystemResourceCollector() {
        HardwareAbstractionLayer hardware = new SystemInfo().getHardware();
        this.processor = hardware.getProcessor();
        this.memory = hardware.getMemory();
    }

    /**
     * 采集 CPU 使用率（0-100）：两次 getSystemCpuLoadTicks 采样间隔 1s 计算差值。
     *
     * @return CPU 使用率百分比，采集被中断时返回 0
     */
    public double collectCpuUsage() {
        try {
            long[] previousTicks = processor.getSystemCpuLoadTicks();
            Thread.sleep(CPU_SAMPLE_INTERVAL_MS);
            return processor.getSystemCpuLoadBetweenTicks(previousTicks) * 100.0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Monitor] CPU 采样被中断，本次返回 0");
            return 0.0;
        }
    }

    /**
     * 采集操作系统内存使用率（0-100）。
     *
     * @return 内存使用率百分比
     */
    public double collectMemUsage() {
        long total = memory.getTotal();
        long available = memory.getAvailable();
        if (total <= 0) {
            return 0.0;
        }
        return (total - available) * 100.0 / total;
    }

    /**
     * 采集操作系统内存总量（字节）。
     *
     * @return 内存总量（字节）
     */
    public long collectMemTotal() {
        return memory.getTotal();
    }

    /**
     * 采集 JVM 堆内存已用（字节）。
     *
     * @return JVM 堆已用（字节）
     */
    public long collectJvmMemUsed() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        return heap.getUsed();
    }

    /**
     * 采集 JVM 堆内存上限（字节，未设置上限时为 -1）。
     *
     * @return JVM 堆最大（字节）
     */
    public long collectJvmMemMax() {
        return memoryMXBean.getHeapMemoryUsage().getMax();
    }
}
