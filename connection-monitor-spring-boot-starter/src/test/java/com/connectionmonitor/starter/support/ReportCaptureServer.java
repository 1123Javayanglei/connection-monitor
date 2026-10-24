package com.connectionmonitor.starter.support;

import com.connectionmonitor.starter.model.MetricsReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * 测试用内嵌 HTTP 服务：接收 Starter 上报的 JSON 报文并保存，供场景测试断言。
 */
public final class ReportCaptureServer implements Closeable {

    private final HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<String>();
    private final ObjectMapper mapper = new ObjectMapper();

    private ReportCaptureServer(HttpServer server) {
        this.server = server;
    }

    public static ReportCaptureServer start(int port) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        final ReportCaptureServer capture = new ReportCaptureServer(httpServer);
        httpServer.createContext("/api/metrics/report", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    String body = readBody(exchange);
                    capture.bodies.add(body);
                    byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    OutputStream out = exchange.getResponseBody();
                    out.write(response);
                    out.close();
                } catch (Throwable t) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            }
        });
        httpServer.setExecutor(Executors.newSingleThreadExecutor());
        httpServer.start();
        return capture;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public List<MetricsReport> parseReports() {
        List<MetricsReport> reports = new ArrayList<MetricsReport>();
        for (String body : bodies) {
            try {
                reports.add(mapper.readValue(body, MetricsReport.class));
            } catch (IOException e) {
                throw new IllegalStateException("解析上报报文失败: " + body, e);
            }
        }
        return reports;
    }

    /** 轮询等待满足条件的第一份报文，超时抛 AssertionError。 */
    public MetricsReport awaitReport(long timeoutMs, Predicate<MetricsReport> condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (MetricsReport report : parseReports()) {
                if (condition.test(report)) {
                    return report;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("在 " + timeoutMs + " ms 内未等到满足条件的上报报文，共收到 "
                + bodies.size() + " 份");
    }
}
