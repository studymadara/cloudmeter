package io.cloudmeter.reporter;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.InstanceType;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.costengine.ScalePoint;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerminalReporterTest {

    private static ProjectionConfig config(double budget) {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).budgetUsd(budget).build();
    }

    private static EndpointCostProjection proj(String route, double monthly, boolean exceeds) {
        InstanceType inst = new InstanceType("t3.micro", CloudProvider.AWS, 2, 1, 0.0104);
        List<ScalePoint> curve = Arrays.asList(
                new ScalePoint(100, 1.0), new ScalePoint(1_000, 10.0));
        return new EndpointCostProjection(route, 1.0, 100.0, monthly,
                monthly / 1_000, inst, curve, exceeds, 30.0, 0.005, 0.1, 512.0);
    }

    private static String capture(List<EndpointCostProjection> projs, ProjectionConfig cfg) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.print(projs, cfg, new PrintStream(buf));
        return buf.toString();
    }

    // ── print() ───────────────────────────────────────────────────────────────

    @Test
    void print_emptyProjections_printsHeaderAndFooter() {
        String out = capture(Collections.emptyList(), config(0));
        assertTrue(out.contains("CloudMeter Cost Projection Report"));
        assertTrue(out.contains("Total projected monthly cost: $0.00"));
    }

    @Test
    void print_singleRoute_containsRouteAndCost() {
        String out = capture(
                Collections.singletonList(proj("GET /api/users", 42.50, false)),
                config(0));
        assertTrue(out.contains("GET /api/users"));
        assertTrue(out.contains("42.50"));
    }

    @Test
    void print_budgetExceeded_containsBudgetMarker() {
        String out = capture(
                Collections.singletonList(proj("POST /heavy", 999.0, true)),
                config(100.0));
        assertTrue(out.contains(TerminalReporter.BUDGET_MARKER));
        assertTrue(out.contains("exceed the budget"));
    }

    @Test
    void print_noBudgetExceeded_noBudgetWarning() {
        String out = capture(
                Collections.singletonList(proj("GET /cheap", 1.0, false)),
                config(500.0));
        assertFalse(out.contains("exceed the budget"));
    }

    @Test
    void print_zeroBudget_noBudgetLineInHeader() {
        String out = capture(
                Collections.singletonList(proj("GET /x", 1.0, false)),
                config(0));
        assertFalse(out.contains("Budget   :"));
    }

    @Test
    void print_withBudget_showsBudgetInHeader() {
        String out = capture(
                Collections.singletonList(proj("GET /x", 1.0, false)),
                config(200.0));
        assertTrue(out.contains("Budget   : $200.00"));
    }

    @Test
    void print_multipleRoutes_totalIsSumOfCosts() {
        List<EndpointCostProjection> projs = Arrays.asList(
                proj("GET /a", 100.0, false),
                proj("GET /b", 200.0, false));
        String out = capture(projs, config(0));
        assertTrue(out.contains("300.00"));
    }

    @Test
    void print_containsPricingDate() {
        String out = capture(Collections.emptyList(), config(0));
        assertTrue(out.contains(io.cloudmeter.costengine.PricingCatalog.PRICING_DATE));
    }

    @Test
    void print_containsProviderAndRegion() {
        String out = capture(Collections.emptyList(), config(0));
        assertTrue(out.contains("AWS"));
        assertTrue(out.contains("us-east-1"));
    }

    // ── Actionability hints ───────────────────────────────────────────────────

    private static EndpointCostProjection projWith(String route, double cpuSec,
            double waitRatio, double egressBytes, double durationMs) {
        InstanceType inst = new InstanceType("t3.micro", CloudProvider.AWS, 2, 1, 0.0104);
        List<ScalePoint> curve = Arrays.asList(new ScalePoint(100, 1.0));
        return new EndpointCostProjection(route, 1.0, 100.0, 10.0, 0.01,
                inst, curve, false, durationMs, cpuSec, waitRatio, egressBytes);
    }

    @Test
    void buildHint_cpuIntensive_containsCpuSignal() {
        EndpointCostProjection p = projWith("GET /heavy", 0.05, 0.1, 100, 50);
        String hint = TerminalReporter.buildHint(p);
        assertNotNull(hint);
        assertTrue(hint.contains("CPU-intensive"), "hint: " + hint);
    }

    @Test
    void buildHint_highWaitRatio_containsIoBound() {
        EndpointCostProjection p = projWith("GET /db", 0.001, 0.8, 100, 50);
        String hint = TerminalReporter.buildHint(p);
        assertNotNull(hint);
        assertTrue(hint.contains("I/O bound") || hint.contains("thread wait"), "hint: " + hint);
    }

    @Test
    void buildHint_highEgress_containsEgressSignal() {
        EndpointCostProjection p = projWith("GET /export", 0.001, 0.1, 50_000, 50);
        String hint = TerminalReporter.buildHint(p);
        assertNotNull(hint);
        assertTrue(hint.contains("egress"), "hint: " + hint);
    }

    @Test
    void buildHint_slowP50_containsLatencySignal() {
        EndpointCostProjection p = projWith("GET /slow", 0.001, 0.1, 100, 800);
        String hint = TerminalReporter.buildHint(p);
        assertNotNull(hint);
        assertTrue(hint.contains("P50 latency"), "hint: " + hint);
    }

    @Test
    void buildHint_noSignals_returnsNull() {
        EndpointCostProjection p = projWith("GET /cheap", 0.001, 0.1, 100, 10);
        assertNull(TerminalReporter.buildHint(p));
    }

    @Test
    void printHints_withHintableEndpoint_printsHintsSection() {
        EndpointCostProjection p = projWith("GET /heavy", 0.05, 0.1, 100, 50);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.printHints(Arrays.asList(p), new PrintStream(buf));
        String out = buf.toString();
        assertTrue(out.contains("Actionability hints"), "out: " + out);
        assertTrue(out.contains("GET /heavy"), "out: " + out);
    }

    @Test
    void printHints_noHints_printsNothing() {
        EndpointCostProjection p = projWith("GET /cheap", 0.001, 0.1, 100, 10);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalReporter.printHints(Arrays.asList(p), new PrintStream(buf));
        assertEquals("", buf.toString());
    }

    @Test
    void print_withCpuHeavyRoute_containsHintsSection() {
        EndpointCostProjection p = projWith("POST /process", 0.1, 0.1, 100, 200);
        String out = capture(Arrays.asList(p), config(0));
        assertTrue(out.contains("Actionability hints"), "output: " + out);
    }

    // ── String utilities ──────────────────────────────────────────────────────

    @Test
    void padRight_shorterThanWidth_padded() {
        assertEquals("hi   ", TerminalReporter.padRight("hi", 5));
    }

    @Test
    void padRight_longerThanWidth_truncated() {
        assertEquals("hello", TerminalReporter.padRight("hello world", 5));
    }

    @Test
    void padRight_exactWidth_unchanged() {
        assertEquals("hello", TerminalReporter.padRight("hello", 5));
    }

    @Test
    void padLeft_shorterThanWidth_padded() {
        assertEquals("   hi", TerminalReporter.padLeft("hi", 5));
    }

    @Test
    void padLeft_longerThanWidth_truncated() {
        assertEquals("hello", TerminalReporter.padLeft("hello world", 5));
    }

    @Test
    void padLeft_exactWidth_unchanged() {
        assertEquals("hello", TerminalReporter.padLeft("hello", 5));
    }

    @Test
    void truncate_shorterThanMax_unchanged() {
        assertEquals("hi", TerminalReporter.truncate("hi", 10));
    }

    @Test
    void truncate_exactMax_unchanged() {
        assertEquals("hello", TerminalReporter.truncate("hello", 5));
    }

    @Test
    void truncate_longerThanMax_truncatedWithEllipsis() {
        String result = TerminalReporter.truncate("hello world", 6);
        assertEquals(6, result.length());
        assertTrue(result.endsWith("…"));
    }
}
