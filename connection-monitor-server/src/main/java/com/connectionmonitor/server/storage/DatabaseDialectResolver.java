package com.connectionmonitor.server.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 数据库方言解析器。
 *
 * <p>解析顺序：</p>
 * <ol>
 *   <li>{@code connection-monitor.storage.dialect} 显式配置（非 auto）；</li>
 *   <li>JDBC URL 前缀；</li>
 *   <li>DatabaseMetaData 数据库产品名。</li>
 * </ol>
 *
 * <p>识别不到时返回 {@code null}，由 {@link SchemaInitializer} 决定回退到自定义脚本
 * 还是给出明确错误。</p>
 */
public class DatabaseDialectResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseDialectResolver.class);

    private static final String AUTO = "auto";

    private final List<DatabaseDialect> dialects;
    private final StorageProperties properties;

    public DatabaseDialectResolver(List<DatabaseDialect> dialects, StorageProperties properties) {
        List<DatabaseDialect> merged = new ArrayList<DatabaseDialect>();
        if (dialects != null) {
            merged.addAll(dialects);
        }
        // 即使调用方只注册了自定义方言，也保证内置方言可用。
        for (DatabaseDialect builtIn : DatabaseDialects.builtIn()) {
            if (findById(merged, builtIn.getId()) == null) {
                merged.add(builtIn);
            }
        }
        this.dialects = Collections.unmodifiableList(merged);
        this.properties = properties == null ? new StorageProperties() : properties;
    }

    /**
     * 解析当前 DataSource 对应的方言；无法识别返回 null。
     */
    public DatabaseDialect resolve(DataSource dataSource) {
        String configured = normalize(properties.getDialect());
        if (!AUTO.equals(configured)) {
            DatabaseDialect dialect = findById(dialects, configured);
            if (dialect == null) {
                throw new IllegalArgumentException("不支持的数据库方言 [" + properties.getDialect()
                        + "]，可选值：auto, " + ids());
            }
            LOG.info("使用显式配置的数据库方言：{}（{}）", dialect.getId(), dialect.getDisplayName());
            return dialect;
        }

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData.getURL();
            String product = metaData.getDatabaseProductName();
            String version = metaData.getDatabaseProductVersion();
            DatabaseDialect dialect = detect(dialects, url, product);
            if (dialect != null) {
                LOG.info("自动识别数据库方言：{}（{} {}）", dialect.getId(), product, version);
            } else {
                LOG.warn("无法自动识别数据库方言：product={}, url={}", product, url);
            }
            return dialect;
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据库元数据失败，无法确定数据库方言: " + e.getMessage(), e);
        }
    }

    /** 纯匹配逻辑，便于单元测试。 */
    public static DatabaseDialect detect(String jdbcUrl, String productName) {
        return detect(DatabaseDialects.builtIn(), jdbcUrl, productName);
    }

    private static DatabaseDialect detect(List<DatabaseDialect> candidates,
                                          String jdbcUrl, String productName) {
        for (DatabaseDialect dialect : candidates) {
            if (dialect.matches(jdbcUrl, productName)) {
                return dialect;
            }
        }
        return null;
    }

    private static DatabaseDialect findById(List<DatabaseDialect> candidates, String id) {
        for (DatabaseDialect dialect : candidates) {
            if (dialect.getId().equalsIgnoreCase(id)) {
                return dialect;
            }
        }
        return null;
    }

    private String ids() {
        StringBuilder builder = new StringBuilder();
        for (DatabaseDialect dialect : dialects) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(dialect.getId());
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return AUTO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("postgres".equals(normalized)) {
            return "postgresql";
        }
        if ("gauss".equals(normalized) || "opengauss".equals(normalized)) {
            return "gaussdb";
        }
        if ("mariadb".equals(normalized)) {
            return "mysql";
        }
        return normalized;
    }
}
