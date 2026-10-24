package com.connectionmonitor.starter.discovery;

import com.connectionmonitor.starter.collector.CollectorChain;
import com.connectionmonitor.starter.model.DataSourceDescriptor;
import com.connectionmonitor.starter.model.PoolSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据源自动发现服务：以 Spring 容器中的实际 DataSource Bean 为发现入口。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>每个上报周期重新执行发现，支持运行期动态注册、初始化顺序不固定的数据源；</li>
 *   <li>按真实连接池实例（解包后）做身份去重：同一个池被多个 Bean / 多个 SqlSessionFactory
 *       引用时只采集一次，不按 Mapper 或 SqlSessionFactory 数量统计；</li>
 *   <li>不要求 @Primary、不要求 Bean 名称固定，全部 DataSource Bean 平等参与发现；</li>
 *   <li>路由数据源：本身记录为 UNKNOWN 条目（指标不可用），底层池以 {@code :route:key}
 *       后缀单独注册且与 Bean 直接注册的同一实例去重，不重复计入总量；</li>
 *   <li>任何发现失败都被隔离，绝不导致业务应用启动或运行失败。</li>
 * </ul>
 */
public class DataSourceDiscoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceDiscoveryService.class);

    private final ApplicationContext applicationContext;
    private final DataSourceUnwrapper unwrapper;
    private final RoutingDataSourceResolver routingResolver;
    private final InstanceIdProvider instanceIdProvider;
    private final CollectorChain collectorChain;

    public DataSourceDiscoveryService(ApplicationContext applicationContext,
                                      DataSourceUnwrapper unwrapper,
                                      RoutingDataSourceResolver routingResolver,
                                      InstanceIdProvider instanceIdProvider,
                                      CollectorChain collectorChain) {
        this.applicationContext = applicationContext;
        this.unwrapper = unwrapper;
        this.routingResolver = routingResolver;
        this.instanceIdProvider = instanceIdProvider;
        this.collectorChain = collectorChain;
    }

    /** 执行一次完整发现。任何异常都在内部消化，失败时返回上一周期可用条目数的空列表。 */
    public List<DiscoveredDataSource> discover() {
        List<DiscoveredDataSource> discovered = new ArrayList<DiscoveredDataSource>();
        Set<DataSource> seenOriginals = Collections.newSetFromMap(new IdentityHashMap<DataSource, Boolean>());
        Set<DataSource> seenPools = Collections.newSetFromMap(new IdentityHashMap<DataSource, Boolean>());

        Map<String, DataSource> beans = findDataSourceBeans();
        for (Map.Entry<String, DataSource> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            DataSource dataSource = entry.getValue();
            if (dataSource == null || !seenOriginals.add(dataSource)) {
                continue;
            }
            try {
                processBean(beanName, dataSource, discovered, seenPools);
            } catch (Throwable t) {
                LOG.warn("发现数据源 Bean [{}] 失败，本周期跳过: {}", beanName, t.toString());
            }
        }
        return discovered;
    }

    private void processBean(String beanName, DataSource dataSource,
                             List<DiscoveredDataSource> discovered, Set<DataSource> seenPools) {
        if (unwrapper.isRoutingDataSource(dataSource)) {
            // 路由数据源本身：记录为 UNKNOWN（指标不可用，不伪造 0），由降级处理器输出
            register(beanName, dataSource, null, discovered, seenPools);
            Map<String, DataSource> targets = routingResolver.resolveTargets(dataSource);
            for (Map.Entry<String, DataSource> target : targets.entrySet()) {
                if (target.getValue() == null) {
                    continue;
                }
                try {
                    DataSource pool = unwrapper.unwrap(target.getValue());
                    register(beanName, pool, target.getKey(), discovered, seenPools);
                } catch (Throwable t) {
                    LOG.warn("解析路由目标 [{}#{}] 失败: {}", beanName, target.getKey(), t.toString());
                }
            }
        } else {
            DataSource pool = unwrapper.unwrap(dataSource);
            register(beanName, pool, null, discovered, seenPools);
        }
    }

    private void register(String beanName, DataSource pool, String routeKey,
                          List<DiscoveredDataSource> discovered, Set<DataSource> seenPools) {
        if (pool == null || !seenPools.add(pool)) {
            return;
        }
        String instanceId = instanceIdProvider.get();
        String idSuffix = routeKey == null ? beanName : beanName + ":route:" + routeKey;
        String dataSourceId = instanceId + ":" + idSuffix;

        String poolType = PoolSnapshot.POOL_TYPE_UNKNOWN;
        String poolName = beanName;
        String poolVersion = null;
        try {
            poolType = collectorChain.detectPoolType(pool);
            String configuredName = collectorChain.detectPoolName(pool);
            if (configuredName != null && configuredName.trim().length() > 0) {
                poolName = configuredName.trim();
            }
            poolVersion = collectorChain.detectPoolVersion(pool);
        } catch (Throwable t) {
            LOG.debug("识别连接池 [{}] 元信息失败: {}", beanName, t.toString());
        }

        DataSourceDescriptor descriptor = new DataSourceDescriptor(
                dataSourceId, beanName, poolName, poolType, poolVersion, instanceId);
        LOG.debug("发现连接池: {}", descriptor);
        discovered.add(new DiscoveredDataSource(descriptor, pool));
    }

    /**
     * 获取容器中全部 DataSource Bean，按名称排序保证确定性。
     * 优先使用 ConfigurableListableBeanFactory（allowEagerInit=false，避免仅为类型判断
     * 而提前初始化 FactoryBean）；对延迟初始化的 Bean 显式 getBean 触发创建，
     * 单个 Bean 创建失败只影响该 Bean。
     */
    private Map<String, DataSource> findDataSourceBeans() {
        Map<String, DataSource> result = new LinkedHashMap<String, DataSource>();
        try {
            ListableBeanFactory beanFactory = resolveBeanFactory();
            if (beanFactory instanceof ConfigurableListableBeanFactory) {
                ConfigurableListableBeanFactory configurable =
                        (ConfigurableListableBeanFactory) beanFactory;
                String[] names = configurable.getBeanNamesForType(DataSource.class, true, false);
                Arrays.sort(names);
                for (String name : names) {
                    try {
                        DataSource ds = configurable.getBean(name, DataSource.class);
                        result.put(name, ds);
                    } catch (Throwable t) {
                        LOG.warn("获取数据源 Bean [{}] 失败，本周期跳过: {}", name, t.toString());
                    }
                }
            } else {
                Map<String, DataSource> beans = beanFactory.getBeansOfType(DataSource.class);
                List<String> names = new ArrayList<String>(beans.keySet());
                Collections.sort(names);
                for (String name : names) {
                    result.put(name, beans.get(name));
                }
            }
        } catch (Throwable t) {
            LOG.warn("数据源发现周期执行失败: {}", t.toString());
        }
        return result;
    }

    private ListableBeanFactory resolveBeanFactory() {
        if (applicationContext instanceof ConfigurableApplicationContext) {
            return ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        }
        return applicationContext;
    }
}
