package io.cloudmeter.integration;

import io.cloudmeter.cli.CliArgs;
import io.cloudmeter.cli.CloudMeterCli;
import io.cloudmeter.cli.ReportCommand;
import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestMetrics;
import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.CostProjector;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.reporter.DashboardServer;
import io.cloudmeter.reporter.JsonReporter;
import io.cloudmeter.reporter.TerminalReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end pipeline integration tests.
 *
 * These tests exercise the full path:
 *   MetricsStore → CostProjector → Reporter (terminal / JSON / dashboard)
 *                                            → CLI (ReportCommand / CloudMeterCli)
 *
 * No Byte Buddy instrumentation or subprocess spawning is required — the metrics
 * store is populated directly to simulate what the HTTP interceptor would capture.
 * The DashboardServer runs on a random port (0) so tests can run in parallel.
 */
class PipelineIntegrationTest {

    private MetricsStore     store;
    private ProjectionConfig config;
    private DashboardServer  dashboard;
    private int              dashboardPort;

    @BeforeEach
    void setup() throws Exception {
        store = new MetricsStore();
        store.startRecording();

        // Simulate 10 requests to three routes
        addRequests("GET /api/users/{id}",    0.010, 512_000,   20);
        addRequests("POST /api/export/pdf",   0.850, 5_000_000, 5);
        addRequests("GET /api/health",        0.001, 128,       50);

        config = ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).budgetUsd(50.0)
                .build();

