package com.connectionmonitor.server.repository;

/** 连接池历史趋势中的一个聚合点（桶内取最后一个样本，指标缺失为 null）。 */
public class PoolHistoryPoint {

    private long bucketTime;
    private Integer activeConnections;
    private Integer idleConnections;
    private Integer totalConnections;
    private Integer maxConnections;
    private Integer minIdleConnections;
    private Integer threadsAwaitingConnection;

    public PoolHistoryPoint(long bucketTime) {
        this.bucketTime = bucketTime;
    }

    public long getBucketTime() {
        return bucketTime;
    }

    public void setBucketTime(long bucketTime) {
        this.bucketTime = bucketTime;
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
}
