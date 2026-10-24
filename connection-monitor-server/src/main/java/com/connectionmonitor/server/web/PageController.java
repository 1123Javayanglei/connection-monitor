package com.connectionmonitor.server.web;

import com.connectionmonitor.server.repository.InstanceRow;
import com.connectionmonitor.server.repository.MonitorRepository;
import com.connectionmonitor.server.repository.PoolRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 页面路由：实例列表 → 实例详情（连接池表格，可按类型/Bean 名筛选）→ 连接池详情（历史趋势图）。
 */
@Controller
public class PageController {

    private static final Logger LOG = LoggerFactory.getLogger(PageController.class);

    /** 超过该时长未上报视为离线（默认上报周期 30s 的 3 倍）。 */
    private static final long ONLINE_THRESHOLD_MS = 90_000L;

    private final MonitorRepository repository;

    public PageController(MonitorRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/")
    public String instances(Model model) {
        List<InstanceRow> instances = repository.listInstances();
        long now = System.currentTimeMillis();
        List<InstanceView> views = new java.util.ArrayList<InstanceView>();
        for (InstanceRow instance : instances) {
            boolean online = instance.getLastReportTime() != null
                    && now - instance.getLastReportTime() < ONLINE_THRESHOLD_MS;
            views.add(new InstanceView(instance, online, now));
        }
        model.addAttribute("views", views);
        return "instances";
    }

    @GetMapping("/instance/{instanceId}")
    public String instanceDetail(@PathVariable("instanceId") String instanceId,
                                 @RequestParam(value = "poolType", required = false) String poolType,
                                 @RequestParam(value = "beanName", required = false) String beanName,
                                 Model model) {
        InstanceRow instance = repository.getInstance(instanceId);
        List<PoolRow> pools = repository.listPools(instanceId, poolType, beanName);
        model.addAttribute("instance", instance);
        model.addAttribute("pools", pools);
        model.addAttribute("poolTypes", repository.distinctPoolTypes(instanceId));
        model.addAttribute("beanNames", repository.distinctBeanNames(instanceId));
        model.addAttribute("poolTypeFilter", poolType == null ? "" : poolType);
        model.addAttribute("beanNameFilter", beanName == null ? "" : beanName);
        return "instance-detail";
    }

    /**
     * 连接池详情页。poolId 使用查询参数传递（含 ":" 等字符，查询参数在各容器下解码行为一致）。
     */
    @GetMapping("/pool")
    public String poolDetail(@RequestParam("instanceId") String instanceId,
                             @RequestParam("poolId") String poolId,
                             Model model) {
        PoolRow pool = repository.getPool(instanceId, poolId);
        if (pool == null) {
            LOG.debug("连接池详情未命中: instanceId=[{}], poolId=[{}]", instanceId, poolId);
        }
        model.addAttribute("instance", repository.getInstance(instanceId));
        model.addAttribute("pool", pool);
        return "pool-detail";
    }
}
