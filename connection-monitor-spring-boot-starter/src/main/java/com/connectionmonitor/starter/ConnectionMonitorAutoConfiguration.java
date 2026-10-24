package com.connectionmonitor.starter;

import com.connectionmonitor.starter.collector.CollectorChain;
import com.connectionmonitor.starter.collector.DataSourceMetricsCollector;
import com.connectionmonitor.starter.collector.DruidMetricsCollector;
import com.connectionmonitor.starter.collector.HikariMetricsCollector;
import com.connectionmonitor.starter.collector.UnsupportedDataSourceCollector;
import com.connectionmonitor.starter.discovery.DataSourceDiscoveryService;
import com.connectionmonitor.starter.discovery.DataSourceUnwrapper;
import com.connectionmonitor.starter.discovery.InstanceIdProvider;
import com.connectionmonitor.starter.discovery.RoutingDataSourceResolver;
import com.connectionmonitor.starter.jvm.JvmMetricsCollector;
import com.connectionmonitor.starter.reporting.HttpMetricsReporter;
import com.connectionmonitor.starter.reporting.MetricsCollectionService;
import com.connectionmonitor.starter.reporting.MetricsReportScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * connection-monitor 客户端自动装配。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>Druid / HikariCP 采集器仅在对应类库存在于 classpath 时创建（可选依赖），
 *       缺失任何连接池都不会导致业务应用启动失败；</li>
 *   <li>所有 Bean 均可被业务应用覆盖（ConditionalOnMissingBean）；</li>
 *   <li>调度器自持线程池，不要求业务应用开启 @EnableScheduling；</li>
 *   <li>通过 connection.monitor.enabled=false 可整体关闭。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "javax.sql.DataSource")
@EnableConfigurationProperties(ConnectionMonitorProperties.class)
@ConditionalOnProperty(prefix = "connection.monitor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ConnectionMonitorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataSourceUnwrapper dataSourceUnwrapper() {
        return new DataSourceUnwrapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoutingDataSourceResolver routingDataSourceResolver(DataSourceUnwrapper unwrapper) {
        return new RoutingDataSourceResolver(unwrapper);
    }

    @Bean
    @ConditionalOnMissingBean(DruidMetricsCollector.class)
    @ConditionalOnClass(name = "com.alibaba.druid.pool.DruidDataSource")
    public DruidMetricsCollector druidMetricsCollector() {
        return new DruidMetricsCollector();
    }

    @Bean
    @ConditionalOnMissingBean(HikariMetricsCollector.class)
    @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
    public HikariMetricsCollector hikariMetricsCollector() {
        return new HikariMetricsCollector();
    }

    @Bean
    @ConditionalOnMissingBean(UnsupportedDataSourceCollector.class)
    public UnsupportedDataSourceCollector unsupportedDataSourceCollector() {
        return new UnsupportedDataSourceCollector();
    }

    @Bean
    @ConditionalOnMissingBean
    public CollectorChain collectorChain(List<DataSourceMetricsCollector> collectors) {
        return new CollectorChain(collectors);
    }

    @Bean
    @ConditionalOnMissingBean
    public InstanceIdProvider instanceIdProvider(org.springframework.core.env.Environment environment,
                                                 ConnectionMonitorProperties properties) {
        return new InstanceIdProvider(environment, properties.getInstanceId());
    }

    @Bean
    @ConditionalOnMissingBean
    public DataSourceDiscoveryService dataSourceDiscoveryService(ApplicationContext applicationContext,
                                                                 DataSourceUnwrapper unwrapper,
                                                                 RoutingDataSourceResolver routingResolver,
                                                                 InstanceIdProvider instanceIdProvider,
                                                                 CollectorChain collectorChain) {
        return new DataSourceDiscoveryService(applicationContext, unwrapper, routingResolver,
                instanceIdProvider, collectorChain);
    }

    @Bean
    @ConditionalOnMissingBean
    public JvmMetricsCollector jvmMetricsCollector() {
        return new JvmMetricsCollector();
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsCollectionService metricsCollectionService(DataSourceDiscoveryService discoveryService,
                                                             CollectorChain collectorChain,
                                                             JvmMetricsCollector jvmMetricsCollector,
                                                             InstanceIdProvider instanceIdProvider,
                                                             org.springframework.core.env.Environment environment) {
        return new MetricsCollectionService(discoveryService, collectorChain, jvmMetricsCollector,
                instanceIdProvider, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpMetricsReporter httpMetricsReporter(ConnectionMonitorProperties properties) {
        return new HttpMetricsReporter(properties);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public MetricsReportScheduler metricsReportScheduler(MetricsCollectionService collectionService,
                                                         HttpMetricsReporter reporter,
                                                         ConnectionMonitorProperties properties) {
        return new MetricsReportScheduler(collectionService, reporter, properties);
    }
}