        dashboard = new DashboardServer(store, config, 0);
        dashboard.start();
        dashboardPort = dashboard.getPort();
    }

    @AfterEach
    void teardown() {
        if (dashboard != null) dashboard.stop();
    }

    // ── CostProjector ─────────────────────────────────────────────────────────

    @Test
    void costProjector_producesThreeEndpoints() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        assertEquals(3, projections.size());
    }

    @Test
    void costProjector_sortedByMonthlyCostDescending() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        for (int i = 1; i < projections.size(); i++) {
            assertTrue(
                projections.get(i - 1).getProjectedMonthlyCostUsd()
                    >= projections.get(i).getProjectedMonthlyCostUsd(),
                "Projections not sorted descending at index " + i
            );
        }
    }

    @Test
    void costProjector_exportPdfIsTopCost() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        assertEquals("POST /api/export/pdf", projections.get(0).getRouteTemplate());
    }

    @Test
    void costProjector_exportPdfExceedsBudget() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        EndpointCostProjection pdf = projections.stream()
                .filter(p -> p.getRouteTemplate().contains("export"))
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(pdf.isExceedsBudget());
    }

    @Test
    void costProjector_healthEndpointCheapest() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        EndpointCostProjection health = projections.get(projections.size() - 1);
        assertEquals("GET /api/health", health.getRouteTemplate());
    }

    @Test
    void costProjector_costCurveHas12Points() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        projections.forEach(p ->
            assertEquals(CostProjector.SCALE_USERS.length, p.getCostCurve().size()));
    }

    // ── TerminalReporter ──────────────────────────────────────────────────────

    @Test
    void terminalReporter_containsAllThreeRoutes() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.print(projections, config, new PrintStream(buf));
        String out = buf.toString();

        assertTrue(out.contains("/api/users"));
        assertTrue(out.contains("/api/export/pdf"));
        assertTrue(out.contains("/api/health"));
    }

    @Test
    void terminalReporter_showsBudgetMarkerForExpensiveEndpoint() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.print(projections, config, new PrintStream(buf));
        assertTrue(buf.toString().contains(TerminalReporter.BUDGET_MARKER));
    }

    @Test
    void terminalReporter_showsPositiveTotalCost() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.print(projections, config, new PrintStream(buf));
        // Total line must contain a non-zero cost
        String out = buf.toString();
        assertTrue(out.contains("Total projected monthly cost: $"));
        assertFalse(out.contains("$0.00"));
    }

    // ── JsonReporter ──────────────────────────────────────────────────────────

    @Test
    void jsonReporter_returnsTrueWhenBudgetExceeded() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean exceeded = JsonReporter.print(projections, config, new PrintStream(buf));
        assertTrue(exceeded);
    }

    @Test
    void jsonReporter_outputIsValidJson() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonReporter.print(projections, config, new PrintStream(buf));
        String json = buf.toString();

        // Basic structural checks
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}\n"));
        assertTrue(json.contains("\"projections\""));
        assertTrue(json.contains("\"meta\""));
        assertTrue(json.contains("\"summary\""));
    }

    @Test
    void jsonReporter_containsAllThreeRoutesInOutput() {
        List<EndpointCostProjection> projections = CostProjector.project(store.getAll(), config);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonReporter.print(projections, config, new PrintStream(buf));
        String json = buf.toString();

        assertTrue(json.contains("GET /api/users/{id}"));
        assertTrue(json.contains("POST /api/export/pdf"));
        assertTrue(json.contains("GET /api/health"));
    }

    // ── DashboardServer ───────────────────────────────────────────────────────

    @Test
    void dashboard_projectionsEndpoint_returns200() throws Exception {
        int code = httpGetCode("/api/projections");
        assertEquals(200, code);
    }

    @Test
    void dashboard_projectionsEndpoint_containsRoutes() throws Exception {
        String body = httpGet("/api/projections");
        assertTrue(body.contains("export/pdf"));
    }

    @Test
    void dashboard_rootEndpoint_returnsHtml() throws Exception {
        String body = httpGet("/");
        assertTrue(body.contains("CloudMeter"));
        assertTrue(body.toLowerCase().contains("<html"));
    }

    @Test
    void dashboard_recordingStart_resets_then_projectionsAreEmpty() throws Exception {
        // Start recording clears the store
        int code = httpPostCode("/api/recording/start");
        assertEquals(200, code);

        // After reset, projections array should contain no route objects
        String body = httpGet("/api/projections");
        assertTrue(body.contains("\"projections\""));
        assertFalse(body.contains("\"route\""));
    }

    @Test
    void dashboard_recordingStop_keepsExistingData() throws Exception {
        // Stop recording but data is retained
        int code = httpPostCode("/api/recording/stop");
        assertEquals(200, code);

        // Data from before stop is still projected
        String body = httpGet("/api/projections");
        assertTrue(body.contains("export/pdf"));
    }

    // ── ReportCommand → DashboardServer ───────────────────────────────────────

    @Test
    void reportCommand_terminal_againstLiveDashboard_containsRoutes() {
        ReportCommand cmd = new ReportCommand();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        cmd.run("127.0.0.1", dashboardPort, "terminal", config, new PrintStream(buf));
        String out = buf.toString();
        // At least one route should appear
        assertTrue(out.contains("/api/"));
    }

    @Test
    void reportCommand_json_againstLiveDashboard_containsProjectionsKey() {
        ReportCommand cmd = new ReportCommand();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        cmd.run("127.0.0.1", dashboardPort, "json", config, new PrintStream(buf));
        assertTrue(buf.toString().contains("\"projections\""));
    }

    // ── CloudMeterCli → DashboardServer ───────────────────────────────────────

    @Test
    void cloudMeterCli_report_budgetExceeded_returnsCode1() {
        int code = CloudMeterCli.run(
                new String[]{"report", "--port", String.valueOf(dashboardPort),
                        "--budget", "1"},    // $1 budget — definitely exceeded
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                new ReportCommand());
        assertEquals(CloudMeterCli.EXIT_BUDGET, code);
    }

    @Test
    void cloudMeterCli_report_largeBudget_returnsCode0() {
        int code = CloudMeterCli.run(
                new String[]{"report", "--port", String.valueOf(dashboardPort),
                        "--budget", "1000000"},   // $1M budget — never exceeded
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                new ReportCommand());
        assertEquals(CloudMeterCli.EXIT_OK, code);
    }

    @Test
    void cloudMeterCli_reportJson_returnsCode1_whenBudgetExceeded() {
        int code = CloudMeterCli.run(
                new String[]{"report", "--format", "json",
                        "--port", String.valueOf(dashboardPort),
                        "--budget", "1"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                new ReportCommand());
        assertEquals(CloudMeterCli.EXIT_BUDGET, code);
    }

    // ── CliArgs → ProjectionConfig round-trip ─────────────────────────────────

    @Test
    void cliArgs_agentArgFormat_parsesCorrectly() {
        // This is the format passed to -javaagent:cloudmeter-agent.jar=<ARGS>
        String agentArgs = "provider=GCP,region=us-central1,targetUsers=5000,rpu=2.0,budget=200,port=8080";
        CliArgs parsed = CliArgs.parse(agentArgs);
        ProjectionConfig cfg = parsed.toProjectionConfig();

        assertEquals(CloudProvider.GCP, cfg.getProvider());
        assertEquals("us-central1", cfg.getRegion());
        assertEquals(5_000, cfg.getTargetUsers());
        assertEquals(2.0, cfg.getRequestsPerUserPerSecond(), 1e-9);
        assertEquals(200.0, cfg.getBudgetUsd(), 1e-9);
        assertEquals(8080, parsed.getPort());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addRequests(String route, double cpuCoreSeconds,
                              long egressBytes, int count) {
        for (int i = 0; i < count; i++) {
            store.add(RequestMetrics.builder()
                    .routeTemplate(route)
                    .actualPath(route.replaceAll("\\{[^}]+}", "1"))
                    .httpMethod(route.split(" ")[0])
                    .httpStatusCode(200)
                    .durationMs((long) (cpuCoreSeconds * 1000))
                    .cpuCoreSeconds(cpuCoreSeconds)
                    .peakMemoryBytes(64 * 1024 * 1024L)
                    .egressBytes(egressBytes)
                    .threadWaitRatio(0.2)
                    .timestamp(Instant.now())
                    .warmup(false)
                    .build());
        }
    }

    private String httpGet(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + dashboardPort + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(5_000);
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.InputStream is = conn.getInputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString("UTF-8");
    }

    private int httpGetCode(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + dashboardPort + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(5_000);
        return conn.getResponseCode();
    }

    private int httpPostCode(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + dashboardPort + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(5_000);
        conn.setDoOutput(true);
        conn.getOutputStream().close();
        return conn.getResponseCode();
    }
}
