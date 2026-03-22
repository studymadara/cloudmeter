package io.cloudmeter.reporter;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.InstanceType;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.costengine.ScalePoint;
import io.cloudmeter.costengine.WarmupCostSummary;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarmupReporterTest {

    private static ProjectionConfig config() {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).budgetUsd(0.0).build();
    }

    private static EndpointCostProjection proj(String route) {
        InstanceType inst = new InstanceType("t3.micro", CloudProvider.AWS, 2, 1, 0.0104);
        List<ScalePoint> curve = Collections.singletonList(new ScalePoint(1_000, 10.0));
        return new EndpointCostProjection(route, 1.0, 10.0, 50.0,
                0.05, inst, curve, false, 20.0, 0.01, 0.0, 0.0);
    }

    // ── TerminalReporter.printWarmup ──────────────────────────────────────────

    @Test
    void printWarmup_noData_printsNothing() {
        WarmupCostSummary empty = new WarmupCostSummary(0, 0.0, 0L, 0L, 0.0);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.printWarmup(empty, new PrintStream(buf));
        assertEquals("", buf.toString());
    }

    @Test
    void printWarmup_withData_showsRequestCount() {
        WarmupCostSummary summary = new WarmupCostSummary(42, 1.5, 15_000L, 512L, 0.00042);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.printWarmup(summary, new PrintStream(buf));
        String out = buf.toString();
        assertTrue(out.contains("42"), "Should show request count");
        assertTrue(out.contains("Cold-start"), "Should mention cold-start");
        assertTrue(out.contains("0.000420"), "Should show cost");
    }

    @Test
    void printWarmup_withData_showsWarmupWindow() {
        WarmupCostSummary summary = new WarmupCostSummary(10, 0.5, 5_000L, 256L, 0.0001);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.printWarmup(summary, new PrintStream(buf));
        assertTrue(buf.toString().contains(String.valueOf(WarmupCostSummary.WARMUP_SECONDS)));
    }

    // ── JsonReporter.print(warmup) ────────────────────────────────────────────

    @Test
    void jsonPrint_withWarmup_containsWarmupSummaryField() {
        WarmupCostSummary warmup = new WarmupCostSummary(15, 0.3, 3_000L, 1024L, 0.00015);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonReporter.print(Collections.singletonList(proj("GET /api/x")), warmup, config(),
                new PrintStream(buf));
        String json = buf.toString();
        assertTrue(json.contains("\"warmupSummary\""));
        assertTrue(json.contains("\"requestCount\": 15"));
        assertTrue(json.contains("\"hasData\": true"));
        assertTrue(json.contains("\"estimatedColdStartCostUsd\""));
    }

    @Test
    void jsonPrint_emptyWarmup_hasDataFalse() {
        WarmupCostSummary empty = new WarmupCostSummary(0, 0.0, 0L, 0L, 0.0);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonReporter.print(Collections.emptyList(), empty, config(), new PrintStream(buf));
        assertTrue(buf.toString().contains("\"hasData\": false"));
    }

    @Test
    void jsonPrint_warmupNote_present() {
        WarmupCostSummary warmup = new WarmupCostSummary(5, 0.1, 500L, 128L, 0.0001);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonReporter.print(Collections.emptyList(), warmup, config(), new PrintStream(buf));
        assertTrue(buf.toString().contains("restarts/month"));
    }

    @Test
    void jsonPrint_withWarmup_stillHasProjectionsAndSummary() {
        WarmupCostSummary warmup = new WarmupCostSummary(3, 0.05, 1_000L, 256L, 0.0);
        List<EndpointCostProjection> projs = Arrays.asList(
                proj("GET /a"), proj("GET /b"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonReporter.print(projs, warmup, config(), new PrintStream(buf));
        String json = buf.toString();
        assertTrue(json.contains("\"projections\""));
        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"warmupSummary\""));
    }
}
