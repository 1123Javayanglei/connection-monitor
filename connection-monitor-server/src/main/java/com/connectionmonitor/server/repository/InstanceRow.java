package com.connectionmonitor.server.repository;

/** 实例列表/详情行。 */
public class InstanceRow {

    private String instanceId;
    private String appName;
    private String host;
    private Long heapUsedBytes;
    private Long heapMaxBytes;
    private Long nonHeapUsedBytes;
    private Integer totalThreadCount;
    private Integer peakThreadCount;
    private Integer daemonThreadCount;
    private Integer loadedClassCount;
    private Long uptimeMs;
    private Double processCpuLoad;
    private Long lastReportTime;
    private int poolCount;

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

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

    public Long getLastReportTime() {
        return lastReportTime;
    }

    public void setLastReportTime(Long lastReportTime) {
        this.lastReportTime = lastReportTime;
    }

    public int getPoolCount() {
        return poolCount;
    }

    public void setPoolCount(int poolCount) {
        this.poolCount = poolCount;
    }
}
