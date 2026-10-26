# connection-monitor 部署说明

## 1. 环境要求

- **监控服务端**：JDK 8+，MySQL 5.7+ / GaussDB / openGauss / PostgreSQL（或开箱体验用内嵌 H2），独立部署在实体机；
- **业务应用**：JDK 8，Spring Boot 2.4.x（构建验证基于 2.4.13）；
- 网络方向：业务 Pod → 服务端 `8090/tcp`（出站），服务端无需访问业务 Pod。

## 2. 服务端部署（MySQL / GaussDB / PostgreSQL）

### 2.1 建库（MySQL 示例）

```sql
CREATE DATABASE connection_monitor DEFAULT CHARACTER SET utf8mb4;
CREATE USER 'monitor'@'%' IDENTIFIED BY '******';
GRANT ALL PRIVILEGES ON connection_monitor.* TO 'monitor'@'%';
```

执行 `docs/sql/schema-mysql.sql`（应用启动时的方言建表脚本也会幂等建表，
两者任选其一；生产建议提前执行以控制权限）。

### 2.2 配置并启动

```bash
export MONITOR_SERVER_PORT=8090
export MONITOR_DATASOURCE_URL='jdbc:mysql://<host>:3306/connection_monitor?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export MONITOR_DATASOURCE_USERNAME=monitor
export MONITOR_DATASOURCE_PASSWORD='******'
java -jar connection-monitor-server-1.0.0-SNAPSHOT.jar
```

可选配置：

| 环境变量 / 配置 | 默认 | 说明 |
| --- | --- | --- |
| `MONITOR_SERVER_PORT` / `server.port` | `8090` | 服务端口 |
| `connection-monitor.history.retention-days` | `30` | 历史保留天数（每日 03:30 清理） |
| `connection-monitor.storage.dialect` | `auto` | 数据库方言：`auto`/`h2`/`mysql`/`gaussdb`/`postgresql` |
| `connection-monitor.storage.init-schema` | `true` | 启动时是否执行方言建表脚本 |
| `connection-monitor.storage.schema-location` | 空 | 自定义建表脚本（`classpath:` / `file:`，多个用逗号分隔） |

### 2.3 GaussDB / openGauss

1. 由 DBA 创建 `connection_monitor` 数据库（或使用现有库），并确保监控账号具备建表/建索引权限。

2. 将高斯 JDBC 驱动 jar 放到服务端可访问的目录（驱动通常不在 Maven Central，需从数据库厂商获取）：

```bash
mkdir -p /opt/connection-monitor/lib
cp gaussdbjdbc.jar /opt/connection-monitor/lib/
```

3. 配置连接（驱动类以实际 jar 为准，常见为 `com.huawei.gaussdb.jdbc.Driver`、
   `com.huawei.gauss200.jdbc.Driver` 或 openGauss 的 `org.postgresql.Driver`）：

```bash
export MONITOR_DATASOURCE_URL='jdbc:gaussdb://<host>:8000/connection_monitor'
export MONITOR_DATASOURCE_USERNAME=monitor
export MONITOR_DATASOURCE_PASSWORD='******'
export MONITOR_DATASOURCE_DRIVER='com.huawei.gaussdb.jdbc.Driver'
export MONITOR_STORAGE_DIALECT='gaussdb'
java -Dloader.path=/opt/connection-monitor/lib \
     -cp connection-monitor-server-1.0.0-SNAPSHOT.jar org.springframework.boot.loader.PropertiesLauncher
```

4. 启动日志出现 `使用显式配置的数据库方言：gaussdb`（未显式配置时为 `自动识别数据库方言：gaussdb`）
   与 `建表脚本执行完成: classpath:db/schema-gaussdb.sql`、`核心表校验通过`，即表示建表成功。页面与 API 使用方式不变。

> openGauss 也可使用 PostgreSQL 兼容 URL `jdbc:postgresql://...`；
> 此时方言会按产品名识别为 `gaussdb` 或 `postgresql`（两者 DDL 结构一致）；
> 也可显式设置 `MONITOR_STORAGE_DIALECT=gaussdb`。

### 2.4 从旧版本迁移

若 MySQL 旧表以 `(instance_id, pool_name)` 为唯一键，执行 `docs/sql/migration-v2.sql`
完成列新增、唯一键切换与历史数据回填，避免同名连接池覆盖。

## 3. 业务应用接入

1. 引入 Starter（无需任何代码改动，无需 Mapper/Service）：

```xml
<dependency>
    <groupId>com.connectionmonitor</groupId>
    <artifactId>connection-monitor-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

2. 配置上报地址（K8s 示例）：

```yaml
env:
  - name: SPRING_APPLICATION_NAME
    value: order-service
  - name: CONNECTION_MONITOR_SERVER_URL
    value: http://monitor.internal:8090
  - name: CONNECTION_MONITOR_REPORT_INTERVAL_MS
    value: "30000"
```

对应属性：`connection.monitor.server-url` / `report-interval-ms` /
`report-initial-delay-ms` / `request-timeout-ms` / `instance-id` / `enabled`。

3. 验证：启动后服务端 `http://<server>:8090/` 出现实例；服务端日志出现
   `接收实例 [...] 上报`；客户端日志出现 `connection-monitor 已启动`。

## 4. 运维要点

- **上报失败不影响业务**：客户端失败仅记有限日志（首次 + 每第 10 次），
  超时默认 5s，业务线程不参与（独立守护线程）；
- **实例离线判定**：页面按 90 秒（3 × 默认上报周期）无上报显示离线；
- **重启语义**：业务实例重启会生成新 instanceId（新实例记录），旧实例保留
  用于历史查询；显式指定 `connection.monitor.instance-id` 可保持稳定；
- **容量估算**：30s 周期、每实例 5 个池 ≈ 1.4 万行/天/实例（历史表），
  默认保留 30 天；
- **安全**：客户端默认不采集 SQL 文本、完整 JDBC URL、用户名或密码；
  建议服务端端口仅对内网/业务 Pod 网段开放；
- **数据库兼容**：服务端写入使用“先 UPDATE 后 INSERT”的跨库 upsert，不依赖 MySQL 专有语法；
  新增数据库时提供方言与建表脚本即可，详见 `docs/database-support.md`。

## 5. 本地开发

```bash
# 服务端（默认 H2 文件库 ./data/，启动时自动执行 db/schema-h2.sql，浏览器打开 http://localhost:8090）
cd connection-monitor-server && mvn spring-boot:run

# 全量构建 + 测试（47 个用例）
mvn clean package
```
