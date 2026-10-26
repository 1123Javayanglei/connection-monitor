package com.connectionmonitor.server.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 启动时按数据库方言执行建表脚本。
 *
 * <p>内置脚本均使用 {@code CREATE TABLE IF NOT EXISTS}（索引随表定义或使用
 * {@code CREATE INDEX IF NOT EXISTS}）保证幂等；已由 DBA 手工建表或希望自行管理 DDL 时，可设置
 * {@code connection-monitor.storage.init-schema=false}。</p>
 */
public class SchemaInitializer implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);

    /** 建表后必须存在的三张表，用于兜底校验 continueOnError 掩盖的严重错误。 */
    private static final String[] REQUIRED_TABLES = {
            "monitor_pool_latest", "monitor_pool_history", "monitor_instance"
    };

    private final DataSource dataSource;
    private final DatabaseDialectResolver dialectResolver;
    private final StorageProperties properties;
    private final ResourceLoader resourceLoader;

    public SchemaInitializer(DataSource dataSource,
                             DatabaseDialectResolver dialectResolver,
                             StorageProperties properties,
                             ResourceLoader resourceLoader) {
        this.dataSource = dataSource;
        this.dialectResolver = dialectResolver;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isInitSchema()) {
            LOG.info("connection-monitor.storage.init-schema=false，跳过自动建表");
            return;
        }

        // 配置了自定义脚本时不再强制识别方言，便于接入尚未内置的数据库。
        boolean customSchema = properties.getSchemaLocation() != null
                && !properties.getSchemaLocation().trim().isEmpty();
        DatabaseDialect dialect = customSchema ? null : dialectResolver.resolve(dataSource);
        List<String> locations = resolveLocations(dialect);
        if (locations.isEmpty()) {
            throw new IllegalStateException(
                    "无法识别数据库方言，且未配置 connection-monitor.storage.schema-location。"
                            + "请显式配置 connection-monitor.storage.dialect（h2/mysql/gaussdb/postgresql），"
                            + "或提供自定义建表脚本；如已手工建表，可设置 "
                            + "connection-monitor.storage.init-schema=false。");
        }

        for (String location : locations) {
            execute(location);
        }
        verifyCoreTables();
        LOG.info("connection-monitor 存储初始化完成，方言：{}",
                dialect == null ? "custom" : dialect.getId() + "（" + dialect.getDisplayName() + "）");
    }

    private List<String> resolveLocations(DatabaseDialect dialect) {
        String custom = properties.getSchemaLocation();
        if (custom != null && !custom.trim().isEmpty()) {
            List<String> locations = new ArrayList<String>();
            for (String part : custom.split(",")) {
                if (!part.trim().isEmpty()) {
                    locations.add(part.trim());
                }
            }
            return locations;
        }
        if (dialect == null || dialect.getSchemaResource() == null
                || dialect.getSchemaResource().trim().isEmpty()) {
            return Collections.emptyList();
        }
        String schemaResource = dialect.getSchemaResource().trim();
        String location = schemaResource.contains(":") ? schemaResource : "classpath:" + schemaResource;
        return Collections.singletonList(location);
    }

    private void execute(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("建表脚本不存在: " + location);
        }

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(resource);
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        // 允许“索引已存在”等幂等错误继续执行；脚本结束后再校验三张核心表。
        populator.setContinueOnError(true);

        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            populator.populate(connection);
            if (!autoCommit) {
                connection.commit();
            }
            LOG.info("建表脚本执行完成: {}", location);
        } catch (SQLException e) {
            throw new IllegalStateException("执行建表脚本失败 " + location + ": " + e.getMessage(), e);
        } catch (ScriptException e) {
            throw new IllegalStateException("解析建表脚本失败 " + location + ": " + e.getMessage(), e);
        }
    }

    private void verifyCoreTables() {
        try (Connection connection = dataSource.getConnection()) {
            verifyRequiredTables(connection);
            LOG.info("核心表校验通过");
        } catch (SQLException e) {
            throw new IllegalStateException("核心表校验失败: " + e.getMessage(), e);
        }
    }

    private void verifyRequiredTables(Connection connection) throws SQLException {
        for (String table : REQUIRED_TABLES) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1 FROM " + table + " WHERE 1 = 0")) {
                // 仅验证表存在且可访问，不读取数据。
            } catch (SQLException e) {
                throw new SQLException("建表后缺少或无法访问表 " + table + ": " + e.getMessage(), e);
            }
        }
    }
}
