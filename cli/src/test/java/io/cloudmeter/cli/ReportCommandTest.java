package io.cloudmeter.cli;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.ProjectionConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportCommandTest {

    private static ProjectionConfig config() {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).build();
    }

    private static ProjectionConfig configWithBudget(double budget) {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).budgetUsd(budget).build();
    }

    private static final String SAMPLE_JSON = "{\n" +
            "  \"meta\": {\n" +
            "    \"provider\": \"AWS\",\n" +
            "    \"region\": \"us-east-1\",\n" +
            "    \"targetUsers\": 1000,\n" +
            "    \"requestsPerUserPerSecond\": 1.0,\n" +
            "    \"budgetUsd\": 0.0,\n" +
            "    \"pricingDate\": \"2025-01-01\"\n" +
            "  },\n" +
            "  \"projections\": [\n" +
            "    {\n" +
            "      \"route\": \"GET /api/users\",\n" +
            "      \"observedRps\": 2.5,\n" +
            "      \"projectedRps\": 250.0,\n" +
            "      \"projectedMonthlyCostUsd\": 42.5,\n" +
            "      \"projectedCostPerUserUsd\": 0.0425,\n" +
            "      \"recommendedInstance\": \"t3.nano\",\n" +
            "      \"exceedsBudget\": false,\n" +
            "      \"costCurve\": [\n" +
            "        {\"users\": 100, \"monthlyCostUsd\": 3.8},\n" +
            "        {\"users\": 1000, \"monthlyCostUsd\": 42.5}\n" +
            "      ]\n" +
            "    }\n" +
            "  ],\n" +
            "  \"summary\": {\n" +
            "    \"totalProjectedMonthlyCostUsd\": 42.5,\n" +
            "    \"anyExceedsBudget\": false\n" +
            "  }\n" +
            "}\n";

    private static final String EXCEEDS_JSON = SAMPLE_JSON.replace(
            "\"exceedsBudget\": false", "\"exceedsBudget\": true");

    // ── constructor ───────────────────────────────────────────────────────────

    @Test
    void constructor_nullFetcher_throws() {
        assertThrows(NullPointerException.class, () -> new ReportCommand(null));
    }

    @Test
    void defaultConstructor_doesNotThrow() {
        assertDoesNotThrow(() -> new ReportCommand());
    }

    // ── run() — terminal format ───────────────────────────────────────────────

    @Test
    void run_terminal_printsRoute() {
        ReportCommand cmd = new ReportCommand(url -> SAMPLE_JSON);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        cmd.run("127.0.0.1", 7777, "terminal", config(), new PrintStream(buf));
        assertTrue(buf.toString().contains("GET /api/users"));
    }

    @Test
    void run_terminal_returnsFalseWhenNoBudgetExceeded() {
        ReportCommand cmd = new ReportCommand(url -> SAMPLE_JSON);
        boolean result = cmd.run("127.0.0.1", 7777, "terminal", config(), new PrintStream(new ByteArrayOutputStream()));
        assertFalse(result);
    }

    @Test
    void run_terminal_returnsTrueWhenBudgetExceeded() {
        ReportCommand cmd = new ReportCommand(url -> EXCEEDS_JSON);
        boolean result = cmd.run("127.0.0.1", 7777, "terminal",
                configWithBudget(10.0), new PrintStream(new ByteArrayOutputStream()));
        assertTrue(result);
    }

    // ── run() — json format ───────────────────────────────────────────────────

    @Test
    void run_json_outputContainsProjections() {
        ReportCommand cmd = new ReportCommand(url -> SAMPLE_JSON);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        cmd.run("127.0.0.1", 7777, "json", config(), new PrintStream(buf));
        assertTrue(buf.toString().contains("\"projections\""));
    }

    @Test
    void run_json_returnsFalseWhenNoBudget() {
        ReportCommand cmd = new ReportCommand(url -> SAMPLE_JSON);
        boolean result = cmd.run("127.0.0.1", 7777, "json", config(),
                new PrintStream(new ByteArrayOutputStream()));
        assertFalse(result);
    }

    // ── run() — connection failure ────────────────────────────────────────────

    @Test
    void run_fetchThrows_printErrorAndReturnFalse() {
        ReportCommand cmd = new ReportCommand(url -> { throw new IOException("refused"); });
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean result = cmd.run("127.0.0.1", 9999, "terminal", config(), new PrintStream(buf));
        assertFalse(result);
        assertTrue(buf.toString().contains("ERROR"));
    }

    // ── run() — empty / malformed JSON ───────────────────────────────────────

    @Test
    void run_emptyJson_printsNoRoutes() {
        ReportCommand cmd = new ReportCommand(url -> "");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        cmd.run("127.0.0.1", 7777, "terminal", config(), new PrintStream(buf));
        // Should not crash; terminal reporter still prints header
        assertTrue(buf.toString().contains("CloudMeter"));
    }

    @Test
    void run_malformedJson_doesNotThrow() {
        ReportCommand cmd = new ReportCommand(url -> "not json at all {{{{");
        assertDoesNotThrow(() ->
                cmd.run("127.0.0.1", 7777, "terminal", config(),
                        new PrintStream(new ByteArrayOutputStream())));
    }

    // ── parseProjections() ────────────────────────────────────────────────────

    @Test
    void parseProjections_null_returnsEmpty() {
        assertTrue(ReportCommand.parseProjections(null, config()).isEmpty());
    }

    @Test
    void parseProjections_empty_returnsEmpty() {
        assertTrue(ReportCommand.parseProjections("", config()).isEmpty());
    }

    @Test
    void parseProjections_validJson_returnsProjection() {
        List<EndpointCostProjection> list = ReportCommand.parseProjections(SAMPLE_JSON, config());
        assertEquals(1, list.size());
        assertEquals("GET /api/users", list.get(0).getRouteTemplate());
    }

    // ── defaultFetch() ────────────────────────────────────────────────────────

    @Test
    void defaultFetch_unreachableHost_throwsIOException() {
        assertThrows(IOException.class,
                () -> ReportCommand.defaultFetch("http://127.0.0.1:19999/api/projections"));
    }

    @Test
    void defaultFetch_200response_returnsBody() throws IOException {
        io.cloudmeter.collector.MetricsStore store = new io.cloudmeter.collector.MetricsStore();
        store.startRecording();
        io.cloudmeter.reporter.DashboardServer ds =
                new io.cloudmeter.reporter.DashboardServer(store, config(), 0);
        ds.start();
        try {
            String body = ReportCommand.defaultFetch(
                    "http://127.0.0.1:" + ds.getPort() + "/api/projections");
            assertTrue(body.contains("projections"));
        } finally {
            ds.stop();
        }
    }

    @Test
    void defaultFetch_non200response_throwsIOException() throws IOException {
        // Use a minimal HttpServer that always returns 503
        com.sun.net.httpserver.HttpServer stubServer =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, 0);
            exchange.getResponseBody().close();
        });
        stubServer.setExecutor(null);
        stubServer.start();
        int port = stubServer.getAddress().getPort();
        try {
            assertThrows(IOException.class,
                    () -> ReportCommand.defaultFetch("http://127.0.0.1:" + port + "/api/projections"));
        } finally {
            stubServer.stop(0);
        }
    }
}
