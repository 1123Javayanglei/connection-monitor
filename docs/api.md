# connection-monitor API 文档

客户端与服务端之间 JSON 字段命名完全一致（Jackson camelCase）。

## 1. 上报接口（客户端 → 服务端）

`POST /api/metrics/report`，`Content-Type: application/json`

```json
{
  "instanceId": "pod-1:order-service:9f3ab12c",
  "appName": "order-service",
  "host": "pod-1",
  "reportTime": 1792839600000,
  "jvm": {
    "heapUsedBytes": 524288000,
    "heapMaxBytes": 2147483648,
    "nonHeapUsedBytes": 104857600,
    "totalThreadCount": 88,
    "peakThreadCount": 120,
    "daemonThreadCount": 80,
    "loadedClassCount": 12000,
    "uptimeMs": 3600000,
    "processCpuLoad": 0.12
  },
  "pools": [
    {
      "poolId": "pod-1:order-service:9f3ab12c:masterDataSource",
      "dataSourceId": "pod-1:order-service:9f3ab12c:masterDataSource",
      "dataSourceBeanName": "masterDataSource",
      "poolName": "master-pool",
      "poolType": "DRUID",
      "poolVersion": "1.2.8",
      "metricsAvailability": "FULL",
      "activeConnections": 4,
      "idleConnections": 6,
      "totalConnections": 10,
      "maxConnections": 50,
      "minIdleConnections": 5,
      "threadsAwaitingConnection": 0,
      "connectionTimeoutMs": 5000,
      "sampleTime": 1792839600000,
      "unavailableMetrics": [],
      "extraMetrics": {"connectCount": 120, "closeCount": 110}
    }
  ]
}
```

响应：

- `200 {"status":"ok","pools":3}` 接受；
- `400 {"status":"rejected","reason":"instanceId is required"}` instanceId 缺失；
- `500 {"status":"error",...}` 存储异常（不影响同报文内其他连接池的已保存数据）。

字段语义：

- 时间字段（`reportTime`/`sampleTime`/`connectionTimeoutMs`）一律 **epoch/毫秒**；
- 数量字段一律整数；**不可用指标为 null，不是 0**；
- `poolType`：`DRUID` / `HIKARI` / `UNKNOWN`；
- `metricsAvailability`：`FULL`（全部统一指标可用）/ `PARTIAL`（部分不可用，见
  `unavailableMetrics`）/ `UNAVAILABLE`（类型未知或整体不可采集）；
- `poolId` 在同一实例内唯一（`instanceId + ":" + beanName[+:route:key]`），
  服务端以 `(instance_id, pool_id)` 唯一键 upsert 最新状态并写入历史表。

## 2. 查询接口

### 实例列表

`GET /api/instances` → 实例数组（含 `poolCount`、JVM 指标、`lastReportTime`）。

### 实例连接池列表

`GET /api/pools?instanceId={id}&poolType={DRUID|HIKARI|UNKNOWN}&beanName={beanName}`

`poolType`、`beanName` 为可选筛选；返回该实例下连接池最新状态数组。

### 历史趋势

`GET /api/history?instanceId={id}&poolId={poolId}&startTime={ms}&endTime={ms}&granularity={raw|1m|5m|1h|auto}`

- `startTime`/`endTime`：epoch 毫秒，缺省最近 6 小时；
- `granularity`：分桶粒度；`auto` 按时间跨度选择，桶内取最后一个样本；
- 按 `poolId` 隔离查询，Druid 与 HikariCP 指标不会混入。

```json
{
  "instanceId": "...", "poolId": "...",
  "startTime": 0, "endTime": 0, "granularityMs": 60000,
  "points": [
    {"bucketTime": 1792839600000, "activeConnections": 4, "idleConnections": 6,
     "totalConnections": 10, "maxConnections": 50, "minIdleConnections": 5,
     "threadsAwaitingConnection": 0}
  ]
}
```

缺失指标点位值为 `null`，前端曲线断点显示，不按 0 处理。

## 3. 页面路由

| 路径 | 说明 |
| --- | --- |
| `/` | 实例列表（在线状态、连接池数、堆内存、线程、CPU、最近上报） |
| `/instance/{instanceId}` | 实例详情：连接池表格；`?poolType=`、`?beanName=` 筛选 |
| `/pool?instanceId={id}&poolId={pid}` | 连接池详情：最新指标、扩展指标、ECharts 历史趋势 |

## 4. 数据表（服务端存储）

- `monitor_pool_latest`：唯一键 `(instance_id, pool_id)`；含
  `data_source_id`、`data_source_bean_name`、`pool_type`、`pool_version`、
  `metrics_availability`、统一指标列（可空）、`extra_metrics`（JSON 文本）；
- `monitor_pool_history`：`(instance_id, pool_id, sample_time)` 索引；缺失指标 `NULL`；
- `monitor_instance`：实例主键 + JVM 指标 + `last_report_time`。

DDL 按数据库方言存放于 `src/main/resources/db/schema-*.sql`（H2 / MySQL / GaussDB / PostgreSQL，
启动时自动选择并幂等执行）；生产 MySQL 也可直接执行 `docs/sql/schema-mysql.sql`。
数据库支持与扩展方式见 [database-support.md](database-support.md)。
