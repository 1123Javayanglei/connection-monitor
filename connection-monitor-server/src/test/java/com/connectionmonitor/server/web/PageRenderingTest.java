package com.connectionmonitor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 页面测试：实例列表、实例详情（连接池表格 + 类型/Bean 筛选）、连接池详情与趋势入口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PageRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void instanceDetailShowsAllPoolsAndSupportsFilters() throws Exception {
        postReport(buildReportWithDruidAndHikari());

        String html = getHtml("/instance/page-instance");
        assertTrue(html.contains("masterDataSource"));
        assertTrue(html.contains("slaveDataSource"));
        assertTrue(html.contains("master-pool"));
        assertTrue(html.contains("slave-pool"));
        assertTrue(html.contains("DRUID"));
        assertTrue(html.contains("HIKARI"), "页面必须能区分连接池类型");
        assertTrue(html.contains("详情 / 趋势"));

        // 按类型筛选：只剩 DRUID
        String druidOnly = getHtml("/instance/page-instance?poolType=DRUID");
        assertTrue(druidOnly.contains("master-pool"));
        assertFalse(druidOnly.contains("slave-pool"), "类型筛选后 Hikari 池不应出现");

        // 按数据源 Bean 名称查看
        String byBean = getHtml("/instance/page-instance?beanName=slaveDataSource");
        assertTrue(byBean.contains("slave-pool"));
        assertFalse(byBean.contains("master-pool"));
    }

    @Test
    void poolDetailPageRendersWithChartAndLatestValues() throws Exception {
        postReport(buildReportWithDruidAndHikari());

        // 真实容器会把 URL 编码的查询参数解码后再交给控制器，MockMvc 用 param() 直接传原始值
        MvcResult result = mockMvc.perform(get("/pool")
                        .param("instanceId", "page-instance")
                        .param("poolId", "page-instance:masterDataSource"))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("poolChart"), "连接池详情必须包含历史趋势图");
        assertTrue(html.contains("master-pool"));
        assertTrue(html.contains("masterDataSource"));
        assertTrue(html.contains("获取连接超时"));
    }

    @Test
    void instanceListPageShowsInstanceAndPoolCount() throws Exception {
        postReport(buildReportWithDruidAndHikari());

        String html = getHtml("/");
        assertTrue(html.contains("page-instance"));
        assertTrue(html.contains("order-service"));
    }

    @Test
    void unknownInstanceShowsFriendlyMessage() throws Exception {
        String html = getHtml("/instance/not-exist");
        assertTrue(html.contains("实例不存在") || html.contains("尚未上报"));
    }

    private String getHtml(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void postReport(Map<String, Object> report) throws Exception {
        mockMvc.perform(post("/api/metrics/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(report)))
                .andExpect(status().isOk());
    }

    private Map<String, Object> buildReportWithDruidAndHikari() {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("instanceId", "page-instance");
        report.put("appName", "order-service");
        report.put("host", "pod-page");
        report.put("reportTime", System.currentTimeMillis());
        Map<String, Object> jvm = new LinkedHashMap<String, Object>();
        jvm.put("heapUsedBytes", 512L * 1024 * 1024);
        jvm.put("heapMaxBytes", 2048L * 1024 * 1024);
        jvm.put("totalThreadCount", 60);
        report.put("jvm", jvm);
        List<Map<String, Object>> pools = new ArrayList<Map<String, Object>>();
        pools.add(pool("page-instance:masterDataSource", "masterDataSource", "master-pool", "DRUID", 4, 6, 50));
        pools.add(pool("page-instance:slaveDataSource", "slaveDataSource", "slave-pool", "HIKARI", 2, 8, 30));
        report.put("pools", pools);
        return report;
    }

    private Map<String, Object> pool(String poolId, String beanName, String poolName,
                                     String poolType, int active, int idle, int max) {
        Map<String, Object> pool = new LinkedHashMap<String, Object>();
        pool.put("poolId", poolId);
        pool.put("dataSourceId", poolId);
        pool.put("dataSourceBeanName", beanName);
        pool.put("poolName", poolName);
        pool.put("poolType", poolType);
        pool.put("poolVersion", "1.2.8");
        pool.put("metricsAvailability", "FULL");
        pool.put("activeConnections", active);
        pool.put("idleConnections", idle);
        pool.put("totalConnections", active + idle);
        pool.put("maxConnections", max);
        pool.put("minIdleConnections", 2);
        pool.put("threadsAwaitingConnection", 0);
        pool.put("connectionTimeoutMs", 30000L);
        pool.put("sampleTime", System.currentTimeMillis());
        pool.put("unavailableMetrics", Arrays.asList());
        return pool;
    }
}
