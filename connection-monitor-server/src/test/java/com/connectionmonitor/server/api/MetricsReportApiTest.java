package com.connectionmonitor.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上报 API 测试：多连接池保存、同名连接池不覆盖、Druid/HikariCP 区分、坏报文拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsReportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sameNamePoolsInDifferentDataSourcesNeverOverwriteEachOther() throws Exception {
        postReport(buildReport("multi-instance",
                pool("multi-instance:masterDataSource", "masterDataSource", "same-pool", "DRUID", 4, 6),
                pool("multi-instance:slaveDataSource", "slaveDataSource", "same-pool", "HIKARI", 2, 8)));

        // 第二次上报只更新 master：slave 的数据必须保持不变（同名 poolName 不互相覆盖）
        postReport(buildReport("multi-instance",
                pool("multi-instance:masterDataSource", "masterDataSource", "same-pool", "DRUID", 5, 5)));

        MvcResult result = mockMvc.perform(get("/api/pools").param("instanceId", "multi-instance"))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> pools = readPools(result);

        assertEquals(2, pools.size(), "同名连接池必须以 poolId 区分，两个都在");
        Map<String, Map<String, Object>> byPoolId = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> pool : pools) {
            byPoolId.put((String) pool.get("poolId"), pool);
        }
        assertTrue(byPoolId.containsKey("multi-instance:masterDataSource"));
        assertTrue(byPoolId.containsKey("multi-instance:slaveDataSource"));

        Map<String, Object> master = byPoolId.get("multi-instance:masterDataSource");
        Map<String, Object> slave = byPoolId.get("multi-instance:slaveDataSource");
        assertEquals(5, master.get("activeConnections"), "master 更新为 5");
        assertEquals("DRUID", master.get("poolType"));
        assertEquals(2, slave.get("activeConnections"), "slave 未被同名 master 覆盖");
        assertEquals("HIKARI", slave.get("poolType"));
        assertEquals("same-pool", master.get("poolName"));
        assertEquals("same-pool", slave.get("poolName"));
    }

    @Test
    void nullMetricsAreStoredAsNullNotZero() throws Exception {
        postReport(buildReport("null-instance", unknownPool("null-instance:legacyDataSource")));

        MvcResult result = mockMvc.perform(get("/api/pools").param("instanceId", "null-instance"))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> pools = readPools(result);
        assertEquals(1, pools.size());
        Map<String, Object> pool = pools.get(0);
        assertEquals("UNKNOWN", pool.get("poolType"));
        assertEquals("UNAVAILABLE", pool.get("metricsAvailability"));
        assertNullValue(pool.get("activeConnections"));
        assertNullValue(pool.get("maxConnections"));
        assertFalse(String.valueOf(pool.get("activeConnections")).equals("0"));
    }

    @Test
    void blankInstanceIdIsRejected() throws Exception {
        Map<String, Object> report = buildReport("", pool("x:y", "y", "p", "DRUID", 1, 1));
        mockMvc.perform(post("/api/metrics/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(report)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void extraMetricsAndAvailabilitySurviveRoundTrip() throws Exception {
        Map<String, Object> druid = pool("extra-instance:masterDataSource", "masterDataSource",
                "master", "DRUID", 1, 2);
        druid.put("metricsAvailability", "PARTIAL");
        druid.put("unavailableMetrics", Arrays.asList("activeConnections"));
        druid.put("extraMetrics", java.util.Collections.singletonMap("connectCount", 42L));
        postReport(buildReport("extra-instance", druid));

        MvcResult result = mockMvc.perform(get("/api/pools").param("instanceId", "extra-instance"))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> pool = readPools(result).get(0);
        assertEquals("PARTIAL", pool.get("metricsAvailability"));
        assertTrue(String.valueOf(pool.get("extraMetricsJson")).contains("connectCount"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readPools(MvcResult result) throws Exception {
        return (List<Map<String, Object>>) objectMapper.readValue(result.getResponse().getContentAsByteArray(),
                List.class);
    }

    private void assertNullValue(Object value) {
        assertTrue(value == null, "应为 null 而不是 0，实际: " + value);
    }

    private void postReport(Map<String, Object> report) throws Exception {
        mockMvc.perform(post("/api/metrics/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(report)))
                .andExpect(status().isOk());
    }

    private Map<String, Object> buildReport(String instanceId, Map<String, Object>... pools) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("instanceId", instanceId);
        report.put("appName", "order-service");
        report.put("host", "pod-1");
        report.put("reportTime", System.currentTimeMillis());
        Map<String, Object> jvm = new LinkedHashMap<String, Object>();
        jvm.put("heapUsedBytes", 256L * 1024 * 1024);
        jvm.put("heapMaxBytes", 1024L * 1024 * 1024);
        jvm.put("totalThreadCount", 42);
        report.put("jvm", jvm);
        List<Map<String, Object>> poolList = new ArrayList<Map<String, Object>>(Arrays.asList(pools));
        report.put("pools", poolList);
        return report;
    }

    private Map<String, Object> pool(String poolId, String beanName, String poolName,
                                     String poolType, int active, int idle) {
        Map<String, Object> pool = new LinkedHashMap<String, Object>();
        pool.put("poolId", poolId);
        pool.put("dataSourceId", poolId);
        pool.put("dataSourceBeanName", beanName);
        pool.put("poolName", poolName);
        pool.put("poolType", poolType);
        pool.put("metricsAvailability", "FULL");
        pool.put("activeConnections", active);
        pool.put("idleConnections", idle);
        pool.put("totalConnections", active + idle);
        pool.put("maxConnections", 50);
        pool.put("minIdleConnections", 2);
        pool.put("threadsAwaitingConnection", 0);
        pool.put("connectionTimeoutMs", 30000L);
        pool.put("sampleTime", System.currentTimeMillis());
        return pool;
    }

    private Map<String, Object> unknownPool(String poolId) {
        Map<String, Object> pool = pool(poolId, "legacyDataSource", "legacyDataSource", "UNKNOWN", 0, 0);
        pool.put("metricsAvailability", "UNAVAILABLE");
        pool.put("unavailableMetrics", Arrays.asList("activeConnections", "idleConnections",
                "totalConnections", "maxConnections", "minIdleConnections", "threadsAwaitingConnection",
                "connectionTimeoutMs"));
        pool.put("activeConnections", null);
        pool.put("idleConnections", null);
        pool.put("totalConnections", null);
        pool.put("maxConnections", null);
        pool.put("minIdleConnections", null);
        pool.put("threadsAwaitingConnection", null);
        pool.put("connectionTimeoutMs", null);
        return pool;
    }
}
