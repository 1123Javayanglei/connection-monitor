package com.connectionmonitor.starter.discovery;

import com.connectionmonitor.starter.support.TestDataSources;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解包器与路由解析器测试：安全解包、深度上限、白名单反射。
 */
class DataSourceUnwrapperTest {

    private final DataSourceUnwrapper unwrapper = new DataSourceUnwrapper();
    private final RoutingDataSourceResolver resolver = new RoutingDataSourceResolver(unwrapper);

    private HikariDataSource hikari;
    private HikariDataSource targetA;
    private HikariDataSource targetB;

    @BeforeEach
    void setUp() {
        hikari = TestDataSources.hikari("unwrap-test", "unwrapTest");
        targetA = TestDataSources.hikari("route-a", "routeA");
        targetB = TestDataSources.hikari("route-b", "routeB");
    }

    @AfterEach
    void tearDown() {
        closeQuietly(hikari);
        closeQuietly(targetA);
        closeQuietly(targetB);
    }

    @Test
    void unwrapsLazyConnectionProxy() {
        DataSource wrapped = new LazyConnectionDataSourceProxy(hikari);
        assertTrue(unwrapper.isDelegatingWrapper(wrapped));
        assertSamePool(hikari, unwrapper.unwrap(wrapped));
    }

    @Test
    void unwrapsTransactionAwareProxy() {
        DataSource wrapped = new TransactionAwareDataSourceProxy(hikari);
        assertSamePool(hikari, unwrapper.unwrap(wrapped));
    }

    @Test
    void unwrapsNestedProxies() {
        DataSource wrapped = new TransactionAwareDataSourceProxy(new LazyConnectionDataSourceProxy(hikari));
        assertSamePool(hikari, unwrapper.unwrap(wrapped));
    }

    @Test
    void stopsAtMaxDepthInsteadOfInfiniteLoop() {
        DataSource current = hikari;
        for (int i = 0; i < DataSourceUnwrapper.MAX_DEPTH + 5; i++) {
            current = new LazyConnectionDataSourceProxy(current);
        }
        DataSource result = unwrapper.unwrap(current);
        assertNotNull(result);
        assertFalse(result == hikari, "超过深度上限后应停止解包，不再还原最内层");
    }

    @Test
    void plainPoolIsReturnedAsIs() {
        assertFalse(unwrapper.isDelegatingWrapper(hikari));
        assertSamePool(hikari, unwrapper.unwrap(hikari));
    }

    @Test
    void detectsRoutingDataSource() {
        assertFalse(unwrapper.isRoutingDataSource(hikari));
        assertTrue(unwrapper.isRoutingDataSource(routingDataSource()));
    }

    @Test
    void resolverReturnsUnderlyingTargetsOnlyAfterInitialization() throws SQLException {
        AbstractRoutingDataSource routing = routingDataSource();
        // 未调用 afterPropertiesSet：安全降级，返回空 Map，不触发路由选择或连接创建
        assertTrue(resolver.resolveTargets(routing).isEmpty());

        routing.afterPropertiesSet();
        Map<String, DataSource> targets = resolver.resolveTargets(routing);
        assertEquals(2, targets.size());
        assertTrue(targets.containsKey("master"));
        assertTrue(targets.containsKey("slave"));
        assertSamePool(targetA, targets.get("master"));
        assertSamePool(targetB, targets.get("slave"));
    }

    @Test
    void resolverReturnsEmptyForNonRoutingDataSource() {
        assertTrue(resolver.resolveTargets(hikari).isEmpty());
    }

    @Test
    void routingDataSourceDoesNotCreateConnectionsWhenResolved() throws Exception {
        AbstractRoutingDataSource routing = routingDataSource();
        routing.afterPropertiesSet();
        resolver.resolveTargets(routing);
        // 未建立任何业务连接：Hikari 池仍处于未启动状态
        assertNull(targetA.getHikariPoolMXBean());
        assertNull(targetB.getHikariPoolMXBean());
    }

    private AbstractRoutingDataSource routingDataSource() {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return "master";
            }
        };
        Map<Object, Object> targets = new HashMap<Object, Object>();
        targets.put("master", targetA);
        targets.put("slave", targetB);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(targetA);
        return routing;
    }

    private void assertSamePool(DataSource expected, DataSource actual) {
        assertTrue(expected == actual, "应解包到同一个连接池实例");
    }

    @SuppressWarnings("TryWithIdenticalCatches")
    static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignore) {
            // 测试清理忽略
        }
    }
}
