package com.connectionmonitor.server.api;

import com.connectionmonitor.server.repository.InstanceRow;
import com.connectionmonitor.server.repository.MonitorRepository;
import com.connectionmonitor.server.repository.PoolHistoryPoint;
import com.connectionmonitor.server.repository.PoolRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询 API：实例列表、连接池列表与历史趋势（供页面 AJAX 与外部系统使用）。
 */
@RestController
@RequestMapping("/api")
public class HistoryApiController {

    private static final long DEFAULT_RANGE_MS = 6L * 60 * 60 * 1000;

    private final MonitorRepository repository;

    public HistoryApiController(MonitorRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/instances")
    public List<InstanceRow> instances() {
        return repository.listInstances();
    }

    @GetMapping("/pools")
    public List<PoolRow> pools(@RequestParam("instanceId") String instanceId,
                               @RequestParam(value = "poolType", required = false) String poolType,
                               @RequestParam(value = "beanName", required = false) String beanName) {
        return repository.listPools(instanceId, poolType, beanName);
    }

    /**
     * 历史趋势查询：instanceId + poolId + startTime + endTime + granularity。
     * granularity 取值 raw / 1m / 5m / 1h（raw 表示不分桶）。
     */
    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam("instanceId") String instanceId,
                                       @RequestParam("poolId") String poolId,
                                       @RequestParam(value = "startTime", required = false) Long startTime,
                                       @RequestParam(value = "endTime", required = false) Long endTime,
                                       @RequestParam(value = "granularity", defaultValue = "auto")
                                               String granularity) {
        long end = endTime != null ? endTime : System.currentTimeMillis();
        long start = startTime != null ? startTime : end - DEFAULT_RANGE_MS;
        long granularityMs = resolveGranularity(granularity, start, end);
        List<PoolHistoryPoint> points = repository.queryHistory(instanceId, poolId, start, end, granularityMs);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("instanceId", instanceId);
        body.put("poolId", poolId);
        body.put("startTime", start);
        body.put("endTime", end);
        body.put("granularityMs", granularityMs);
        body.put("points", points);
        return body;
    }

    private long resolveGranularity(String granularity, long start, long end) {
        if ("raw".equalsIgnoreCase(granularity)) {
            return 0;
        }
        if ("1m".equalsIgnoreCase(granularity)) {
            return 60_000L;
        }
        if ("5m".equalsIgnoreCase(granularity)) {
            return 300_000L;
        }
        if ("1h".equalsIgnoreCase(granularity)) {
            return 3_600_000L;
        }
        // auto：按时间跨度选择粒度，控制点位数量
        long span = Math.max(1, end - start);
        if (span <= 2L * 60 * 60 * 1000) {
            return 0;
        } else if (span <= 12L * 60 * 60 * 1000) {
            return 60_000L;
        } else if (span <= 3L * 24 * 60 * 60 * 1000) {
            return 300_000L;
        }
        return 3_600_000L;
    }
}
