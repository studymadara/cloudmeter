package io.cloudmeter.reporter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.costengine.CostProjector;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.ProjectionConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP dashboard server (ADR-004: in-process, no separate process required).
 *
 * Endpoints:
 *   GET  /               → serves the dashboard HTML page
 *   GET  /api/projections → JSON cost projections (live, recomputed on each request)
 *   POST /api/recording/start → starts/resets the MetricsStore recording
 *   POST /api/recording/stop  → stops recording (data retained for projection)
 *
 * Security note (ADR-011): this server binds to localhost only and has no
 * authentication.  It MUST NOT be exposed to a public network interface.
 */
public final class DashboardServer {

    static final int DEFAULT_PORT = 7777;

    private final MetricsStore     store;
    private final ProjectionConfig config;
    private final int              port;
    private HttpServer             server;

    public DashboardServer(MetricsStore store, ProjectionConfig config) {
        this(store, config, DEFAULT_PORT);
    }

    public DashboardServer(MetricsStore store, ProjectionConfig config, int port) {
        if (store  == null) throw new NullPointerException("store");
        if (config == null) throw new NullPointerException("config");
        this.store  = store;
        this.config = config;
        this.port   = port;
    }

    /**
     * Starts the HTTP server. Binds to 127.0.0.1 only (ADR-011).
     *
     * @throws IOException if the port is already in use or cannot be bound
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/projections",     new ProjectionsHandler());
        server.createContext("/api/recording/start", new RecordingStartHandler());
        server.createContext("/api/recording/stop",  new RecordingStopHandler());
        server.createContext("/",                    new StaticHandler());
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cloudmeter-dashboard");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** Stops the HTTP server gracefully with a 1-second delay. */
    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    /** Returns the port this server is bound to (useful when port=0 was requested). */
    public int getPort() {
        if (server == null) return port;
        return server.getAddress().getPort();
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private class ProjectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            List<EndpointCostProjection> projections =
                    CostProjector.project(store.getAll(), config);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            JsonReporter.print(projections, config, new PrintStream(buf, true, "UTF-8"));
            byte[] body = buf.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private class RecordingStartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            store.startRecording();
            sendResponse(exchange, 200, "application/json", "{\"status\":\"recording\"}");
        }
    }

    private class RecordingStopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            store.stopRecording();
            sendResponse(exchange, 200, "application/json", "{\"status\":\"stopped\"}");
        }
    }

    private static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String html = loadDashboardHtml();
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static void sendResponse(HttpExchange exchange, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String loadDashboardHtml() {
        InputStream is = DashboardServer.class.getResourceAsStream(
                "/io/cloudmeter/reporter/dashboard.html");
        return loadFromStream(is);
    }

    static String loadFromStream(InputStream is) {
        if (is == null) {
            return "<html><body><h1>CloudMeter Dashboard</h1>"
                    + "<p>dashboard.html resource not found.</p></body></html>";
        }
        try {
            byte[] bytes = readAll(is);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<html><body><h1>CloudMeter Dashboard</h1>"
                    + "<p>Error loading dashboard: " + e.getMessage() + "</p></body></html>";
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
