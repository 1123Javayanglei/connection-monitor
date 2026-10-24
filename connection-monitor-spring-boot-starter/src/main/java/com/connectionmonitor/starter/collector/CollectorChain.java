package com.connectionmonitor.starter.collector;

import com.connectionmonitor.starter.model.PoolSnapshot;
import org.springframework.core.Ordered;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 采集器链：按 Spring {@link Ordered} 排序后取第一个 supports 命中的适配器；
 * {@link UnsupportedDataSourceCollector} 排在最后兜底，保证任何数据源都有处理结果。
 */
public class CollectorChain {

    private final List<DataSourceMetricsCollector> collectors;

    public CollectorChain(List<DataSourceMetricsCollector> collectors) {
        List<DataSourceMetricsCollector> sorted = new ArrayList<DataSourceMetricsCollector>(collectors);
        Collections.sort(sorted, new Comparator<DataSourceMetricsCollector>() {
            @Override
            public int compare(DataSourceMetricsCollector a, DataSourceMetricsCollector b) {
                return Integer.compare(orderOf(a), orderOf(b));
            }
        });
        this.collectors = Collections.unmodifiableList(sorted);
    }

    private int orderOf(DataSourceMetricsCollector collector) {
        if (collector instanceof Ordered) {
            return ((Ordered) collector).getOrder();
        }
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** 找到第一个支持该数据源的采集器；链尾为降级处理器，理论上不会返回 null。 */
    public DataSourceMetricsCollector findFor(DataSource dataSource) {
        for (DataSourceMetricsCollector collector : collectors) {
            try {
                if (collector.supports(dataSource)) {
                    return collector;
                }
            } catch (Throwable t) {
                // 单个适配器 supports 异常不影响其他适配器
            }
        }
        return null;
    }

    /** 识别连接池类型（用于发现阶段），无法识别返回 {@link PoolSnapshot#POOL_TYPE_UNKNOWN}。 */
    public String detectPoolType(DataSource dataSource) {
        for (DataSourceMetricsCollector collector : collectors) {
            try {
                String type = collector.detectPoolType(dataSource);
                if (type != null) {
                    return type;
                }
            } catch (Throwable t) {
                // 忽略单个适配器异常，继续探测
            }
        }
        return PoolSnapshot.POOL_TYPE_UNKNOWN;
    }

    public String detectPoolName(DataSource dataSource) {
        for (DataSourceMetricsCollector collector : collectors) {
            try {
                String name = collector.detectPoolName(dataSource);
                if (name != null && name.trim().length() > 0) {
                    return name;
                }
            } catch (Throwable t) {
                // 忽略
            }
        }
        return null;
    }

    public String detectPoolVersion(DataSource dataSource) {
        for (DataSourceMetricsCollector collector : collectors) {
            try {
                String version = collector.detectPoolVersion(dataSource);
                if (version != null && version.trim().length() > 0) {
                    return version;
                }
            } catch (Throwable t) {
                // 忽略
            }
        }
        return null;
    }
}
