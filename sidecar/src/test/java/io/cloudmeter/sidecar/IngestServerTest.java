package io.cloudmeter.sidecar;

import io.cloudmeter.collector.MetricsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class IngestServerTest {

    private MetricsStore store;
    private IngestServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        store = new MetricsStore();
        store.startRecording();
        server = new IngestServer(store, 0); // port 0 = OS picks a free port
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ── /api/metrics ──────────────────────────────────────────────────────────

    @Test
    void validJsonPostAddsMetricToStore() throws Exception {
        String json = "{\"route\":\"GET /api/users/{id}\","
                + "\"method\":\"GET\","
                + "\"status\":200,"
                + "\"durationMs\":45,"
                + "\"egressBytes\":1024}";

        int responseCode = postJson("/api/metrics", json);

        assertEquals(202, responseCode);
        assertEquals(1, store.size());
        assertEquals("GET /api/users/{id}", store.getAll().get(0).getRouteTemplate());
    }

    @Test
    void getToMetricsEndpointReturns405() throws Exception {
        HttpURLConnection conn = open("/api/metrics", "GET");
        conn.connect();
        assertEquals(405, conn.getResponseCode());
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        int responseCode = postJson("/api/metrics", "not-json-at-all");
        assertEquals(400, responseCode);
    }

    @Test
    void missingRequiredFieldRouteReturns400() throws Exception {
        // Missing "route" field
        String json = "{\"method\":\"GET\",\"status\":200,\"durationMs\":45}";
        int responseCode = postJson("/api/metrics", json);
        assertEquals(400, responseCode);
        assertEquals(0, store.size());
    }

    @Test
    void missingRequiredFieldDurationMsReturns400() throws Exception {
        // Missing "durationMs" field
        String json = "{\"route\":\"GET /api/test\",\"method\":\"GET\",\"status\":200}";
        int responseCode = postJson("/api/metrics", json);
        assertEquals(400, responseCode);
        assertEquals(0, store.size());
    }

    // ── /api/status ───────────────────────────────────────────────────────────

    @Test
    void getStatusReturnsOkJson() throws Exception {
        HttpURLConnection conn = open("/api/status", "GET");
        conn.connect();
        assertEquals(200, conn.getResponseCode());

        String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"status\":\"ok\""), "body=" + body);
        assertTrue(body.contains("\"recording\":true"), "body=" + body);
        assertTrue(body.contains("\"totalMetrics\":0"), "body=" + body);
    }

    @Test
    void statusReflectsTotalMetricsAfterIngest() throws Exception {
        postJson("/api/metrics", "{\"route\":\"GET /ping\",\"method\":\"GET\","
                + "\"status\":200,\"durationMs\":5}");

        HttpURLConnection conn = open("/api/status", "GET");
        conn.connect();
        String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"totalMetrics\":1"), "body=" + body);
    }

    @Test
    void postToStatusEndpointReturns405() throws Exception {
        int code = postJson("/api/status", "{}");
        assertEquals(405, code);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int postJson(String path, String json) throws Exception {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = open(path, "POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Content-Length", String.valueOf(data.length));
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }
        return conn.getResponseCode();
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        return conn;
    }
}
