package com.connectionmonitor.starter.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.util.UUID;

/**
 * 应用实例 ID 提供者：进程内单例，生成后保持稳定（跨发现周期不变）。
 *
 * <p>格式：{@code hostname:appName:8位随机十六进制}。随机后缀保证重启后是新实例，
 * 不使用 System.identityHashCode 等跨进程不稳定值；不含密码、JDBC URL 等敏感信息。
 * 可通过 {@code connection.monitor.instance-id} 显式覆盖。</p>
 */
public class InstanceIdProvider {

    private static final Logger LOG = LoggerFactory.getLogger(InstanceIdProvider.class);

    private final String instanceId;

    public InstanceIdProvider(Environment environment, String configuredId) {
        if (configuredId != null && configuredId.trim().length() > 0) {
            this.instanceId = configuredId.trim();
            return;
        }
        this.instanceId = detectHostname() + ":" + resolveAppName(environment) + ":" + randomSuffix();
        LOG.info("connection-monitor 实例 ID: {}", this.instanceId);
    }

    public String get() {
        return instanceId;
    }

    private String detectHostname() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && host.trim().length() > 0) {
                return host.trim();
            }
        } catch (Throwable t) {
            LOG.debug("获取主机名失败: {}", t.toString());
        }
        return "unknown-host";
    }

    private String resolveAppName(Environment environment) {
        try {
            String name = environment.getProperty("spring.application.name");
            if (name != null && name.trim().length() > 0) {
                return name.trim();
            }
        } catch (Throwable t) {
            LOG.debug("读取 spring.application.name 失败: {}", t.toString());
        }
        return "unknown-app";
    }

    private String randomSuffix() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return uuid.substring(0, 8);
    }
}
