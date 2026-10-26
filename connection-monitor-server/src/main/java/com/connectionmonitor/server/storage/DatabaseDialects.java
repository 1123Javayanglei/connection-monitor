package com.connectionmonitor.server.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 内置数据库方言。
 *
 * <p>扩展新数据库的推荐方式：</p>
 * <ol>
 *   <li>实现 {@link DatabaseDialect} 并注册为 Spring Bean（或在此登记）；</li>
 *   <li>提供对应的 {@code db/schema-<id>.sql}；</li>
 *   <li>服务端写入 SQL 已使用可移植写法，通常无需修改 Repository。</li>
 * </ol>
 */
public final class DatabaseDialects {

    /** H2：开发/测试默认库，同时兼容 MySQL 模式。 */
    public static final DatabaseDialect H2 = new SimpleDatabaseDialect(
            "h2", "H2",
            "db/schema-h2.sql",
            prefixes("jdbc:h2:"),
            tokens("h2"));

    /** MySQL / MariaDB：生产常用库，建表脚本使用 MySQL 语法。 */
    public static final DatabaseDialect MYSQL = new SimpleDatabaseDialect(
            "mysql", "MySQL / MariaDB",
            "db/schema-mysql.sql",
            prefixes("jdbc:mysql:", "jdbc:mariadb:"),
            tokens("mysql", "mariadb"));

    /**
     * GaussDB / openGauss：兼容 PostgreSQL 方言。
     *
     * <p>兼容多种驱动与 URL：</p>
     * <ul>
     *   <li>GaussDB：{@code jdbc:gaussdb://...}</li>
     *   <li>openGauss：{@code jdbc:opengauss://...} 或 PostgreSQL 兼容 URL</li>
     * </ul>
     *
     * <p>GaussDB 100（Oracle 兼容，{@code jdbc:zenith:@}）语法不同，不在本方言范围内。</p>
     */
    public static final DatabaseDialect GAUSSDB = new SimpleDatabaseDialect(
            "gaussdb", "GaussDB / openGauss",
            "db/schema-gaussdb.sql",
            prefixes("jdbc:gaussdb:", "jdbc:opengauss:"),
            tokens("gaussdb", "opengauss", "gauss"),
            prefixes("jdbc:zenith:"));

    /** PostgreSQL：与 GaussDB 共用一套 PostgreSQL 兼容 DDL 结构。 */
    public static final DatabaseDialect POSTGRESQL = new SimpleDatabaseDialect(
            "postgresql", "PostgreSQL",
            "db/schema-postgresql.sql",
            prefixes("jdbc:postgresql:"),
            tokens("postgresql", "postgres"));

    private DatabaseDialects() {
    }

    /** 内置方言列表；顺序即自动识别的优先级。 */
    public static List<DatabaseDialect> builtIn() {
        return Collections.unmodifiableList(Arrays.asList(H2, MYSQL, GAUSSDB, POSTGRESQL));
    }

    private static List<String> prefixes(String... values) {
        return Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(values)));
    }

    private static List<String> tokens(String... values) {
        return Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(values)));
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static final class SimpleDatabaseDialect implements DatabaseDialect {

        private final String id;
        private final String displayName;
        private final String schemaResource;
        private final List<String> jdbcUrlPrefixes;
        private final List<String> productNameTokens;
        private final List<String> excludedJdbcUrlPrefixes;

        private SimpleDatabaseDialect(String id, String displayName, String schemaResource,
                                      List<String> jdbcUrlPrefixes, List<String> productNameTokens) {
            this(id, displayName, schemaResource, jdbcUrlPrefixes, productNameTokens,
                    Collections.<String>emptyList());
        }

        private SimpleDatabaseDialect(String id, String displayName, String schemaResource,
                                      List<String> jdbcUrlPrefixes, List<String> productNameTokens,
                                      List<String> excludedJdbcUrlPrefixes) {
            this.id = id;
            this.displayName = displayName;
            this.schemaResource = schemaResource;
            this.jdbcUrlPrefixes = jdbcUrlPrefixes;
            this.productNameTokens = productNameTokens;
            this.excludedJdbcUrlPrefixes = excludedJdbcUrlPrefixes;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public boolean matches(String jdbcUrl, String productName) {
            String url = lower(jdbcUrl);
            for (String prefix : excludedJdbcUrlPrefixes) {
                if (url.startsWith(prefix)) {
                    return false;
                }
            }
            for (String prefix : jdbcUrlPrefixes) {
                if (url.startsWith(prefix)) {
                    return true;
                }
            }
            String product = lower(productName);
            for (String token : productNameTokens) {
                if (product.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String getSchemaResource() {
            return schemaResource;
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
