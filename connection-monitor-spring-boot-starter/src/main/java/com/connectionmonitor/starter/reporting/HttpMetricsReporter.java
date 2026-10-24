package com.connectionmonitor.starter.reporting;

import com.connectionmonitor.starter.ConnectionMonitorProperties;
import com.connectionmonitor.starter.model.MetricsReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 指标上报器：业务 Pod 主动 POST 到监控服务端。
 *
 * <p>失败不影响业务：所有异常内部消化；为避免日志风暴，只在首次失败和之后每第 10 次失败时告警。
 * Jackson 不在 classpath 时降级为不上报（记录一次错误），不抛异常。</p>
 */
public class HttpMetricsReporter {

    private static final Logger LOG = LoggerFactory.getLogger(HttpMetricsReporter.class);

    private final ConnectionMonitorProperties properties;
    private final RestTemplate restTemplate;
    private final String endpoint;
    private final boolean available;

    private int failureCount;
    private boolean urlAbsentLogged;

    public HttpMetricsReporter(ConnectionMonitorProperties properties) {
        this.properties = properties;
        boolean jacksonPresent = ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper",
                HttpMetricsReporter.class.getClassLoader());
        RestTemplate template = null;
        String url = null;
        if (properties.getServerUrl() != null && properties.getServerUrl().trim().length() > 0
                && jacksonPresent) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(properties.getRequestTimeoutMs());
            factory.setReadTimeout(properties.getRequestTimeoutMs());
            template = new RestTemplate(factory);
            url = properties.getServerUrl().trim().replaceAll("/+$", "");
        } else if (!jacksonPresent && properties.getServerUrl() != null
                && properties.getServerUrl().trim().length() > 0) {
            LOG.error("classpath 缺少 jackson-databind，无法上报连接池指标；"
                    + "请为业务应用引入 spring-boot-starter-web 或 jackson-databind");
        }
        this.restTemplate = template;
        this.endpoint = url == null ? null : url + "/api/metrics/report";
        this.available = this.endpoint != null;
    }

    /** 返回 true 表示本次上报成功。 */
    public boolean report(MetricsReport report) {
        if (!available) {
            if (!urlAbsentLogged) {
                urlAbsentLogged = true;
                LOG.info("未配置 connection.monitor.server-url 或序列化能力不可用，"
                        + "连接池指标仅采集不上报（JVM 与连接池发现仍正常运行）");
            }
            return false;
        }
        try {
            restTemplate.postForEntity(endpoint, report, String.class);
            if (failureCount > 0) {
                LOG.info("连接池指标上报恢复，此前连续失败 {} 次", failureCount);
            }
            failureCount = 0;
            return true;
        } catch (Throwable t) {
            failureCount++;
            if (failureCount == 1 || failureCount % 10 == 0) {
                LOG.warn("连接池指标上报失败（连续第 {} 次）: {}", failureCount, t.toString());
            }
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }
}
