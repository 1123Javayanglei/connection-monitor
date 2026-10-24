package com.connectionmonitor.server.api;

import com.connectionmonitor.server.domain.MetricsReport;
import com.connectionmonitor.server.repository.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标接收 API：业务 Pod 中的 Starter 周期性 POST 完整报文到这里。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsReportController {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsReportController.class);

    private final MonitorRepository repository;

    public MetricsReportController(MonitorRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> report(@RequestBody MetricsReport report) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (report == null || isBlank(report.getInstanceId())) {
            body.put("status", "rejected");
            body.put("reason", "instanceId is required");
            return ResponseEntity.badRequest().body(body);
        }
        int poolCount = report.getPools() == null ? 0 : report.getPools().size();
        try {
            repository.saveReport(report);
            LOG.debug("接收实例 [{}] 上报，连接池 {} 个", report.getInstanceId(), poolCount);
            body.put("status", "ok");
            body.put("pools", poolCount);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            LOG.warn("保存实例 [{}] 上报失败: {}", report.getInstanceId(), e.getMessage());
            body.put("status", "error");
            body.put("reason", e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
