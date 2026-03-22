package io.cloudmeter.sidecar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestMetrics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP ingest server that receives metric reports from Python/Node.js clients.
 *
 * Binds to 127.0.0.1 only for security — must not be exposed on a public interface.
 *
 * Endpoints:
 *   POST /api/metrics  — ingest a JSON metric payload
 *   GET  /api/status   — health check
 */
public final class IngestServer {

    private final MetricsStore store;
    private final int          port;
    private HttpServer         server;

    public IngestServer(MetricsStore store, int port) {
        if (store == null) throw new NullPointerException("store");
        this.store = store;
        this.port  = port;
    }

    /**
     * Start the ingest HTTP server. Binds to 127.0.0.1 only.
     *
     * @throws IOException if the port is unavailable
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/metrics", new MetricsHandler());
        server.createContext("/api/status",  new StatusHandler());
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cloudmeter-ingest");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** Stop the server gracefully. */
    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    /** Returns the actual port this server is bound to (useful when port=0 was used). */
    public int getPort() {
        if (server == null) return port;
        return server.getAddress().getPort();
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String body;
            try {
                body = readBody(exchange.getRequestBody());
            } catch (IOException e) {
                sendResponse(exchange, 400, "text/plain", "Bad Request: cannot read body");
                return;
            }

            RequestMetrics metrics;
            try {
                metrics = parseMetrics(body);
            } catch (ParseException e) {
                sendResponse(exchange, 400, "application/json",
                        "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                return;
            }

            store.add(metrics);
            sendResponse(exchange, 202, "application/json", "{\"accepted\":true}");
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            boolean recording = store.isRecording();
            int total         = store.size();
            String json = "{\"status\":\"ok\",\"recording\":"
                    + recording + ",\"totalMetrics\":" + total + "}";
            sendResponse(exchange, 200, "application/json", json);
        }
    }

    // ── JSON parser (no external library) ─────────────────────────────────────

    /**
     * Parses the ingest JSON payload using simple string operations.
     *
     * Expected format:
     * <pre>
     * {
     *   "route": "GET /api/users/{id}",
     *   "method": "GET",
     *   "status": 200,
     *   "durationMs": 45,
     *   "egressBytes": 1024
     * }
     * </pre>
     *
     * @throws ParseException if required fields are missing or the input is malformed
     */
    static RequestMetrics parseMetrics(String json) throws ParseException {
        if (json == null || json.isBlank()) {
            throw new ParseException("Empty or null JSON body");
        }

        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new ParseException("Body is not a JSON object");
        }

        String route       = extractStringField(trimmed, "route");
        String method      = extractStringField(trimmed, "method");
        int    status      = extractIntField(trimmed, "status", 200);
        long   durationMs  = extractLongField(trimmed, "durationMs");
        long   egressBytes = extractOptionalLongField(trimmed, "egressBytes", 0L);

        if (route == null || route.isEmpty()) {
            throw new ParseException("Missing required field: route");
        }
        if (method == null || method.isEmpty()) {
            throw new ParseException("Missing required field: method");
        }
        // durationMs must have been found (extractLongField throws if absent)

        return RequestMetrics.builder()
                .routeTemplate(route)
                .actualPath(route)
                .httpMethod(method)
                .httpStatusCode(status)
                .durationMs(durationMs)
                .cpuCoreSeconds(0.0)
                .peakMemoryBytes(0L)
                .egressBytes(egressBytes)
                .threadWaitRatio(0.0)
                .timestamp(Instant.now())
                .warmup(false)
                .build();
    }

    /**
     * Extracts a JSON string field value. Returns null if the field is absent.
     *
     * Handles both {@code "field": "value"} and {@code "field":"value"}.
     */
    static String extractStringField(String json, String field) throws ParseException {
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) throw new ParseException("Malformed JSON near field: " + field);

        // skip whitespace after colon
        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
            throw new ParseException("Expected string value for field: " + field);
        }

        // find closing quote (handle simple escaped quotes)
        int start = valueStart + 1;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        throw new ParseException("Unterminated string value for field: " + field);
    }

    /**
     * Extracts a JSON numeric field as int. Returns {@code defaultValue} if absent.
     */
    static int extractIntField(String json, String field, int defaultValue) throws ParseException {
        String raw = extractRawNumericField(json, field);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid integer value for field: " + field);
        }
    }

    /**
     * Extracts a required JSON numeric field as long. Throws if absent.
     */
    static long extractLongField(String json, String field) throws ParseException {
        String raw = extractRawNumericField(json, field);
        if (raw == null) throw new ParseException("Missing required field: " + field);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid long value for field: " + field);
        }
    }

    /**
     * Extracts an optional JSON numeric field as long. Returns {@code defaultValue} if absent.
     */
    static long extractOptionalLongField(String json, String field, long defaultValue) throws ParseException {
        String raw = extractRawNumericField(json, field);
        if (raw == null) return defaultValue;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid long value for field: " + field);
        }
    }

    /**
     * Extracts the raw token for a numeric field, or null if absent.
     */
    private static String extractRawNumericField(String json, String field) {
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;

        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        int valueEnd = valueStart;
        while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
            valueEnd++;
        }

        if (valueEnd == valueStart) return null;
        return json.substring(valueStart, valueEnd).trim();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String readBody(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    private static void sendResponse(HttpExchange exchange, int code,
                                     String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── ParseException ────────────────────────────────────────────────────────

    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }
}
