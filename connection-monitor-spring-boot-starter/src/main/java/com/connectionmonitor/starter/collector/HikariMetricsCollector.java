package com.connectionmonitor.starter.collector;

import com.connectionmonitor.starter.model.DataSourceDescriptor;
import com.connectionmonitor.starter.model.PoolSnapshot;
import com.connectionmonitor.starter.support.ClassNameMatcher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * HikariCP 连接池采集器。
 *
 * <p>依赖策略：HikariCP 由业务应用实际使用的版本提供（Starter 声明为 optional），
 * 不强制升级、不额外创建监控用连接池。指标来自公开 API：
 * 运行时指标读 {@link HikariPoolMXBean}，配置指标读 {@link HikariConfig}，
 * 池未启动（MXBean 为 null）时运行时指标置 null 并标记 PARTIAL，不使用 SQL 探测。</p>
 */
public class HikariMetricsCollector implements DataSourceMetricsCollector, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(HikariMetricsCollector.class);

    private static final String HIKARI_DATASOURCE_CLASS = "com.zaxxer.hikari.HikariDataSource";

    @Override
    public boolean supports(DataSource dataSource) {
        return ClassNameMatcher.isInstanceOf(dataSource, HIKARI_DATASOURCE_CLASS);
    }

    @Override
    public String detectPoolType(DataSource dataSource) {
        return supports(dataSource) ? PoolSnapshot.POOL_TYPE_HIKARI : null;
    }

    @Override
    public String detectPoolName(DataSource dataSource) {
        if (!supports(dataSource)) {
            return null;
        }
        try {
            return HikariAccess.readName(dataSource);
        } catch (Throwable t) {
            LOG.debug("读取 HikariCP 连接池名称失败: {}", t.getMessage());
            return null;
        }
    }

    @Override
    public String detectPoolVersion(DataSource dataSource) {
        if (!supports(dataSource)) {
            return null;
        }
        return HikariAccess.readVersion();
    }

    @Override
    public PoolSnapshot collect(DataSourceDescriptor descriptor, DataSource dataSource) {
        PoolSnapshot snapshot = new PoolSnapshot();
        fillIdentity(snapshot, descriptor);
        List<String> unavailable = new ArrayList<String>();

        try {
            final HikariDataSource hikari = HikariAccess.cast(dataSource);

            // 配置类指标：池未启动也可读取，逐指标隔离异常
            snapshot.setMaxConnections(readMetric("maxConnections", new Callable<Integer>() {
                public Integer call() {
                    return hikari.getMaximumPoolSize();
                }
            }, unavailable));
            snapshot.setMinIdleConnections(readMetric("minIdleConnections", new Callable<Integer>() {
                public Integer call() {
                    return hikari.getMinimumIdle();
                }
            }, unavailable));
            snapshot.setConnectionTimeoutMs(readMetric("connectionTimeoutMs", new Callable<Long>() {
                public Long call() {
                    return hikari.getConnectionTimeout();
                }
            }, unavailable));

            // 运行时指标：仅当池已启动（MXBean 可用）时读取，不创建连接探测
            HikariPoolMXBean pool = readMetric("__poolMxBean", new Callable<HikariPoolMXBean>() {
                public HikariPoolMXBean call() {
                    return hikari.getHikariPoolMXBean();
                }
            }, unavailable);
            if (pool == null) {
                unavailable.add("activeConnections");
                unavailable.add("idleConnections");
                unavailable.add("totalConnections");
                unavailable.add("threadsAwaitingConnection");
                LOG.debug("HikariCP 池 [{}] 尚未启动，运行时指标不可用", snapshot.getPoolName());
            } else {
                final HikariPoolMXBean mxBean = pool;
                snapshot.setActiveConnections(readMetric("activeConnections", new Callable<Integer>() {
                    public Integer call() {
                        return mxBean.getActiveConnections();
                    }
                }, unavailable));
                snapshot.setIdleConnections(readMetric("idleConnections", new Callable<Integer>() {
                    public Integer call() {
                        return mxBean.getIdleConnections();
                    }
                }, unavailable));
                snapshot.setTotalConnections(readMetric("totalConnections", new Callable<Integer>() {
                    public Integer call() {
                        return mxBean.getTotalConnections();
                    }
                }, unavailable));
                snapshot.setThreadsAwaitingConnection(readMetric("threadsAwaitingConnection", new Callable<Integer>() {
                    public Integer call() {
                        return mxBean.getThreadsAwaitingConnection();
                    }
                }, unavailable));
            }
        } catch (Throwable t) {
            LOG.warn("HikariCP 连接池 [{}] 整体采集失败: {}", safeName(snapshot), t.toString());
            String[] all = {"activeConnections", "idleConnections", "totalConnections", "maxConnections",
                    "minIdleConnections", "threadsAwaitingConnection", "connectionTimeoutMs"};
            for (String m : all) {
                if (!unavailable.contains(m)) {
                    unavailable.add(m);
                }
            }
        }

        snapshot.setUnavailableMetrics(unavailable);
        snapshot.setMetricsAvailability(unavailable.isEmpty()
                ? PoolSnapshot.AVAILABILITY_FULL : PoolSnapshot.AVAILABILITY_PARTIAL);
        return snapshot;
    }

    private <T> T readMetric(String metricName, Callable<T> reader, List<String> unavailable) {
        try {
            return reader.call();
        } catch (Throwable t) {
            if (!"__poolMxBean".equals(metricName)) {
                LOG.debug("HikariCP 指标 [{}] 采集失败: {}", metricName, t.toString());
                unavailable.add(metricName);
            }
            return null;
        }
    }

    private void fillIdentity(PoolSnapshot snapshot, DataSourceDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        snapshot.setPoolId(descriptor.getDataSourceId());
        snapshot.setDataSourceId(descriptor.getDataSourceId());
        snapshot.setDataSourceBeanName(descriptor.getDataSourceBeanName());
        snapshot.setPoolName(descriptor.getPoolName());
        snapshot.setPoolType(PoolSnapshot.POOL_TYPE_HIKARI);
        snapshot.setPoolVersion(descriptor.getPoolVersion());
        snapshot.setSampleTime(System.currentTimeMillis());
    }

    private String safeName(PoolSnapshot snapshot) {
        return snapshot.getPoolName() != null ? snapshot.getPoolName() : snapshot.getPoolId();
    }

    @Override
    public int getOrder() {
        return 110;
    }

    /**
     * 直接持有 HikariCP 类型引用的调用隔离层：仅当 supports() 已确认 HikariCP 存在时才加载。
     */
    private static final class HikariAccess {

        static HikariDataSource cast(DataSource dataSource) {
            return (HikariDataSource) dataSource;
        }

        static String readName(DataSource dataSource) {
            return cast(dataSource).getPoolName();
        }

        static String readVersion() {
            Package pkg = HikariDataSource.class.getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            return version;
        }

        private HikariAccess() {
        }
    }
}
