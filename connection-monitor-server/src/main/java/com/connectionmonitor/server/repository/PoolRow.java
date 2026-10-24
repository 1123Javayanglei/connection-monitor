package com.connectionmonitor.server.repository;

/** 连接池最新状态行。 */
public class PoolRow {

    private String instanceId;
    private String poolId;
    private String dataSourceId;
    private String dataSourceBeanName;
    private String poolName;
    private String poolType;
    private String poolVersion;
    private String metricsAvailability;
    private Integer activeConnections;
    private Integer idleConnections;
    private Integer totalConnections;
    private Integer maxConnections;
    private Integer minIdleConnections;
    private Integer threadsAwaitingConnection;
    private Long connectionTimeoutMs;
    private String extraMetricsJson;
    private Long sampleTime;

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
    }

    public String getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public String getDataSourceBeanName() {
        return dataSourceBeanName;
    }

    public void setDataSourceBeanName(String dataSourceBeanName) {
        this.dataSourceBeanName = dataSourceBeanName;
    }

    public String getPoolName() {
        return poolName;
    }

    public void setPoolName(String poolName) {
        this.poolName = poolName;
    }

    public String getPoolType() {
        return poolType;
    }

    public void setPoolType(String poolType) {
        this.poolType = poolType;
    }

    public String getPoolVersion() {
        return poolVersion;
    }

    public void setPoolVersion(String poolVersion) {
        this.poolVersion = poolVersion;
    }

    public String getMetricsAvailability() {
        return metricsAvailability;
    }

    public void setMetricsAvailability(String metricsAvailability) {
        this.metricsAvailability = metricsAvailability;
    }

    public Integer getActiveConnections() {
        return activeConnections;
    }

    public void setActiveConnections(Integer activeConnections) {
        this.activeConnections = activeConnections;
    }

    public Integer getIdleConnections() {
        return idleConnections;
    }

    public void setIdleConnections(Integer idleConnections) {
        this.idleConnections = idleConnections;
    }

    public Integer getTotalConnections() {
        return totalConnections;
    }

    public void setTotalConnections(Integer totalConnections) {
        this.totalConnections = totalConnections;
    }

    public Integer getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(Integer maxConnections) {
        this.maxConnections = maxConnections;
    }

    public Integer getMinIdleConnections() {
        return minIdleConnections;
    }

    public void setMinIdleConnections(Integer minIdleConnections) {
        this.minIdleConnections = minIdleConnections;
    }

    public Integer getThreadsAwaitingConnection() {
        return threadsAwaitingConnection;
    }

    public void setThreadsAwaitingConnection(Integer threadsAwaitingConnection) {
        this.threadsAwaitingConnection = threadsAwaitingConnection;
    }

    public Long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(Long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public String getExtraMetricsJson() {
        return extraMetricsJson;
    }

    public void setExtraMetricsJson(String extraMetricsJson) {
        this.extraMetricsJson = extraMetricsJson;
    }

    public Long getSampleTime() {
        return sampleTime;
    }

    public void setSampleTime(Long sampleTime) {
        this.sampleTime = sampleTime;
    }
}
