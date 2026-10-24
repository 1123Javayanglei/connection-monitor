package com.connectionmonitor.starter.model;

/**
 * JVM 指标（与应用内连接池无关，任何场景下均可独立上报）。
 */
public class JvmMetrics {

    private Long heapUsedBytes;
    private Long heapMaxBytes;
    private Long nonHeapUsedBytes;
    private Long nonHeapMaxBytes;
    private Integer totalThreadCount;
    private Integer peakThreadCount;
    private Integer daemonThreadCount;
    private Integer loadedClassCount;
    private Long uptimeMs;
    /** 进程 CPU 使用率 [0,1]，无法获取时为 null。 */
    private Double processCpuLoad;

    public Long getHeapUsedBytes() {
        return heapUsedBytes;
    }

    public void setHeapUsedBytes(Long heapUsedBytes) {
        this.heapUsedBytes = heapUsedBytes;
    }

    public Long getHeapMaxBytes() {
        return heapMaxBytes;
    }

    public void setHeapMaxBytes(Long heapMaxBytes) {
        this.heapMaxBytes = heapMaxBytes;
    }

    public Long getNonHeapUsedBytes() {
        return nonHeapUsedBytes;
    }

    public void setNonHeapUsedBytes(Long nonHeapUsedBytes) {
        this.nonHeapUsedBytes = nonHeapUsedBytes;
    }

    public Long getNonHeapMaxBytes() {
        return nonHeapMaxBytes;
    }

    public void setNonHeapMaxBytes(Long nonHeapMaxBytes) {
        this.nonHeapMaxBytes = nonHeapMaxBytes;
    }

    public Integer getTotalThreadCount() {
        return totalThreadCount;
    }

    public void setTotalThreadCount(Integer totalThreadCount) {
        this.totalThreadCount = totalThreadCount;
    }

    public Integer getPeakThreadCount() {
        return peakThreadCount;
    }

    public void setPeakThreadCount(Integer peakThreadCount) {
        this.peakThreadCount = peakThreadCount;
    }

    public Integer getDaemonThreadCount() {
        return daemonThreadCount;
    }

    public void setDaemonThreadCount(Integer daemonThreadCount) {
        this.daemonThreadCount = daemonThreadCount;
    }

    public Integer getLoadedClassCount() {
        return loadedClassCount;
    }

    public void setLoadedClassCount(Integer loadedClassCount) {
        this.loadedClassCount = loadedClassCount;
    }

    public Long getUptimeMs() {
        return uptimeMs;
    }

    public void setUptimeMs(Long uptimeMs) {
        this.uptimeMs = uptimeMs;
    }

    public Double getProcessCpuLoad() {
        return processCpuLoad;
    }

    public void setProcessCpuLoad(Double processCpuLoad) {
        this.processCpuLoad = processCpuLoad;
    }
}
