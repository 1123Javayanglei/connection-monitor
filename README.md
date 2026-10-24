# connection-monitor 连接池监控

面向 Spring Boot 业务应用的连接池监控系统：**客户端 Starter 主动上报，服务端独立部署**，
支持 **MyBatis-Plus 多数据源、Druid、HikariCP 及多连接池混合**场景。
自带 Thymeleaf + Bootstrap + ECharts 页面，MySQL 保存指标；
**不依赖 Nacos / Prometheus / Grafana / Elasticsearch**；保持 **JDK 8 + Spring Boot 2.4.13** 兼容。

## 1. 总体架构

```
┌─────────────────────────────┐         ┌──────────────────────────────────┐
│ 业务 Pod（每个应用）          │         │ 监控服务端（独立部署在实体机）      │
│                             │         │                                  │
│  MyBatis-Plus/SqlSessionFactory A ─┐  │  POST /api/metrics/report        │
│    └── DataSource A                │  │        ▲                         │
│         └── Druid 或 HikariCP      │  │        │ JSON（统一 PoolSnapshot） │
│  MyBatis-Plus/SqlSessionFactory B ─┤──────────┘                         │
│    └── DataSource B                │  │  MySQL：monitor_pool_latest      │
│         └── Druid 或 HikariCP      │  │        monitor_pool_history      │
│                                   │  │        monitor_instance          │
│  connection-monitor Starter:      │  │                                  │
│   ├─ DataSource Discovery         │  │  页面：实例列表 → 实例详情          │
│   ├─ Druid Metrics Collector      │  │        → 连接池详情/历史趋势       │
│   ├─ HikariCP Metrics Collector   │  │  （Thymeleaf+Bootstrap+ECharts，  │
│   ├─ Unsupported 降级处理器        │  │   资源全部本地化）                 │
│   └─ JVM Metrics Collector        │  │                                  │
└─────────────────────────────┘         └──────────────────────────────────┘
```

模块说明：

| 模块 | 说明 |
| --- | --- |
| `connection-monitor-spring-boot-starter` | 客户端 Starter，业务应用引入后零代码接入 |
| `connection-monitor-server` | 监控服务端，接收上报、存储指标、展示页面 |

## 2. 快速开始

### 2.1 启动服务端

默认配置使用本地 H2 文件库（MySQL 兼容模式），开箱即用：

```bash
cd connection-monitor-server
mvn spring-boot:run
# 或使用构建产物
java -jar connection-monitor-server/target/connection-monitor-server-1.0.0-SNAPSHOT.jar
```

访问 `http://localhost:8090/`。

生产环境连接 MySQL（建表脚本见 `docs/sql/schema-mysql.sql`）：

```bash
export MONITOR_DATASOURCE_URL='jdbc:mysql://<host>:3306/connection_monitor?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export MONITOR_DATASOURCE_USERNAME=monitor
export MONITOR_DATASOURCE_PASSWORD='******'
java -jar connection-monitor-server-1.0.0-SNAPSHOT.jar
```

首次启动自动执行 `schema.sql`（`CREATE TABLE IF NOT EXISTS`，幂等）。
从旧版本（`instance_id + pool_name` 唯一键）升级请执行 `docs/sql/migration-v2.sql`。

### 2.2 业务应用接入

业务应用只需两步：引入依赖 + 配置服务端地址。**不需要为监控编写任何
Mapper、Service 或手动采集代码。**

```xml
<dependency>
    <groupId>com.connectionmonitor</groupId>
    <artifactId>connection-monitor-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```yaml
spring:
  application:
    name: order-service        # 用于实例展示
connection:
  monitor:
    server-url: http://monitor.internal:8090
    report-interval-ms: 30000  # 上报周期，默认 30s
