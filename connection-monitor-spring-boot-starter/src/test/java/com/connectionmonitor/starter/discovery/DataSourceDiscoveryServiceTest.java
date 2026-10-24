package com.connectionmonitor.starter.discovery;

import com.connectionmonitor.starter.collector.CollectorChain;
import com.connectionmonitor.starter.collector.DruidMetricsCollector;
import com.connectionmonitor.starter.collector.HikariMetricsCollector;
import com.connectionmonitor.starter.collector.UnsupportedDataSourceCollector;
import com.connectionmonitor.starter.model.PoolSnapshot;
import com.connectionmonitor.starter.support.TestDataSources;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发现服务单元测试：按真实连接池实例去重、代理/裸 Bean 合并、路由数据源展开、ID 稳定性。
 */
class DataSourceDiscoveryServiceTest {

    private AnnotationConfigApplicationContext context;
    private DataSourceDiscoveryService service;
    private final List<HikariDataSource> pools = new ArrayList<HikariDataSource>();

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.refresh();
        service = newService("fixed-instance");
    }

    @AfterEach
    void tearDown() {
        for (HikariDataSource pool : pools) {
            pool.close();
        }
        context.close();
    }

    private DataSourceDiscoveryService newService(String instanceId) {
        DataSourceUnwrapper unwrapper = new DataSourceUnwrapper();
        return new DataSourceDiscoveryService(context, unwrapper,
                new RoutingDataSourceResolver(unwrapper),
                new InstanceIdProvider(new StandardEnvironment(), instanceId),
                new CollectorChain(Arrays.asList(
                        new DruidMetricsCollector(),
                        new HikariMetricsCollector(),
                        new UnsupportedDataSourceCollector())));
    }

    private HikariDataSource registerPool(String beanName, String poolName) {
        HikariDataSource pool = TestDataSources.hikari(poolName, "discovery-" + beanName);
        pools.add(pool);
        context.getBeanFactory().registerSingleton(beanName, pool);
        return pool;
    }

    @Test
    void samePoolUnderTwoBeanNamesIsDiscoveredOnce() {
        HikariDataSource pool = TestDataSources.hikari("shared", "discovery-shared");
        pools.add(pool);
        context.getBeanFactory().registerSingleton("firstDataSource", pool);
        context.getBeanFactory().registerSingleton("aliasDataSource", pool);

        List<DiscoveredDataSource> discovered = service.discover();
        assertEquals(1, discovered.size());
        // Bean 名称按字母序确定性地选择第一个注册名（aliasDataSource < firstDataSource）
        assertEquals("fixed-instance:aliasDataSource", discovered.get(0).getDescriptor().getDataSourceId());
    }

    @Test
    void proxyAndRawBeanCollapseToSinglePool() {
        HikariDataSource pool = registerPool("rawDataSource", "raw-pool");
        LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(pool);
        context.getBeanFactory().registerSingleton("proxiedDataSource", proxy);

        List<DiscoveredDataSource> discovered = service.discover();
        assertEquals(1, discovered.size());
        assertEquals(PoolSnapshot.POOL_TYPE_HIKARI, discovered.get(0).getDescriptor().getPoolType());
    }

    @Test
    void twoIndependentPoolsGetDistinctStableIds() {
        registerPool("masterDataSource", "master");
        registerPool("slaveDataSource", "slave");

        List<DiscoveredDataSource> first = service.discover();
        List<DiscoveredDataSource> second = service.discover();

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertEquals(ids(first), ids(second), "两次发现周期 ID 必须稳定");
        assertTrue(ids(first).contains("fixed-instance:masterDataSource"));
        assertTrue(ids(first).contains("fixed-instance:slaveDataSource"));
    }

    @Test
    void poolsWithSamePoolNameKeepIndependentIds() {
        registerPool("firstDataSource", "dup-pool");
        registerPool("secondDataSource", "dup-pool");

        List<DiscoveredDataSource> discovered = service.discover();
        assertEquals(2, discovered.size());
        assertEquals("dup-pool", discovered.get(0).getDescriptor().getPoolName());
        assertEquals("dup-pool", discovered.get(1).getDescriptor().getPoolName());
        assertTrue(!discovered.get(0).getDescriptor().getDataSourceId()
                .equals(discovered.get(1).getDescriptor().getDataSourceId()));
    }

    private AbstractRoutingDataSource newRoutingDataSource(Map<Object, Object> targets, Object defaultTarget) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return targets.keySet().iterator().next();
            }
        };
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(defaultTarget);
        try {
            routing.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return routing;
    }

    @Test
    void routingDataSourceItselfPlusDedupedTargets() {
        HikariDataSource master = registerPool("masterDataSource", "master");
        HikariDataSource slave = registerPool("slaveDataSource", "slave");

        Map<Object, Object> targets = new LinkedHashMap<Object, Object>();
        targets.put("master", master);
        targets.put("slave", slave);
        context.getBeanFactory().registerSingleton("routingDataSource",
                newRoutingDataSource(targets, master));

        List<DiscoveredDataSource> discovered = service.discover();
        // 路由数据源本身（UNKNOWN）+ 两个底层池，底层池与 Bean 注册的同一实例去重，不重复统计
        assertEquals(3, discovered.size());

        DiscoveredDataSource routingEntry = null;
        int hikariCount = 0;
        for (DiscoveredDataSource item : discovered) {
            if (PoolSnapshot.POOL_TYPE_HIKARI.equals(item.getDescriptor().getPoolType())) {
                hikariCount++;
            }
            if ("fixed-instance:routingDataSource".equals(item.getDescriptor().getDataSourceId())) {
                routingEntry = item;
            }
        }
        assertEquals(2, hikariCount, "路由与底层池同时出现时，真实 Hikari 池只统计 2 个");
        assertNotNull(routingEntry);
        assertEquals(PoolSnapshot.POOL_TYPE_UNKNOWN, routingEntry.getDescriptor().getPoolType());
        assertEquals("fixed-instance:masterDataSource", ids(discovered).get(0));
    }

    @Test
    void poolsOnlyReachableThroughRoutingGetRouteSuffix() {
        HikariDataSource hidden = TestDataSources.hikari("hidden", "discovery-hidden");
        pools.add(hidden);

        Map<Object, Object> targets = new HashMap<Object, Object>();
        targets.put("shadow", hidden);
        context.getBeanFactory().registerSingleton("routingDataSource",
                newRoutingDataSource(targets, hidden));

        List<DiscoveredDataSource> discovered = service.discover();
        assertEquals(2, discovered.size());
        assertTrue(ids(discovered).contains("fixed-instance:routingDataSource:route:shadow"));
        for (DiscoveredDataSource item : discovered) {
            if (item.getDescriptor().getDataSourceId().endsWith(":route:shadow")) {
                assertEquals(PoolSnapshot.POOL_TYPE_HIKARI, item.getDescriptor().getPoolType());
                assertEquals("hidden", item.getDescriptor().getPoolName());
            }
        }
    }

    @Test
    void brokenBeanDoesNotAffectOthers() {
        registerPool("goodDataSource", "good-pool");
        context.getBeanFactory().registerSingleton("brokenDataSource", new BrokenDataSource());

        List<DiscoveredDataSource> discovered = service.discover();
        assertEquals(2, discovered.size());
        assertTrue(ids(discovered).contains("fixed-instance:goodDataSource"));
    }

    private List<String> ids(List<DiscoveredDataSource> discovered) {
        List<String> ids = new ArrayList<String>();
        for (DiscoveredDataSource item : discovered) {
            ids.add(item.getDescriptor().getDataSourceId());
        }
        return ids;
    }

    /** 一个行为异常的 DataSource：toString/equals 抛异常，模拟容器中的坏 Bean。 */
    static class BrokenDataSource implements DataSource {
        @Override
        public String toString() {
            throw new IllegalStateException("broken");
        }

        public java.sql.Connection getConnection() {
            throw new UnsupportedOperationException();
        }

        public java.sql.Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }

        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        public java.io.PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }

        public void setLogWriter(java.io.PrintWriter out) {
            throw new UnsupportedOperationException();
        }

        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException();
        }

        public int getLoginTimeout() {
            throw new UnsupportedOperationException();
        }

        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException();
        }
    }
}
