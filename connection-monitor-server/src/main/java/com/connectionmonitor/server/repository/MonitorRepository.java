package com.connectionmonitor.server.repository;

import com.connectionmonitor.server.domain.JvmMetrics;
import com.connectionmonitor.server.domain.MetricsReport;
import com.connectionmonitor.server.domain.PoolSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 监控数据仓储：最新状态 upsert（instance_id + pool_id 唯一）、历史插入、趋势查询。
 * 单条写入失败只影响该条，不影响同一报文内其他连接池。
 */
@Repository
public class MonitorRepository {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorRepository.class);

    private static final int MAX_HISTORY_ROWS = 20000;

    /**
     * 跨数据库可移植的 upsert：先 UPDATE，影响行数为 0 时再 INSERT；
     * 并发插入遇到唯一键冲突时回退为 UPDATE。
     * 不使用 MySQL 专有的 ON DUPLICATE KEY UPDATE，GaussDB/PostgreSQL/H2 均可运行。
     */
    private static final String UPDATE_INSTANCE_SQL =
            "UPDATE monitor_instance SET app_name = ?, host = ?, heap_used_bytes = ?, heap_max_bytes = ?,"
                    + " non_heap_used_bytes = ?, total_thread_count = ?, peak_thread_count = ?,"
                    + " daemon_thread_count = ?, loaded_class_count = ?, uptime_ms = ?, process_cpu_load = ?,"
                    + " last_report_time = ?, update_time = ? WHERE instance_id = ?";

    private static final String INSERT_INSTANCE_SQL =
            "INSERT INTO monitor_instance (instance_id, app_name, host, heap_used_bytes, heap_max_bytes,"
                    + " non_heap_used_bytes, total_thread_count, peak_thread_count, daemon_thread_count,"
                    + " loaded_class_count, uptime_ms, process_cpu_load, last_report_time, create_time, update_time)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String UPDATE_LATEST_SQL =
            "UPDATE monitor_pool_latest SET data_source_id = ?, data_source_bean_name = ?, pool_name = ?,"
                    + " pool_type = ?, pool_version = ?, metrics_availability = ?, active_connections = ?,"
                    + " idle_connections = ?, total_connections = ?, max_connections = ?,"
                    + " min_idle_connections = ?, threads_awaiting_connection = ?, connection_timeout_ms = ?,"
                    + " extra_metrics = ?, sample_time = ?, update_time = ?"
                    + " WHERE instance_id = ? AND pool_id = ?";

    private static final String INSERT_LATEST_SQL =
            "INSERT INTO monitor_pool_latest (instance_id, pool_id, data_source_id, data_source_bean_name,"
                    + " pool_name, pool_type, pool_version, metrics_availability, active_connections,"
                    + " idle_connections, total_connections, max_connections, min_idle_connections,"
                    + " threads_awaiting_connection, connection_timeout_ms, extra_metrics, sample_time,"
                    + " create_time, update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

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
                LOG.warn("保存连接池 [{}/{}] 失败，跳过该池: {}",
                        report.getInstanceId(), pool.getPoolId(), e.getMessage());
            }
        }
    }

    private void upsertInstance(MetricsReport report, Timestamp now) {
        JvmMetrics jvm = report.getJvm();
        final long reportTime = report.getReportTime() != null ? report.getReportTime() : now.getTime();

        int updated = jdbcTemplate.update(UPDATE_INSTANCE_SQL,
                ps -> bindInstance(ps, report, jvm, reportTime, now, false));
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(INSERT_INSTANCE_SQL,
                    ps -> bindInstance(ps, report, jvm, reportTime, now, true));
        } catch (DataAccessException e) {
            // 并发上报时可能已被其他线程插入；仅唯一键冲突回退为 UPDATE。
            if (!isDuplicateKey(e)) {
                throw e;
            }
            jdbcTemplate.update(UPDATE_INSTANCE_SQL,
                    ps -> bindInstance(ps, report, jvm, reportTime, now, false));
        }
    }

    private void upsertLatest(String instanceId, PoolSnapshot pool, Timestamp now) {
        final long sampleTime = pool.getSampleTime() != null ? pool.getSampleTime() : now.getTime();

        int updated = jdbcTemplate.update(UPDATE_LATEST_SQL,
                ps -> bindLatest(ps, instanceId, pool, sampleTime, now, false));
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(INSERT_LATEST_SQL,
                    ps -> bindLatest(ps, instanceId, pool, sampleTime, now, true));
        } catch (DataAccessException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            jdbcTemplate.update(UPDATE_LATEST_SQL,
                    ps -> bindLatest(ps, instanceId, pool, sampleTime, now, false));
        }
    }

    private void bindInstance(PreparedStatement ps, MetricsReport report, JvmMetrics jvm,
                              long reportTime, Timestamp now, boolean insert) throws SQLException {
        int i = 1;
        if (insert) {
            ps.setString(i++, report.getInstanceId());
        }
        ps.setString(i++, report.getAppName());
        ps.setString(i++, report.getHost());
        setNullableLong(ps, i++, jvm == null ? null : jvm.getHeapUsedBytes());
        setNullableLong(ps, i++, jvm == null ? null : jvm.getHeapMaxBytes());
        setNullableLong(ps, i++, jvm == null ? null : jvm.getNonHeapUsedBytes());
        setNullableInt(ps, i++, jvm == null ? null : jvm.getTotalThreadCount());
        setNullableInt(ps, i++, jvm == null ? null : jvm.getPeakThreadCount());
        setNullableInt(ps, i++, jvm == null ? null : jvm.getDaemonThreadCount());
        setNullableInt(ps, i++, jvm == null ? null : jvm.getLoadedClassCount());
        setNullableLong(ps, i++, jvm == null ? null : jvm.getUptimeMs());
        Double cpuLoad = jvm == null ? null : jvm.getProcessCpuLoad();
        if (cpuLoad == null) {
            ps.setNull(i++, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(i++, cpuLoad);
        }
        ps.setLong(i++, reportTime);
        if (insert) {
            ps.setTimestamp(i++, now);
        }
        ps.setTimestamp(i++, now);
        if (!insert) {
            ps.setString(i++, report.getInstanceId());
        }
    }

    private void bindLatest(PreparedStatement ps, String instanceId, PoolSnapshot pool,
                            long sampleTime, Timestamp now, boolean insert) throws SQLException {
        int i = 1;
        if (insert) {
            ps.setString(i++, instanceId);
            ps.setString(i++, pool.getPoolId());
        }
        ps.setString(i++, orEmpty(pool.getDataSourceId()));
        ps.setString(i++, pool.getDataSourceBeanName());
        ps.setString(i++, pool.getPoolName());
        ps.setString(i++, pool.getPoolType());
        ps.setString(i++, pool.getPoolVersion());
        ps.setString(i++, pool.getMetricsAvailability());
        setNullableInt(ps, i++, pool.getActiveConnections());
        setNullableInt(ps, i++, pool.getIdleConnections());
        setNullableInt(ps, i++, pool.getTotalConnections());
        setNullableInt(ps, i++, pool.getMaxConnections());
        setNullableInt(ps, i++, pool.getMinIdleConnections());
        setNullableInt(ps, i++, pool.getThreadsAwaitingConnection());
        setNullableLong(ps, i++, pool.getConnectionTimeoutMs());
        ps.setString(i++, serializeExtra(pool));
        ps.setLong(i++, sampleTime);
        if (insert) {
            ps.setTimestamp(i++, now);
        }
        ps.setTimestamp(i++, now);
        if (!insert) {
            ps.setString(i++, instanceId);
            ps.setString(i++, pool.getPoolId());
        }
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

    /**
     * 判断异常是否为唯一键冲突。不同数据库/驱动可能抛出 DuplicateKeyException
     * 或原始 SQLIntegrityConstraintViolationException，这里统一识别。
     */
    private static boolean isDuplicateKey(DataAccessException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof SQLException) {
            SQLException sqlException = (SQLException) cause;
            String state = sqlException.getSQLState();
            int code = sqlException.getErrorCode();
            if ("23505".equals(state)) {
                return true;
            }
            // MySQL 1062、SQL Server 2601/2627
            if (code == 1062 || code == 2601 || code == 2627) {
                return true;
            }
        }
        String message = cause == null ? null : cause.getMessage();
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            return lower.contains("duplicate")
                    || lower.contains("unique constraint")
                    || lower.contains("unique violation")
                    || lower.contains("重复")
                    || lower.contains("唯一");
        }
        return false;
    }

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
