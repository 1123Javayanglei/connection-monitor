package com.connectionmonitor.server.web;

import com.connectionmonitor.server.repository.InstanceRow;

import java.text.SimpleDateFormat;
import java.util.Date;

/** 实例列表页视图模型（格式化后的展示字段）。 */
public class InstanceView {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final InstanceRow row;
    private final boolean online;
    private final String lastReportDisplay;

    public InstanceView(InstanceRow row, boolean online, long now) {
        this.row = row;
        this.online = online;
        if (row.getLastReportTime() != null) {
            long agoSeconds = Math.max(0, (now - row.getLastReportTime()) / 1000);
            this.lastReportDisplay = DATE_FORMAT.format(new Date(row.getLastReportTime()))
                    + "（" + agoSeconds + " 秒前）";
        } else {
            this.lastReportDisplay = "—";
        }
    }

    public InstanceRow getRow() {
        return row;
    }

    public boolean isOnline() {
        return online;
    }

    public String getLastReportDisplay() {
        return lastReportDisplay;
    }

    public String getHeapDisplay() {
        if (row.getHeapUsedBytes() == null || row.getHeapMaxBytes() == null) {
            return null;
        }
        return mb(row.getHeapUsedBytes()) + " / " + mb(row.getHeapMaxBytes()) + " MB";
    }

    public String getCpuDisplay() {
        if (row.getProcessCpuLoad() == null) {
            return null;
        }
        return String.format("%.1f%%", row.getProcessCpuLoad() * 100);
    }

    public int getHeapUsedPercent() {
        if (row.getHeapUsedBytes() == null || row.getHeapMaxBytes() == null
                || row.getHeapMaxBytes() <= 0) {
            return 0;
        }
        return (int) (row.getHeapUsedBytes() * 100 / row.getHeapMaxBytes());
    }

    private static String mb(long bytes) {
        return String.format("%,d", bytes / (1024 * 1024));
    }
}
