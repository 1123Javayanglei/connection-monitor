package com.connectionmonitor.starter.collector;

import com.alibaba.druid.pool.DruidDataSource;
import com.connectionmonitor.starter.model.DataSourceDescriptor;
import com.connectionmonitor.starter.model.PoolSnapshot;
import com.connectionmonitor.starter.support.ClassNameMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Druid 连接池采集器。
 *
 * <p>依赖策略：Starter 将 Druid 声明为 optional，业务应用未引入 Druid 时本类不会被实例化
 * （自动装配使用 {@code @ConditionalOnClass} 判断），也不会导致启动失败；
 * {@link #supports} 仅按类名匹配，不触发 Druid 类加载。</p>
 *
 * <p>指标全部来自 {@link DruidDataSource} 公开统计 API（MBean 同源 getter），
 * 不执行 SQL、不调用 getConnection()、不开启 StatViewServlet。
 * 单个指标读取失败（例如老版本 NoSuchMethodError）只将该指标置为 null 并记录有限日志，其余指标继续采集。
 * 累计计数器（connectCount 等）放入 extraMetrics，绝不冒充当前连接数。</p>
 */
public class DruidMetricsCollector implements DataSourceMetricsCollector, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(DruidMetricsCollector.class);

    private static final String DRUID_DATASOURCE_CLASS = "com.alibaba.druid.pool.DruidDataSource";

    @Override
    public boolean supports(DataSource dataSource) {
        return ClassNameMatcher.isInstanceOf(dataSource, DRUID_DATASOURCE_CLASS);
    }

    @Override
    public String detectPoolType(DataSource dataSource) {
        return supports(dataSource) ? PoolSnapshot.POOL_TYPE_DRUID : null;
    }

    @Override
    public String detectPoolName(DataSource dataSource) {
        if (!supports(dataSource)) {
            return null;
        }
        try {
            return DruidAccess.readName(dataSource);
        } catch (Throwable t) {
            LOG.debug("读取 Druid 连接池名称失败: {}", t.getMessage());
            return null;
        }
    }

    @Override
    public String detectPoolVersion(DataSource dataSource) {
        if (!supports(dataSource)) {
            return null;
        }
        try {
            return DruidAccess.readVersion(dataSource);
        } catch (Throwable t) {
            LOG.debug("读取 Druid 版本失败: {}", t.getMessage());
            return null;
        }
    }

    @Override
    public PoolSnapshot collect(DataSourceDescriptor descriptor, DataSource dataSource) {
        PoolSnapshot snapshot = new PoolSnapshot();
        fillIdentity(snapshot, descriptor);
        List<String> unavailable = new ArrayList<String>();
        Map<String, Long> extra = new LinkedHashMap<String, Long>();

        try {
            DruidDataSource druid = DruidAccess.cast(dataSource);

            snapshot.setActiveConnections(readMetric("activeConnections",
                    new Callable<Integer>() {
                        public Integer call() {
                            return druid.getActiveCount();
                        }
                    }, unavailable));
            snapshot.setIdleConnections(readMetric("idleConnections",
                    new Callable<Integer>() {
                        public Integer call() {
                            return druid.getPoolingCount();
                        }
                    }, unavailable));
            snapshot.setMaxConnections(readMetric("maxConnections",
                    new Callable<Integer>() {
                        public Integer call() {
                            return druid.getMaxActive();
                        }
                    }, unavailable));
            snapshot.setMinIdleConnections(readMetric("minIdleConnections",
                    new Callable<Integer>() {
                        public Integer call() {
                            return druid.getMinIdle();
                        }
                    }, unavailable));
            // Druid 无直接 total 统计：活动数 + 空闲数，两者任一不可用则 total 置为不可用
            Integer active = snapshot.getActiveConnections();
            Integer idle = snapshot.getIdleConnections();
            if (active != null && idle != null) {
                snapshot.setTotalConnections(active + idle);
            } else {
                unavailable.add("totalConnections");
            }
            snapshot.setThreadsAwaitingConnection(readMetric("threadsAwaitingConnection",
                    new Callable<Integer>() {
                        public Integer call() {
                            return druid.getWaitThreadCount();
                        }
                    }, unavailable));
            Long maxWait = readMetric("connectionTimeoutMs",
                    new Callable<Long>() {
                        public Long call() {
                            return druid.getMaxWait();
                        }
                    }, unavailable);
            snapshot.setConnectionTimeoutMs(maxWait);
            if (maxWait != null) {
                extra.put("maxWaitMillis", maxWait);
            }

            putExtra(extra, "initialSize", readMetric("initialSize",
                    new Callable<Long>() {
                        public Long call() {
                            return (long) druid.getInitialSize();
                        }
                    }, unavailable));
            putExtra(extra, "notEmptyWaitCount", readMetric("notEmptyWaitCount",
                    new Callable<Long>() {
                        public Long call() {
                            return druid.getNotEmptyWaitCount();
                        }
                    }, unavailable));
            putExtra(extra, "connectCount", readMetric("connectCount",
                    new Callable<Long>() {
                        public Long call() {
                            return druid.getConnectCount();
                        }
                    }, unavailable));
            putExtra(extra, "closeCount", readMetric("closeCount",
                    new Callable<Long>() {
                        public Long call() {
                            return druid.getCloseCount();
                        }
                    }, unavailable));
            putExtra(extra, "errorCount", readMetric("errorCount",
                    new Callable<Long>() {
                        public Long call() {
                            return druid.getErrorCount();
                        }
                    }, unavailable));
        } catch (Throwable t) {
            LOG.warn("Druid 连接池 [{}] 整体采集失败: {}", safeName(snapshot), t.toString());
            markAllUnavailable(unavailable);
        }

        snapshot.setUnavailableMetrics(unavailable);
        snapshot.setMetricsAvailability(availabilityOf(unavailable));
        snapshot.setExtraMetrics(extra);
        return snapshot;
    }

    private void fillIdentity(PoolSnapshot snapshot, DataSourceDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        snapshot.setPoolId(descriptor.getDataSourceId());
        snapshot.setDataSourceId(descriptor.getDataSourceId());
        snapshot.setDataSourceBeanName(descriptor.getDataSourceBeanName());
        snapshot.setPoolName(descriptor.getPoolName());
        snapshot.setPoolType(PoolSnapshot.POOL_TYPE_DRUID);
        snapshot.setPoolVersion(descriptor.getPoolVersion());
        snapshot.setSampleTime(System.currentTimeMillis());
    }

    private <T> T readMetric(String metricName, Callable<T> reader, List<String> unavailable) {
        try {
            T value = reader.call();
            return value;
        } catch (Throwable t) {
            // 版本差异（NoSuchMethodError 等）或统计器异常：该指标不可用，记录有限诊断日志后继续
            LOG.debug("Druid 指标 [{}] 采集失败: {}", metricName, t.toString());
            unavailable.add(metricName);
            return null;
        }
    }

    private void putExtra(Map<String, Long> extra, String key, Long value) {
        if (value != null) {
            extra.put(key, value);
        }
    }

    private void markAllUnavailable(List<String> unavailable) {
        String[] all = {"activeConnections", "idleConnections", "totalConnections", "maxConnections",
                "minIdleConnections", "threadsAwaitingConnection", "connectionTimeoutMs"};
        for (String m : all) {
            if (!unavailable.contains(m)) {
                unavailable.add(m);
            }
        }
    }

    private String availabilityOf(List<String> unavailable) {
        return unavailable.isEmpty() ? PoolSnapshot.AVAILABILITY_FULL : PoolSnapshot.AVAILABILITY_PARTIAL;
    }

    private String safeName(PoolSnapshot snapshot) {
        return snapshot.getPoolName() != null ? snapshot.getPoolName() : snapshot.getPoolId();
    }

    @Override
    public int getOrder() {
        return 100;
    }

    /**
     * 直接持有 Druid 类型引用的调用隔离层：仅当 supports() 已确认 Druid 存在时才加载。
     */
    private static final class DruidAccess {

        static DruidDataSource cast(DataSource dataSource) {
            return (DruidDataSource) dataSource;
        }

        static String readName(DataSource dataSource) {
            return cast(dataSource).getName();
        }

        static String readVersion(DataSource dataSource) {
            return cast(dataSource).getVersion();
        }

        private DruidAccess() {
        }
    }
}
