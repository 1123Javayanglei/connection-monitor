package com.connectionmonitor.starter.model;

import java.util.List;

/**
 * 业务 Pod 主动上报的完整监控报文。客户端与服务端 JSON 字段命名保持一致。
 */
public class MetricsReport {

    private String instanceId;
    private String appName;
    private String host;
    private Long reportTime;
    private JvmMetrics jvm;
    private List<PoolSnapshot> pools;

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

    public Long getReportTime() {
        return reportTime;
    }

    public void setReportTime(Long reportTime) {
        this.reportTime = reportTime;
    }

    public JvmMetrics getJvm() {
        return jvm;
    }

    public void setJvm(JvmMetrics jvm) {
        this.jvm = jvm;
    }

    public List<PoolSnapshot> getPools() {
        return pools;
    }

    public void setPools(List<PoolSnapshot> pools) {
        this.pools = pools;
    }
}
