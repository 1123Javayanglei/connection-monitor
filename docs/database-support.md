# 数据库支持与扩展

服务端默认使用 H2 开箱体验，生产支持 MySQL / MariaDB、GaussDB / openGauss、PostgreSQL。
客户端 Starter 与数据库类型无关：它从 Druid / HikariCP 读取连接池指标，业务使用哪种数据库都可以。

## 1. 支持矩阵

| 数据库 | 方言 ID | 内置建表脚本 | JDBC URL 示例 | 驱动 |
| --- | --- | --- | --- | --- |
| H2 | `h2` | `db/schema-h2.sql` | `jdbc:h2:file:./data/connection-monitor;MODE=MySQL` | 内置 `com.h2database:h2` |
| MySQL / MariaDB | `mysql` | `db/schema-mysql.sql` | `jdbc:mysql://host:3306/connection_monitor` | 内置 `mysql:mysql-connector-java` |
| GaussDB / openGauss | `gaussdb` | `db/schema-gaussdb.sql` | `jdbc:gaussdb://host:8000/connection_monitor`、`jdbc:opengauss://...`、`jdbc:postgresql://...` | 需自行提供 |
| PostgreSQL | `postgresql` | `db/schema-postgresql.sql` | `jdbc:postgresql://host:5432/connection_monitor` | 需自行提供 |
| 其他数据库 | 自定义 | 自定义 `schema-location` | 以数据库为准 | 需自行提供 |

## 2. 方言自动识别

解析顺序：

1. `connection-monitor.storage.dialect` 显式配置（非 `auto`）；
2. JDBC URL 前缀；
3. `DatabaseMetaData.getDatabaseProductName()`。

GaussDB 匹配规则覆盖：

- URL：`jdbc:gaussdb:`、`jdbc:opengauss:`；
- 产品名：包含 `GaussDB`、`openGauss`、`Gauss`（含 `GaussDB(DWS)`）。

GaussDB 100（Oracle 兼容，`jdbc:zenith:@`）与上述 PostgreSQL 兼容版本语法不同，未内置；
如需支持，请参考第 5 节新增方言与建表脚本。

H2 即使使用 `MODE=PostgreSQL` 也会优先识别为 `h2`，避免误用 PostgreSQL 脚本。

识别不到时启动会给出明确错误；如果数据库由 DBA 管理、表已存在，可设置
`connection-monitor.storage.init-schema=false`。

## 3. 配置项

```yaml
connection-monitor:
  storage:
    # auto / h2 / mysql / gaussdb / postgresql，或自定义方言 Bean 的 id
    dialect: auto
    # 启动时执行建表脚本；已有表结构时可关闭
    init-schema: true
    # 自定义脚本，classpath: 或 file:，多个用英文逗号分隔；配置后优先于内置脚本
    # schema-location: classpath:db/schema-custom.sql
```

环境变量：`MONITOR_STORAGE_DIALECT`、`MONITOR_STORAGE_INIT_SCHEMA`。

## 4. GaussDB / openGauss 接入

### 4.1 驱动

高斯 JDBC 驱动通常不在 Maven Central（openGauss 也可使用 PostgreSQL 驱动），
需要从数据库厂商获取并随服务端提供。常见组合：

| 场景 | 驱动类 | URL 前缀 |
| --- | --- | --- |
| GaussDB（较新驱动） | `com.huawei.gaussdb.jdbc.Driver` | `jdbc:gaussdb:` |
| GaussDB / openGauss（gsjdbc200） | `com.huawei.gauss200.jdbc.Driver` | `jdbc:gaussdb:` |
| openGauss（gsjdbc4，PostgreSQL 兼容） | `org.postgresql.Driver` | `jdbc:postgresql:` |

> `jdbc:opengauss:` 需使用支持该前缀的 openGauss 驱动（如 opengauss-jdbc），
> 不能配置 `org.postgresql.Driver`。

将驱动 jar 放到独立目录，通过 Spring Boot `PropertiesLauncher` 加载：

