package com.connectionmonitor.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 客户端配置项（前缀 connection.monitor）。
 */
@ConfigurationProperties(prefix = "connection.monitor")
public class ConnectionMonitorProperties {

    /** 是否启用监控采集与上报，默认 true。 */
    private boolean enabled = true;

    /** 监控服务端地址，例如 http://monitor.internal:8090 ；为空时只采集不上报。 */
    private String serverUrl = "";

    /** 上报周期（毫秒），默认 30 秒。 */
    private long reportIntervalMs = 30000L;

    /** 首次上报延迟（毫秒），默认 15 秒，避免业务启动高峰期抢资源。 */
    private long reportInitialDelayMs = 15000L;

    /** 上报 HTTP 超时（毫秒）。 */
    private int requestTimeoutMs = 5000;

    /** 显式指定实例 ID；为空时自动生成 hostname:appName:随机后缀。 */
    private String instanceId = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public long getReportIntervalMs() {
        return reportIntervalMs;
    }

    public void setReportIntervalMs(long reportIntervalMs) {
        this.reportIntervalMs = reportIntervalMs;
    }

    public long getReportInitialDelayMs() {
        return reportInitialDelayMs;
    }

    public void setReportInitialDelayMs(long reportInitialDelayMs) {
        this.reportInitialDelayMs = reportInitialDelayMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }
}
