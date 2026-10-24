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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 历史趋势 API 测试：raw/分桶查询、poolId 隔离（Druid 与 HikariCP 不混）、null 保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void historyIsIsolatedByPoolIdAndBucketedByGranularity() throws Exception {
        // 对齐到分钟边界，保证全部样本（最晚 +10s）落入同一分钟桶
        long base = (System.currentTimeMillis() - 60_000) / 60_000 * 60_000;
        String druidPoolId = "hist-instance:masterDataSource";
        String hikariPoolId = "hist-instance:orderDataSource";

        // Druid 池 3 个样本
        for (int i = 0; i < 3; i++) {
            postReport(report(base + i * 1000,
                    pool(druidPoolId, "masterDataSource", "master", "DRUID", 1 + i, 5, base + i * 1000)));
        }
        // Hikari 池 2 个样本
        for (int i = 0; i < 2; i++) {
            postReport(report(base + i * 1000,
                    pool(hikariPoolId, "orderDataSource", "order", "HIKARI", 2 + i, 7, base + i * 1000)));
        }
        // Druid 池部分指标缺失的样本（idle 仍可用，active/max 缺失）
        Map<String, Object> partial = pool(druidPoolId, "masterDataSource", "master", "DRUID", 0, 5, base + 10_000);
        partial.put("activeConnections", null);
        partial.put("maxConnections", null);
        postReport(report(base + 10_000, partial));

        // raw 粒度：Druid 4 个点，且只含 Druid 的数据
        Map<String, Object> druidRaw = history(druidPoolId, base - 1000, base + 20_000, "raw");
        List<Map<String, Object>> druidPoints = points(druidRaw);
        assertEquals(4, druidPoints.size());
        for (Map<String, Object> point : druidPoints) {
            assertEquals(5, point.get("idleConnections"), "Druid 池查询不会混入 Hikari 样本");
        }

        // Hikari 查询只有 2 个点，值与 Druid 不同
        Map<String, Object> hikariRaw = history(hikariPoolId, base - 1000, base + 20_000, "raw");
        List<Map<String, Object>> hikariPoints = points(hikariRaw);
        assertEquals(2, hikariPoints.size());
        for (Map<String, Object> point : hikariPoints) {
            assertEquals(7, point.get("idleConnections"));
        }

        // 1 分钟分桶：全部样本落入同一桶（取桶内最后一个样本），缺失指标保留为 null
        Map<String, Object> bucketed = history(druidPoolId, base - 1000, base + 20_000, "1m");
        List<Map<String, Object>> bucketPoints = points(bucketed);
        assertEquals(1, bucketPoints.size(), "同一分钟内的样本聚合为一个点");
        Map<String, Object> last = bucketPoints.get(0);
        assertNull(last.get("activeConnections"), "缺失指标保持 null，不落库为 0");
        assertNull(last.get("maxConnections"));
    }

    @Test
    void emptyHistoryReturnsEmptyPoints() throws Exception {
        Map<String, Object> body = history("no-such-instance:x", 0, System.currentTimeMillis(), "raw");
        assertTrue(points(body).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> points(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("points");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> history(String poolId, long start, long end, String granularity)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/history")
                        .param("instanceId", "hist-instance")
                        .param("poolId", poolId)
                        .param("startTime", String.valueOf(start))
                        .param("endTime", String.valueOf(end))
                        .param("granularity", granularity))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), Map.class);
    }

    private void postReport(Map<String, Object> report) throws Exception {
        mockMvc.perform(post("/api/metrics/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(report)))
                .andExpect(status().isOk());
    }

    private Map<String, Object> report(long sampleTime, Map<String, Object>... pools) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("instanceId", "hist-instance");
        report.put("appName", "history-app");
        report.put("reportTime", sampleTime);
        report.put("jvm", new LinkedHashMap<String, Object>());
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>(java.util.Arrays.asList(pools));
        report.put("pools", list);
        return report;
    }

    private Map<String, Object> pool(String poolId, String beanName, String poolName,
                                     String poolType, int active, int idle, long sampleTime) {
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
        pool.put("sampleTime", sampleTime);
        return pool;
    }
}