```

业务应用使用 Druid、HikariCP、多数据源、路由数据源均可，Starter 自动发现并采集；
连接池依赖由业务应用自带，Starter 的 Druid/HikariCP 依赖均为 optional，缺失不会导致启动失败。

## 3. 客户端配置项

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `connection.monitor.enabled` | `true` | 总开关，false 时完全关闭采集与上报 |
| `connection.monitor.server-url` | 空 | 监控服务端地址；为空时只采集不上报 |
| `connection.monitor.report-interval-ms` | `30000` | 上报周期（毫秒），最小 1000 |
| `connection.monitor.report-initial-delay-ms` | `15000` | 首次上报延迟，避开业务启动高峰 |
| `connection.monitor.request-timeout-ms` | `5000` | 上报 HTTP 超时 |
| `connection.monitor.instance-id` | 自动生成 | 实例 ID；默认 `hostname:appName:8位随机后缀` |

## 4. 统一连接池指标（PoolSnapshot）

Druid 与 HikariCP 输出**同一数据结构**，服务端不维护两套模型/页面/接口。

| 统一字段 | Druid 来源（公开统计 API） | HikariCP 来源（公开 MXBean/Config API） |
| --- | --- | --- |
| `activeConnections` | `getActiveCount()` | `HikariPoolMXBean.getActiveConnections()` |
| `idleConnections` | `getPoolingCount()` | `HikariPoolMXBean.getIdleConnections()` |
| `totalConnections` | active + pooling（任一缺失则 null） | `HikariPoolMXBean.getTotalConnections()` |
| `maxConnections` | `getMaxActive()` | `getMaximumPoolSize()` |
| `minIdleConnections` | `getMinIdle()` | `getMinimumIdle()` |
| `threadsAwaitingConnection` | `getWaitThreadCount()` | `getThreadsAwaitingConnection()` |
| `connectionTimeoutMs` | `getMaxWait()` | `getConnectionTimeout()` |
| `poolVersion` | `getVersion()` | jar 包 ImplementationVersion |

附加字段：

- `poolId` / `dataSourceId`：连接池唯一标识（见第 5 节），存储与查询主键；
- `dataSourceBeanName`：Spring Bean 名称（业务可读）；
- `poolType`：`DRUID` / `HIKARI` / `UNKNOWN`（约定值，页面按此区分类型）；
- `metricsAvailability`：`FULL` / `PARTIAL` / `UNAVAILABLE`，配合 `unavailableMetrics`
  列表明示不可用指标——**缺失指标一律 `null`，绝不伪造为 0**；
- `extraMetrics`：连接池特有扩展指标（Druid 的 `initialSize`、`maxWaitMillis`、
  `notEmptyWaitCount`、`connectCount`、`closeCount`、`errorCount` 累计计数器等；
  累计计数器只在这里出现，不会冒充当前连接数）。

版本不支持或读取异常的单个指标自动置 null 并记录有限诊断日志，其余指标继续采集。

## 5. 多数据源发现与唯一标识

- **发现入口**：Spring 容器中的实际 `DataSource` Bean（`ListableBeanFactory`），
  每个上报周期重新发现，支持运行期动态注册、初始化顺序不固定的数据源；
- **不要求 `@Primary`、不要求 Bean 名称固定**，不按 Mapper / SqlSessionFactory 数量统计；
- **按真实连接池实例去重**：`LazyConnectionDataSourceProxy`、`TransactionAwareDataSourceProxy`
  等包装器由 `DataSourceUnwrapper` 安全解包（深度上限 8，白名单反射仅
  `getTargetDataSource` / `getResolvedDataSources` / `getResolvedDefaultDataSource`），
  同一池被多个 Bean/Factory 引用只采集一次；
- **路由数据源**（`AbstractRoutingDataSource`，含 baomidou dynamic-datasource 子类）：
  路由本身记录为 `UNKNOWN` 条目（指标不可用），底层池以 `:route:<key>` 后缀单独注册，
  与 Bean 直接注册的同一实例去重，**不会重复计入总量**；全程只读 `resolvedDataSources`
  缓存 Map，不触发路由选择、不执行 SQL、不创建连接；
- **命名规则**：`dataSourceId = instanceId + ":" + normalizedBeanName`
  （路由间接发现的池为 `instanceId + ":" + beanName + ":route:" + routeKey`），
  `poolId` 与 `dataSourceId` 取值一致。同名连接池（`poolName` 相同）因 Bean 名不同
  而拥有独立 ID，服务端以 `(instance_id, pool_id)` 唯一键存储，**同名不会互相覆盖**；
  ID 不使用 `System.identityHashCode`，不含密码、完整 JDBC URL 等敏感信息。

## 6. 采集安全边界

客户端采集过程：**不执行 SQL、不调用 `getConnection()`、不关闭连接池、不修改连接池配置、
不依赖业务 Mapper、不开启 Druid StatViewServlet、不要求业务暴露管理端口**；
默认不采集/上报 SQL 文本、完整 JDBC URL、用户名或密码。
单连接池采集失败只降级该池（null + UNAVAILABLE），不影响其他池与业务应用；
发现与采集的所有异常均在 Starter 内部消化，**不会导致业务应用启动失败**。

## 7. 服务端存储与页面

数据表（MySQL 5.7+ / H2 MySQL 模式通用，`schema.sql` 自动执行）：

- `monitor_pool_latest`：最新状态，唯一键 `(instance_id, pool_id)`，
  含 `data_source_id` / `data_source_bean_name` / `pool_type` / `pool_version` /
  `metrics_availability` / `extra_metrics`；
- `monitor_pool_history`：历史趋势，按稳定 `pool_id` 关联（非显示名称），
  缺失指标存 `NULL`；默认保留 30 天（`connection-monitor.history.retention-days`），每日 03:30 清理；
- `monitor_instance`：实例 JVM 指标与最近上报时间。

页面：

- `/` 实例列表（在线状态、连接池数、堆内存、线程、CPU）；
- `/instance/{instanceId}` 实例详情：连接池表格
  （数据源 Bean｜连接池名称｜类型｜Active｜Idle｜Total｜Max｜Pending），
  支持按 **连接池类型** 与 **数据源 Bean 名称** 筛选；
- `/pool?instanceId=..&poolId=..` 连接池详情：最新指标卡片、扩展指标表、
  ECharts 历史趋势（1h/6h/24h/7d + 原始/1m/5m/1h 粒度），同一 `poolId`
  关联最新状态与历史曲线。

API 与页面说明详见 [docs/api.md](docs/api.md)，部署细节见 [docs/deployment.md](docs/deployment.md)。

## 8. 依赖与兼容性

| 依赖 | 位置 | 是否必需 | 原因与兼容性 |
| --- | --- | --- | --- |
| spring-boot-autoconfigure / spring-context / spring-web | Starter | 必需 | 自动装配与 RestTemplate 上报；不内嵌 Web 容器 |
| jackson-databind | Starter | optional | 上报 JSON 序列化；缺失时降级为只采集不上报（不抛错） |
| Druid（`com.alibaba:druid`，测试用 1.2.8） | Starter | optional | 编译基于 Druid 公开统计 API；由业务应用实际版本提供，**不强制引入、不覆盖版本**；1.1.x 起方法兼容，1.0.x 个别签名差异会被逐指标隔离 |
| HikariCP | Starter | optional | 由业务应用依赖提供（Spring Boot 2.4.13 管理 3.4.5）；**不强制升级、不额外创建监控用连接池** |
| MySQL Connector/H2 | 服务端 | 运行时 | 生产 MySQL；开发/测试 H2（MySQL 兼容模式） |
| MyBatis-Plus（3.4.3.4） | 仅测试 | 否 | 用于多数据源兼容性场景测试；Starter 代码不引用任何 MyBatis 类 |

兼容性验证：JDK 8（OpenJDK 1.8.0_504）+ Spring Boot 2.4.13 全量构建与测试通过；
Starter 在有/无 MyBatis-Plus、有/无 Druid、有/无 HikariCP 的应用中均可正常启动。

## 9. 测试覆盖（对应实施文档场景 A–G）

`mvn test` 共 39 个用例：

- **场景 A** 单数据源 + MyBatis-Plus + HikariCP：一个池 + JVM 指标上报；
- **场景 B** 多数据源 + Druid（含 `@Primary`、多个 SqlSessionFactory）：两池分别上报、不重复统计、非 Primary 不漏采；
- **场景 C** 两个 HikariCP 同名连接池：独立 poolId、互不覆盖（同时排除 MP 自动装配验证无 MP 应用）；
- **场景 D** Druid + HikariCP 混合：统一结构上报、类型可区分；
- **场景 E** 采集异常隔离：损坏池 PARTIAL（null 不伪造 0），正常池持续上报，应用正常启动；
- **场景 F** 路由数据源：路由本身 UNKNOWN、底层两池去重各计一次、不建连；
- **场景 G** 未知类型 DataSource：UNKNOWN 记录 + JVM 指标正常上报；
- **单元**：解包器（代理/深度上限/白名单）、Druid/HikariCP 采集器（逐指标隔离、未启动池降级）、
  发现服务（按实例去重、ID 稳定性、路由展开）；
- **服务端**：上报 API（同名不覆盖、null 保真、坏报文拒绝）、历史 API（poolId 隔离、分桶）、
  页面渲染（类型/Bean 筛选、连接池详情）。

## 10. 构建

```bash
# JDK 8 + Maven 3.6+，完整构建并运行全部测试
mvn clean package

# 产物
connection-monitor-spring-boot-starter/target/connection-monitor-spring-boot-starter-1.0.0-SNAPSHOT.jar
connection-monitor-server/target/connection-monitor-server-1.0.0-SNAPSHOT.jar
```