```bash
mkdir -p /opt/connection-monitor/lib
cp gaussdbjdbc.jar /opt/connection-monitor/lib/

export MONITOR_DATASOURCE_URL='jdbc:gaussdb://<host>:8000/connection_monitor'
export MONITOR_DATASOURCE_USERNAME=monitor
export MONITOR_DATASOURCE_PASSWORD='******'
export MONITOR_DATASOURCE_DRIVER='com.huawei.gaussdb.jdbc.Driver'
export MONITOR_STORAGE_DIALECT='gaussdb'   # 可选，auto 通常也能识别

java -Dloader.path=/opt/connection-monitor/lib \
     -cp connection-monitor-server-1.0.0-SNAPSHOT.jar \
     org.springframework.boot.loader.PropertiesLauncher
```

也可以在构建时将驱动安装到本地 Maven 仓库并加入 `connection-monitor-server` 的运行时依赖。

> `jdbc:gaussdb:` / `jdbc:opengauss:` 不在 Spring Boot 内置驱动推断列表，必须显式设置
> `MONITOR_DATASOURCE_DRIVER`；使用 PostgreSQL 兼容 URL `jdbc:postgresql:` 时可自动推断。

### 4.2 建表

启动时自动执行 `db/schema-gaussdb.sql`（`CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`）。
脚本使用 `BIGSERIAL`、`DOUBLE PRECISION`、`TEXT` 等 PostgreSQL 兼容类型，不使用 MySQL 的
`AUTO_INCREMENT`、`ENGINE=InnoDB`、`ON DUPLICATE KEY UPDATE`。

如果数据库版本不支持 `CREATE INDEX IF NOT EXISTS`，自动初始化会继续执行建表并在日志中提示索引语句失败；
核心表仍会创建，可稍后由 DBA 手工补建索引，或设置 `init-schema=false` 后一次性执行脚本。

## 5. 新增其他数据库

服务端业务 SQL 已统一为 ANSI 风格，“先 UPDATE 后 INSERT”的 upsert 不依赖任何数据库专有语法。
新增数据库通常只需要两步（JDBC 驱动另需提供）：

### 5.1 提供建表脚本

在 `connection-monitor-server/src/main/resources/db/` 下新增 `schema-<id>.sql`，至少包含三张表：

- `monitor_pool_latest`：唯一键 `(instance_id, pool_id)`；
- `monitor_pool_history`：索引 `(instance_id, pool_id, sample_time)`；
- `monitor_instance`：主键 `instance_id`。

字段名、可空性与现有脚本保持一致即可；主键自增列 `id` 未被业务读取，可按数据库习惯调整。

### 5.2 注册方言（二选一）

**方式 A：自定义建表脚本 + 现有方言**

如果新数据库兼容 MySQL 或 PostgreSQL 语法，直接复用现有方言，并指定脚本：

```yaml
connection-monitor:
  storage:
    dialect: postgresql
    schema-location: classpath:db/schema-my-db.sql
```

**方式 B：注册自定义方言 Bean**

在服务端增加一个配置类：

```java
@Configuration
public class MyDatabaseDialectConfiguration {

    @Bean
    public DatabaseDialect myDatabaseDialect() {
        return new DatabaseDialect() {
            public String getId() { return "mydb"; }
            public String getDisplayName() { return "My Database"; }
            public boolean matches(String url, String product) {
                return url != null && url.startsWith("jdbc:mydb:");
            }
            public String getSchemaResource() { return "db/schema-mydb.sql"; }
        };
    }
}
```

然后配置 `connection-monitor.storage.dialect=mydb`（或依赖 `matches` 自动识别）。
自定义方言 Bean 会与内置方言一起参与解析，无需修改 `MonitorRepository`。

### 5.3 驱动

把数据库 JDBC 驱动加入服务端运行时 classpath。内置数据库驱动依赖只包含 MySQL 与 H2；
GaussDB / PostgreSQL 等驱动按需提供，避免服务端包体膨胀和驱动冲突。

## 6. SQL 兼容性约定

为避免再次绑定单一数据库，服务端代码遵循：

- 不使用 `ON DUPLICATE KEY UPDATE`、`REPLACE INTO`、`INSERT IGNORE` 等 MySQL 专有 upsert；
- 不使用 `LIMIT`、`TOP` 等分页方言（历史查询在应用层分桶/抽样）；
- 时间统一存 `BIGINT` epoch 毫秒，不依赖数据库时间类型差异；
- 缺失指标存 `NULL`，不伪造为 0；
- 建表脚本按方言隔离，业务 SQL 不拼接数据库品牌判断。

新增数据库时如遇到特殊语法，优先通过 `schema-location` 或自定义方言解决，
不要把数据库判断散落到 Repository/Controller 中。
