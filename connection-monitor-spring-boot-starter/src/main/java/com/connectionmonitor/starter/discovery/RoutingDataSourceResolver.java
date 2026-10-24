package com.connectionmonitor.starter.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 路由数据源（AbstractRoutingDataSource 体系）底层目标解析器。
 *
 * <p>只读方式访问 {@code getResolvedDataSources()} / {@code getResolvedDefaultDataSource()}：
 * 这两个方法仅返回 afterPropertiesSet 阶段缓存好的 Map，不触发路由选择、不执行 SQL、不创建连接。
 * 路由数据源尚未初始化完成时返回空 Map，安全降级为"仅记录路由数据源本身"。</p>
 */
public class RoutingDataSourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingDataSourceResolver.class);

    private final DataSourceUnwrapper unwrapper;

    public RoutingDataSourceResolver(DataSourceUnwrapper unwrapper) {
        this.unwrapper = unwrapper;
    }

    /**
     * 返回路由数据源的底层目标池：key 为路由键的字符串形式（确定性），value 为底层 DataSource。
     * 无法安全识别时返回空 Map。
     */
    public Map<String, DataSource> resolveTargets(DataSource routingDataSource) {
        if (routingDataSource == null || !unwrapper.isRoutingDataSource(routingDataSource)) {
            return Collections.emptyMap();
        }
        Map<String, DataSource> targets = new LinkedHashMap<String, DataSource>();
        try {
            Map<?, ?> resolved = unwrapper.invokeWhitelisted(routingDataSource, "getResolvedDataSources", Map.class);
            if (resolved != null) {
                for (Map.Entry<?, ?> entry : resolved.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    if (key != null && value instanceof DataSource) {
                        targets.put(String.valueOf(key), (DataSource) value);
                    }
                }
            }
            DataSource defaultTarget = unwrapper.invokeWhitelisted(
                    routingDataSource, "getResolvedDefaultDataSource", DataSource.class);
            if (defaultTarget != null && !containsIdentity(targets, defaultTarget)) {
                targets.put("default", defaultTarget);
            }
        } catch (Throwable t) {
            LOG.debug("解析路由数据源 [{}] 底层目标失败: {}", routingDataSource.getClass().getName(), t.toString());
            return Collections.emptyMap();
        }
        return targets;
    }

    private boolean containsIdentity(Map<String, DataSource> targets, DataSource candidate) {
        for (DataSource ds : targets.values()) {
            if (ds == candidate) {
                return true;
            }
        }
        return false;
    }
}
