package com.connectionmonitor.starter.jvm;

import com.connectionmonitor.starter.model.JvmMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;

/**
 * JVM 指标采集：基于 JDK 公开 MXBean，逐项隔离异常，任一项失败不影响其他项与应用。
 */
public class JvmMetricsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(JvmMetricsCollector.class);

    public JvmMetrics collect() {
        JvmMetrics metrics = new JvmMetrics();
        try {
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            fillMemory(metrics, memory);
        } catch (Throwable t) {
            LOG.debug("JVM 内存指标采集失败: {}", t.toString());
        }
        try {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            metrics.setTotalThreadCount(threads.getThreadCount());
            metrics.setPeakThreadCount(threads.getPeakThreadCount());
            metrics.setDaemonThreadCount(threads.getDaemonThreadCount());
        } catch (Throwable t) {
            LOG.debug("JVM 线程指标采集失败: {}", t.toString());
        }
        try {
            ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
            metrics.setLoadedClassCount(classes.getLoadedClassCount());
        } catch (Throwable t) {
            LOG.debug("JVM 类加载指标采集失败: {}", t.toString());
        }
        try {
            metrics.setUptimeMs(ManagementFactory.getRuntimeMXBean().getUptime());
        } catch (Throwable t) {
            LOG.debug("JVM 运行时长采集失败: {}", t.toString());
        }
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            java.lang.reflect.Method method = os.getClass().getMethod("getProcessCpuLoad");
            method.setAccessible(true);
            Object value = method.invoke(os);
            if (value instanceof Double) {
                metrics.setProcessCpuLoad((Double) value);
            }
        } catch (Throwable t) {
            // getProcessCpuLoad 仅在部分 JVM 可用，失败保持 null
            LOG.debug("进程 CPU 使用率采集失败: {}", t.toString());
        }
        return metrics;
    }

    private void fillMemory(JvmMetrics metrics, MemoryMXBean memory) {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        if (heap != null) {
            metrics.setHeapUsedBytes(heap.getUsed());
            metrics.setHeapMaxBytes(heap.getMax());
        }
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        if (nonHeap != null) {
            metrics.setNonHeapUsedBytes(nonHeap.getUsed());
            metrics.setNonHeapMaxBytes(nonHeap.getMax());
        }
    }
}
