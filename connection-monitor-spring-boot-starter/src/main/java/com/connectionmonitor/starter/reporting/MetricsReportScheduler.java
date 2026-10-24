package com.connectionmonitor.starter.reporting;

import com.connectionmonitor.starter.ConnectionMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 采集调度器：自带单线程 ScheduledExecutorService（守护线程），
 * 不依赖业务应用的 @EnableScheduling；任务内部消化所有异常，
 * 保证调度不中断、业务不受影响。
 */
public class MetricsReportScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsReportScheduler.class);

    private final MetricsCollectionService collectionService;
    private final HttpMetricsReporter reporter;
    private final ConnectionMonitorProperties properties;
    private ScheduledExecutorService executor;

    public MetricsReportScheduler(MetricsCollectionService collectionService,
                                  HttpMetricsReporter reporter,
                                  ConnectionMonitorProperties properties) {
        this.collectionService = collectionService;
        this.reporter = reporter;
        this.properties = properties;
    }

    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        long interval = Math.max(1000L, properties.getReportIntervalMs());
        long initialDelay = Math.max(0L, properties.getReportInitialDelayMs());
        executor = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "connection-monitor-reporter");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    reporter.report(collectionService.collectReport());
                } catch (Throwable t) {
                    // scheduleWithFixedDelay 遇到未捕获异常会停止后续调度，必须全部捕获
                    LOG.warn("连接池监控采集周期异常: {}", t.toString());
                }
            }
        }, initialDelay, interval, TimeUnit.MILLISECONDS);
        LOG.info("connection-monitor 已启动：上报间隔 {} ms，首次延迟 {} ms，服务端 {}",
                interval, initialDelay,
                reporter.isAvailable() ? properties.getServerUrl() : "未配置（仅采集）");
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
