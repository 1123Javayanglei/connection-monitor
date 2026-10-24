package com.connectionmonitor.starter.reporting;

import com.connectionmonitor.starter.collector.CollectorChain;
import com.connectionmonitor.starter.collector.DataSourceMetricsCollector;
import com.connectionmonitor.starter.discovery.DataSourceDiscoveryService;
import com.connectionmonitor.starter.discovery.DiscoveredDataSource;
import com.connectionmonitor.starter.discovery.InstanceIdProvider;
import com.connectionmonitor.starter.jvm.JvmMetricsCollector;
import com.connectionmonitor.starter.model.DataSourceDescriptor;
import com.connectionmonitor.starter.model.JvmMetrics;
import com.connectionmonitor.starter.model.MetricsReport;
import com.connectionmonitor.starter.model.PoolSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 汇总一次完整采集：数据源发现 → 逐池采集（失败降级）→ JVM 指标 → 组装上报报文。
 * 任何单个连接池的异常都被隔离为该池的降级快照，不影响其他池与业务应用。
 */
public class MetricsCollectionService {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsCollectionService.class);

    private final DataSourceDiscoveryService discoveryService;
    private final CollectorChain collectorChain;
    private final JvmMetricsCollector jvmMetricsCollector;
    private final InstanceIdProvider instanceIdProvider;
    private final Environment environment;
    private final String host;

    public MetricsCollectionService(DataSourceDiscoveryService discoveryService,
                                    CollectorChain collectorChain,
                                    JvmMetricsCollector jvmMetricsCollector,
                                    InstanceIdProvider instanceIdProvider,
                                    Environment environment) {
        this.discoveryService = discoveryService;
        this.collectorChain = collectorChain;
        this.jvmMetricsCollector = jvmMetricsCollector;
        this.instanceIdProvider = instanceIdProvider;
        this.environment = environment;
        this.host = detectHost();
    }

    public MetricsReport collectReport() {
        MetricsReport report = new MetricsReport();
        report.setInstanceId(instanceIdProvider.get());
        report.setAppName(environment.getProperty("spring.application.name", "unknown-app"));
        report.setHost(host);
        report.setReportTime(System.currentTimeMillis());
        report.setJvm(collectJvmSafely());
        report.setPools(collectPoolsSafely());
        return report;
    }

    private JvmMetrics collectJvmSafely() {
        try {
            return jvmMetricsCollector.collect();
        } catch (Throwable t) {
            LOG.warn("JVM 指标采集失败: {}", t.toString());
            return new JvmMetrics();
        }
    }

    private List<PoolSnapshot> collectPoolsSafely() {
        List<PoolSnapshot> pools = new ArrayList<PoolSnapshot>();
        List<DiscoveredDataSource> discovered;
        try {
            discovered = discoveryService.discover();
        } catch (Throwable t) {
            LOG.warn("数据源发现失败: {}", t.toString());
            return pools;
        }
        for (DiscoveredDataSource item : discovered) {
            try {
                pools.add(collectOne(item));
            } catch (Throwable t) {
                LOG.warn("连接池 [{}] 采集异常，已降级: {}", describe(item), t.toString());
                pools.add(degradedSnapshot(item.getDescriptor()));
            }
        }
        return pools;
    }

    private PoolSnapshot collectOne(DiscoveredDataSource item) {
        DataSourceMetricsCollector collector = collectorChain.findFor(item.getPool());
        if (collector == null) {
            return degradedSnapshot(item.getDescriptor());
        }
        PoolSnapshot snapshot = collector.collect(item.getDescriptor(), item.getPool());
        if (snapshot == null) {
            return degradedSnapshot(item.getDescriptor());
        }
        if (snapshot.getSampleTime() == null) {
            snapshot.setSampleTime(System.currentTimeMillis());
        }
        return snapshot;
    }

    /** 降级快照：保留身份信息，指标全部 null + UNAVAILABLE，绝不伪造为 0。 */
    private PoolSnapshot degradedSnapshot(DataSourceDescriptor descriptor) {
        PoolSnapshot snapshot = new PoolSnapshot();
        if (descriptor != null) {
            snapshot.setPoolId(descriptor.getDataSourceId());
            snapshot.setDataSourceId(descriptor.getDataSourceId());
            snapshot.setDataSourceBeanName(descriptor.getDataSourceBeanName());
            snapshot.setPoolName(descriptor.getPoolName());
            snapshot.setPoolVersion(descriptor.getPoolVersion());
        }
        snapshot.setPoolType(descriptor == null ? PoolSnapshot.POOL_TYPE_UNKNOWN : descriptor.getPoolType());
        snapshot.setMetricsAvailability(PoolSnapshot.AVAILABILITY_UNAVAILABLE);
        snapshot.setSampleTime(System.currentTimeMillis());
        java.util.List<String> unavailable = new ArrayList<String>(java.util.Arrays.asList(
                "activeConnections", "idleConnections", "totalConnections", "maxConnections",
                "minIdleConnections", "threadsAwaitingConnection", "connectionTimeoutMs"));
        snapshot.setUnavailableMetrics(unavailable);
        return snapshot;
    }

    private String describe(DiscoveredDataSource item) {
        DataSourceDescriptor d = item.getDescriptor();
        return d == null ? "unknown" : String.valueOf(d.getDataSourceId());
    }

    private String detectHost() {
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
}
