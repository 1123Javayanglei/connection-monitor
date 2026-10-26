package com.connectionmonitor.server.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;
import java.util.List;

/**
 * 存储层装配：内置数据库方言、方言解析器与启动建表。
 *
 * <p>业务应用若要支持新的数据库，只需额外注册一个 {@link DatabaseDialect} Bean
 * （并提供对应的建表脚本），无需修改本配置或 Repository。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    @Bean
    public DatabaseDialect h2DatabaseDialect() {
        return DatabaseDialects.H2;
    }

    @Bean
    public DatabaseDialect mysqlDatabaseDialect() {
        return DatabaseDialects.MYSQL;
    }

    @Bean
    public DatabaseDialect gaussdbDatabaseDialect() {
        return DatabaseDialects.GAUSSDB;
    }

    @Bean
    public DatabaseDialect postgresqlDatabaseDialect() {
        return DatabaseDialects.POSTGRESQL;
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseDialectResolver databaseDialectResolver(List<DatabaseDialect> dialects,
                                                           StorageProperties properties) {
        return new DatabaseDialectResolver(dialects, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemaInitializer schemaInitializer(DataSource dataSource,
                                               DatabaseDialectResolver dialectResolver,
                                               StorageProperties properties,
                                               ResourceLoader resourceLoader) {
        return new SchemaInitializer(dataSource, dialectResolver, properties, resourceLoader);
    }
}
