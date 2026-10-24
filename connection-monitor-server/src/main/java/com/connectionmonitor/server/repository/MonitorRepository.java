package com.connectionmonitor.server.repository;

import com.connectionmonitor.server.domain.JvmMetrics;
import com.connectionmonitor.server.domain.MetricsReport;
import com.connectionmonitor.server.domain.PoolSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控数据仓储：最新状态 upsert（instance_id + pool_id 唯一）、历史插入、趋势查询。
 * 单条写入失败只影响该条，不影响同一报文内其他连接池。
 */
@Repository
public class MonitorRepository {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorRepository.class);

    private static final int MAX_HISTORY_ROWS = 20000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MonitorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- 写入

    public void saveReport(MetricsReport report) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try {
            upsertInstance(report, now);
        } catch (DataAccessException e) {
            LOG.warn("保存实例 [{}] 状态失败: {}", report.getInstanceId(), e.getMessage());
        }
        if (report.getPools() == null) {
            return;
        }
        for (PoolSnapshot pool : report.getPools()) {
            if (pool == null || isBlank(pool.getPoolId()) || isBlank(report.getInstanceId())) {
                continue;
            }
            try {
                upsertLatest(report.getInstanceId(), pool, now);
                insertHistory(report.getInstanceId(), pool);
            } catch (DataAccessException e) {
                LOG.warn("保存连接池 [}/{}] 失败，跳过该池: {}",
                        report.getInstanceId(), pool.getPoolId(), e.getMessage());
            }
        }
    }

    private void upsertInstance(MetricsReport report, Timestamp now) {
        JvmMetrics jvm = report.getJvm();
        final Long heapUsed = jvm == null ? null : jvm.getHeapUsedBytes();
        final Long heapMax = jvm == null ? null : jvm.getHeapMaxBytes();
        final Long nonHeapUsed = jvm == null ? null : jvm.getNonHeapUsedBytes();
        final Integer threads = jvm == null ? null : jvm.getTotalThreadCount();
        final Integer peakThreads = jvm == null ? null : jvm.getPeakThreadCount();
        final Integer daemonThreads = jvm == null ? null : jvm.getDaemonThreadCount();
        final Integer loadedClasses = jvm == null ? null : jvm.getLoadedClassCount();
        final Long uptime = jvm == null ? null : jvm.getUptimeMs();
        final Double cpuLoad = jvm == null ? null : jvm.getProcessCpuLoad();
        final long reportTime = report.getReportTime() != null ? report.getReportTime() : now.getTime();
        jdbcTemplate.update(
                "INSERT INTO monitor_instance (instance_id, app_name, host, heap_used_bytes, heap_max_bytes,"
                        + " non_heap_used_bytes, total_thread_count, peak_thread_count, daemon_thread_count,"
                        + " loaded_class_count, uptime_ms, process_cpu_load, last_report_time, create_time, update_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                        + " ON DUPLICATE KEY UPDATE app_name=VALUES(app_name), host=VALUES(host),"
                        + " heap_used_bytes=VALUES(heap_used_bytes), heap_max_bytes=VALUES(heap_max_bytes),"
                        + " non_heap_used_bytes=VALUES(non_heap_used_bytes),"
                        + " total_thread_count=VALUES(total_thread_count),"
                        + " peak_thread_count=VALUES(peak_thread_count),"
                        + " daemon_thread_count=VALUES(daemon_thread_count),"
                        + " loaded_class_count=VALUES(loaded_class_count), uptime_ms=VALUES(uptime_ms),"
                        + " process_cpu_load=VALUES(process_cpu_load), last_report_time=VALUES(last_report_time),"
                        + " update_time=VALUES(update_time)",
                ps -> {
                    ps.setString(1, report.getInstanceId());
                    ps.setString(2, report.getAppName());
                    ps.setString(3, report.getHost());
                    setNullableLong(ps, 4, heapUsed);
                    setNullableLong(ps, 5, heapMax);
                    setNullableLong(ps, 6, nonHeapUsed);
                    setNullableInt(ps, 7, threads);
                    setNullableInt(ps, 8, peakThreads);
                    setNullableInt(ps, 9, daemonThreads);
                    setNullableInt(ps, 10, loadedClasses);
                    setNullableLong(ps, 11, uptime);
                    if (cpuLoad == null) {
                        ps.setNull(12, java.sql.Types.DOUBLE);
                    } else {
                        ps.setDouble(12, cpuLoad);
                    }
                    ps.setLong(13, reportTime);
                    ps.setTimestamp(14, now);
                    ps.setTimestamp(15, now);
                });
    }

    private void upsertLatest(String instanceId, PoolSnapshot pool, Timestamp now) {
        long sampleTime = pool.getSampleTime() != null ? pool.getSampleTime() : now.getTime();
        jdbcTemplate.update(
                "INSERT INTO monitor_pool_latest (instance_id, pool_id, data_source_id, data_source_bean_name,"
                        + " pool_name, pool_type, pool_version, metrics_availability, active_connections,"
                        + " idle_connections, total_connections, max_connections, min_idle_connections,"
                        + " threads_awaiting_connection, connection_timeout_ms, extra_metrics, sample_time,"
                        + " create_time, update_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                        + " ON DUPLICATE KEY UPDATE data_source_id=VALUES(data_source_id),"
                        + " data_source_bean_name=VALUES(data_source_bean_name), pool_name=VALUES(pool_name),"
                        + " pool_type=VALUES(pool_type), pool_version=VALUES(pool_version),"
                        + " metrics_availability=VALUES(metrics_availability),"
                        + " active_connections=VALUES(active_connections), idle_connections=VALUES(idle_connections),"
                        + " total_connections=VALUES(total_connections), max_connections=VALUES(max_connections),"
                        + " min_idle_connections=VALUES(min_idle_connections),"
                        + " threads_awaiting_connection=VALUES(threads_awaiting_connection),"
                        + " connection_timeout_ms=VALUES(connection_timeout_ms), extra_metrics=VALUES(extra_metrics),"
                        + " sample_time=VALUES(sample_time), update_time=VALUES(update_time)",
                ps -> {
                    ps.setString(1, instanceId);
                    ps.setString(2, pool.getPoolId());
                    ps.setString(3, orEmpty(pool.getDataSourceId()));
                    ps.setString(4, pool.getDataSourceBeanName());
                    ps.setString(5, pool.getPoolName());
                    ps.setString(6, pool.getPoolType());
                    ps.setString(7, pool.getPoolVersion());
                    ps.setString(8, pool.getMetricsAvailability());
                    setNullableInt(ps, 9, pool.getActiveConnections());
                    setNullableInt(ps, 10, pool.getIdleConnections());
                    setNullableInt(ps, 11, pool.getTotalConnections());
                    setNullableInt(ps, 12, pool.getMaxConnections());
                    setNullableInt(ps, 13, pool.getMinIdleConnections());
                    setNullableInt(ps, 14, pool.getThreadsAwaitingConnection());
                    setNullableLong(ps, 15, pool.getConnectionTimeoutMs());
                    ps.setString(16, serializeExtra(pool));
                    ps.setLong(17, sampleTime);
                    ps.setTimestamp(18, now);
                    ps.setTimestamp(19, now);
                });
    }

    private void insertHistory(String instanceId, PoolSnapshot pool) {
        long sampleTime = pool.getSampleTime() != null ? pool.getSampleTime() : System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO monitor_pool_history (instance_id, pool_id, pool_type, active_connections,"
                        + " idle_connections, total_connections, max_connections, min_idle_connections,"
                        + " threads_awaiting_connection, sample_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                ps -> {
                    ps.setString(1, instanceId);
                    ps.setString(2, pool.getPoolId());
                    ps.setString(3, pool.getPoolType());
                    setNullableInt(ps, 4, pool.getActiveConnections());
                    setNullableInt(ps, 5, pool.getIdleConnections());
                    setNullableInt(ps, 6, pool.getTotalConnections());
                    setNullableInt(ps, 7, pool.getMaxConnections());
                    setNullableInt(ps, 8, pool.getMinIdleConnections());
                    setNullableInt(ps, 9, pool.getThreadsAwaitingConnection());
                    ps.setLong(10, sampleTime);
                });
    }

    /** 清理保留期外的历史数据，由定时任务调用。 */
    public int purgeHistoryBefore(long beforeEpochMs) {
        return jdbcTemplate.update("DELETE FROM monitor_pool_history WHERE sample_time < ?", beforeEpochMs);
    }

    // ---------------------------------------------------------------- 查询

    public List<InstanceRow> listInstances() {
        String sql = "SELECT i.instance_id, i.app_name, i.host, i.heap_used_bytes, i.heap_max_bytes,"
                + " i.non_heap_used_bytes, i.total_thread_count, i.peak_thread_count, i.daemon_thread_count,"
                + " i.loaded_class_count, i.uptime_ms, i.process_cpu_load, i.last_report_time,"
                + " (SELECT COUNT(*) FROM monitor_pool_latest p WHERE p.instance_id = i.instance_id) AS pool_count"
                + " FROM monitor_instance i ORDER BY i.last_report_time DESC";
        return jdbcTemplate.query(sql, new InstanceRowMapper());
    }

    public InstanceRow getInstance(String instanceId) {
        List<InstanceRow> rows = listInstances();
        for (InstanceRow row : rows) {
            if (row.getInstanceId().equals(instanceId)) {
                return row;
            }
        }
        return null;
    }

    /**
     * 查询实例下的连接池最新状态，支持按连接池类型与数据源 Bean 名称筛选。
     */
    public List<PoolRow> listPools(String instanceId, String poolType, String beanName) {
        StringBuilder sql = new StringBuilder(
                "SELECT instance_id, pool_id, data_source_id, data_source_bean_name, pool_name, pool_type,"
                        + " pool_version, metrics_availability, active_connections, idle_connections,"
                        + " total_connections, max_connections, min_idle_connections, threads_awaiting_connection,"
                        + " connection_timeout_ms, extra_metrics, sample_time FROM monitor_pool_latest"
                        + " WHERE instance_id = ?");
        List<Object> args = new ArrayList<Object>();
        args.add(instanceId);
        if (notBlank(poolType)) {
            sql.append(" AND pool_type = ?");
            args.add(poolType.trim());
        }
        if (notBlank(beanName)) {
            sql.append(" AND data_source_bean_name = ?");
            args.add(beanName.trim());
        }
        sql.append(" ORDER BY data_source_bean_name, pool_id");
        return jdbcTemplate.query(sql.toString(), args.toArray(), new PoolRowMapper());
    }

    public PoolRow getPool(String instanceId, String poolId) {
        List<PoolRow> rows = jdbcTemplate.query(
                "SELECT instance_id, pool_id, data_source_id, data_source_bean_name, pool_name, pool_type,"
                        + " pool_version, metrics_availability, active_connections, idle_connections,"
                        + " total_connections, max_connections, min_idle_connections, threads_awaiting_connection,"
                        + " connection_timeout_ms, extra_metrics, sample_time FROM monitor_pool_latest"
                        + " WHERE instance_id = ? AND pool_id = ?",
                new Object[]{instanceId, poolId}, new PoolRowMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> distinctPoolTypes(String instanceId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT pool_type FROM monitor_pool_latest WHERE instance_id = ? ORDER BY pool_type",
                String.class, instanceId);
    }

    public List<String> distinctBeanNames(String instanceId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT data_source_bean_name FROM monitor_pool_latest WHERE instance_id = ?"
                        + " ORDER BY data_source_bean_name",
                String.class, instanceId);
    }

    /**
     * 查询连接池历史趋势：按 granularityMs 分桶，桶内取最后一个样本；
     * Druid 与 HikariCP 按 pool_id 隔离查询，指标不会互相混入。
     */
    public List<PoolHistoryPoint> queryHistory(String instanceId, String poolId,
                                               long startTime, long endTime, long granularityMs) {
        List<PoolHistoryPoint> raw = jdbcTemplate.query(
                "SELECT sample_time, active_connections, idle_connections, total_connections, max_connections,"
                        + " min_idle_connections, threads_awaiting_connection FROM monitor_pool_history"
                        + " WHERE instance_id = ? AND pool_id = ? AND sample_time >= ? AND sample_time <= ?"
                        + " ORDER BY sample_time ASC",
                new Object[]{instanceId, poolId, startTime, endTime},
                (rs, rowNum) -> {
                    PoolHistoryPoint point = new PoolHistoryPoint(rs.getLong(1));
                    point.setActiveConnections((Integer) rs.getObject(2));
                    point.setIdleConnections((Integer) rs.getObject(3));
                    point.setTotalConnections((Integer) rs.getObject(4));
                    point.setMaxConnections((Integer) rs.getObject(5));
                    point.setMinIdleConnections((Integer) rs.getObject(6));
                    point.setThreadsAwaitingConnection((Integer) rs.getObject(7));
                    return point;
                });
        if (raw.size() > MAX_HISTORY_ROWS) {
            int stride = raw.size() / MAX_HISTORY_ROWS + 1;
            List<PoolHistoryPoint> sampled = new ArrayList<PoolHistoryPoint>();
            for (int i = 0; i < raw.size(); i += stride) {
                sampled.add(raw.get(i));
            }
            raw = sampled;
        }
        if (granularityMs <= 0) {
            return raw;
        }
        // 按桶聚合：保留每个桶内最后一个样本（瞬时值语义）
        Map<Long, PoolHistoryPoint> buckets = new LinkedHashMap<Long, PoolHistoryPoint>();
        for (PoolHistoryPoint point : raw) {
            long bucket = point.getBucketTime() / granularityMs * granularityMs;
            buckets.put(bucket, point);
        }
        return new ArrayList<PoolHistoryPoint>(buckets.values());
    }

    // ---------------------------------------------------------------- 辅助

    private String serializeExtra(PoolSnapshot pool) {
        if (pool.getExtraMetrics() == null || pool.getExtraMetrics().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(pool.getExtraMetrics());
        } catch (Exception e) {
            LOG.debug("extraMetrics 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private static void setNullableInt(java.sql.PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setNullableLong(java.sql.PreparedStatement ps, int index, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean notBlank(String value) {
        return !isBlank(value);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static class InstanceRowMapper implements RowMapper<InstanceRow> {
        @Override
        public InstanceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            InstanceRow row = new InstanceRow();
            row.setInstanceId(rs.getString("instance_id"));
            row.setAppName(rs.getString("app_name"));
            row.setHost(rs.getString("host"));
            row.setHeapUsedBytes(getNullableLong(rs, "heap_used_bytes"));
            row.setHeapMaxBytes(getNullableLong(rs, "heap_max_bytes"));
            row.setNonHeapUsedBytes(getNullableLong(rs, "non_heap_used_bytes"));
            row.setTotalThreadCount(getNullableInt(rs, "total_thread_count"));
            row.setPeakThreadCount(getNullableInt(rs, "peak_thread_count"));
            row.setDaemonThreadCount(getNullableInt(rs, "daemon_thread_count"));
            row.setLoadedClassCount(getNullableInt(rs, "loaded_class_count"));
            row.setUptimeMs(getNullableLong(rs, "uptime_ms"));
            double cpu = rs.getDouble("process_cpu_load");
            row.setProcessCpuLoad(rs.wasNull() ? null : cpu);
            row.setLastReportTime(getNullableLong(rs, "last_report_time"));
            row.setPoolCount(rs.getInt("pool_count"));
            return row;
        }
    }

    private static class PoolRowMapper implements RowMapper<PoolRow> {
        @Override
        public PoolRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PoolRow row = new PoolRow();
            row.setInstanceId(rs.getString("instance_id"));
            row.setPoolId(rs.getString("pool_id"));
            row.setDataSourceId(rs.getString("data_source_id"));
            row.setDataSourceBeanName(rs.getString("data_source_bean_name"));
            row.setPoolName(rs.getString("pool_name"));
            row.setPoolType(rs.getString("pool_type"));
            row.setPoolVersion(rs.getString("pool_version"));
            row.setMetricsAvailability(rs.getString("metrics_availability"));
            row.setActiveConnections(getNullableInt(rs, "active_connections"));
            row.setIdleConnections(getNullableInt(rs, "idle_connections"));
            row.setTotalConnections(getNullableInt(rs, "total_connections"));
            row.setMaxConnections(getNullableInt(rs, "max_connections"));
            row.setMinIdleConnections(getNullableInt(rs, "min_idle_connections"));
            row.setThreadsAwaitingConnection(getNullableInt(rs, "threads_awaiting_connection"));
            row.setConnectionTimeoutMs(getNullableLong(rs, "connection_timeout_ms"));
            row.setExtraMetricsJson(rs.getString("extra_metrics"));
            row.setSampleTime(getNullableLong(rs, "sample_time"));
            return row;
        }
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
