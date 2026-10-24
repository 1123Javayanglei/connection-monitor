package com.connectionmonitor.server.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 历史数据保留期清理：每天 03:30 删除保留期之外的记录，防止历史表无限增长。
 */
@Component
public class HistoryCleanupTask {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryCleanupTask.class);

    private final MonitorRepository repository;
    private final int retentionDays;

    public HistoryCleanupTask(MonitorRepository repository,
                              @Value("${connection-monitor.history.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void purge() {
        try {
            long before = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000;
            int deleted = repository.purgeHistoryBefore(before);
            LOG.info("历史数据清理完成：删除 {} 条保留期（{} 天）之外的记录", deleted, retentionDays);
        } catch (Throwable t) {
            LOG.warn("历史数据清理失败: {}", t.toString());
        }
    }
}
