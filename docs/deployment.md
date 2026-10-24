# connection-monitor 部署说明

## 1. 环境要求

- **监控服务端**：JDK 8+，MySQL 5.7+（或开箱体验用内嵌 H2），独立部署在实体机；
- **业务应用**：JDK 8，Spring Boot 2.4.x（构建验证基于 2.4.13）；
- 网络方向：业务 Pod → 服务端 `8090/tcp`（出站），服务端无需访问业务 Pod。

## 2. 服务端部署（MySQL）

### 2.1 建库

```sql
CREATE DATABASE connection_monitor DEFAULT CHARACTER SET utf8mb4;
CREATE USER 'monitor'@'%' IDENTIFIED BY '******';
GRANT ALL PRIVILEGES ON connection_monitor.* TO 'monitor'@'%';
```

执行 `docs/sql/schema-mysql.sql`（应用启动时的 `schema.sql` 也会幂等建表，
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

### 2.3 从旧版本迁移

若旧表以 `(instance_id, pool_name)` 为唯一键，执行 `docs/sql/migration-v2.sql`
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
  建议服务端端口仅对内网/业务 Pod 网段开放。

## 5. 本地开发

```bash
# 服务端（默认 H2 文件库 ./data/，浏览器打开 http://localhost:8090）
cd connection-monitor-server && mvn spring-boot:run

# 全量构建 + 测试（39 个用例）
mvn clean package
```
