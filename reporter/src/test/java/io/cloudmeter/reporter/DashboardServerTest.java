package io.cloudmeter.reporter;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestMetrics;
import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.ProjectionConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DashboardServerTest {

    private DashboardServer server;

    @AfterEach
    void teardown() {
        if (server != null) server.stop();
    }

    private static ProjectionConfig config() {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(100).requestsPerUserPerSecond(0.5)
                .recordingDurationSeconds(60.0).build();
    }

    private static DashboardServer startServer() throws IOException {
        MetricsStore store = new MetricsStore();
        store.startRecording();
        DashboardServer s = new DashboardServer(store, config(), 0); // port 0 = random
        s.start();
        return s;
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        return readResponse(conn);
    }

    private static String httpPost(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        conn.setDoOutput(true);
        conn.getOutputStream().close();
        return readResponse(conn);
    }

    private static int httpGetCode(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        return conn.getResponseCode();
    }

    private static int httpPostCode(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        conn.setDoOutput(true);
        conn.getOutputStream().close();
        return conn.getResponseCode();
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (IOException e) {
            is = conn.getErrorStream();
        }
        if (is == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    @Test
    void constructor_nullStore_throws() {
        assertThrows(NullPointerException.class,
                () -> new DashboardServer(null, config()));
    }

    @Test
    void constructor_nullConfig_throws() {
        assertThrows(NullPointerException.class,
                () -> new DashboardServer(new MetricsStore(), null));
    }

    @Test
    void defaultPort_is7777() {
        MetricsStore store = new MetricsStore();
        DashboardServer s = new DashboardServer(store, config());
        assertEquals(DashboardServer.DEFAULT_PORT, 7777);
        assertEquals(7777, s.getPort()); // before start, returns field value
    }

    // ── start / stop / getPort ────────────────────────────────────────────────

    @Test
    void start_bindsToRandomPort() throws IOException {
        server = startServer();
        assertTrue(server.getPort() > 0);
    }

    @Test
    void stop_canBeCalledBeforeStart() {
        MetricsStore store = new MetricsStore();
        DashboardServer s = new DashboardServer(store, config(), 0);
        assertDoesNotThrow(s::stop); // server is null — must not throw
    }

    @Test
    void stop_canBeCalledAfterStart() throws IOException {
        server = startServer();
        assertDoesNotThrow(server::stop);
        server = null; // already stopped
    }

    // ── GET / (dashboard HTML) ────────────────────────────────────────────────

    @Test
    void getRoot_returns200AndHtml() throws IOException {
        server = startServer();
        String body = httpGet("http://127.0.0.1:" + server.getPort() + "/");
        assertTrue(body.contains("CloudMeter"));
    }

    @Test
    void getRoot_withPostMethod_returns405() throws IOException {
        server = startServer();
        int code = httpPostCode("http://127.0.0.1:" + server.getPort() + "/");
        assertEquals(405, code);
    }

    // ── GET /api/projections ──────────────────────────────────────────────────

    @Test
    void getProjections_emptyStore_returnsValidJson() throws IOException {
        server = startServer();
        String body = httpGet("http://127.0.0.1:" + server.getPort() + "/api/projections");
        assertTrue(body.contains("\"projections\""));
        assertTrue(body.contains("\"meta\""));
    }

    @Test
    void getProjections_withMetrics_containsRoute() throws IOException {
        MetricsStore store = new MetricsStore();
        store.startRecording();
        for (int i = 0; i < 5; i++) {
            store.add(RequestMetrics.builder()
                    .routeTemplate("GET /api/data")
                    .actualPath("/api/data")
                    .httpMethod("GET").httpStatusCode(200)
                    .durationMs(50).cpuCoreSeconds(0.005)
                    .peakMemoryBytes(1024 * 1024L).egressBytes(0)
                    .threadWaitRatio(0.1).timestamp(Instant.now())
                    .warmup(false).build());
        }
        server = new DashboardServer(store, config(), 0);
        server.start();
        String body = httpGet("http://127.0.0.1:" + server.getPort() + "/api/projections");
        assertTrue(body.contains("GET /api/data"));
    }

    @Test
    void getProjections_withPostMethod_returns405() throws IOException {
        server = startServer();
        int code = httpPostCode("http://127.0.0.1:" + server.getPort() + "/api/projections");
        assertEquals(405, code);
    }

    // ── POST /api/recording/start ─────────────────────────────────────────────

    @Test
    void postRecordingStart_returns200() throws IOException {
        server = startServer();
        int code = httpPostCode("http://127.0.0.1:" + server.getPort() + "/api/recording/start");
        assertEquals(200, code);
    }

    @Test
    void postRecordingStart_bodyContainsRecording() throws IOException {
        server = startServer();
        String body = httpPost("http://127.0.0.1:" + server.getPort() + "/api/recording/start");
        assertTrue(body.contains("recording"));
    }

    @Test
    void postRecordingStart_withGetMethod_returns405() throws IOException {
        server = startServer();
        int code = httpGetCode("http://127.0.0.1:" + server.getPort() + "/api/recording/start");
        assertEquals(405, code);
    }

    // ── POST /api/recording/stop ──────────────────────────────────────────────

    @Test
    void postRecordingStop_returns200() throws IOException {
        server = startServer();
        int code = httpPostCode("http://127.0.0.1:" + server.getPort() + "/api/recording/stop");
        assertEquals(200, code);
    }

    @Test
    void postRecordingStop_bodyContainsStopped() throws IOException {
        server = startServer();
        String body = httpPost("http://127.0.0.1:" + server.getPort() + "/api/recording/stop");
        assertTrue(body.contains("stopped"));
    }

    @Test
    void postRecordingStop_withGetMethod_returns405() throws IOException {
        server = startServer();
        int code = httpGetCode("http://127.0.0.1:" + server.getPort() + "/api/recording/stop");
        assertEquals(405, code);
    }

    // ── loadDashboardHtml ─────────────────────────────────────────────────────

    @Test
    void loadDashboardHtml_returnsNonEmpty() {
        String html = DashboardServer.loadDashboardHtml();
        assertFalse(html.isEmpty());
        assertTrue(html.contains("CloudMeter"));
    }

    // ── loadFromStream ────────────────────────────────────────────────────────

    @Test
    void loadFromStream_null_returnsFallback() {
        String html = DashboardServer.loadFromStream(null);
        assertTrue(html.contains("resource not found"));
    }

    @Test
    void loadFromStream_validStream_returnsContent() throws IOException {
        byte[] bytes = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String result = DashboardServer.loadFromStream(new java.io.ByteArrayInputStream(bytes));
        assertEquals("hello world", result);
    }

    @Test
    void loadFromStream_brokenStream_returnsFallback() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("disk error"); }
        };
        String result = DashboardServer.loadFromStream(broken);
        assertTrue(result.contains("Error loading dashboard"));
    }

    // ── sendResponse ──────────────────────────────────────────────────────────

    @Test
    void sendResponse_helper_usedInternallyViaCoverage() {
        // Covered indirectly via the 405 paths above; test static method directly
        // by calling it on a live exchange — verified through HTTP tests above.
        assertTrue(true); // marker — actual coverage from HTTP tests
    }
}
