package io.cloudmeter.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteStatsCalculatorTest {

    private RouteStatsCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RouteStatsCalculator();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestMetrics metric(String route, double cpuCoreSeconds,
                                         long egressBytes, boolean warmup) {
        return RequestMetrics.builder()
                .routeTemplate(route)
                .actualPath(route + "/1")
                .httpStatusCode(200)
                .httpMethod("GET")
                .durationMs(100)
                .cpuCoreSeconds(cpuCoreSeconds)
                .peakMemoryBytes(1024)
                .egressBytes(egressBytes)
                .threadWaitRatio(0.1)
                .timestamp(Instant.now())
                .warmup(warmup)
                .build();
    }

    private static RequestMetrics metric(String route, double cpuCoreSeconds) {
        return metric(route, cpuCoreSeconds, 0L, false);
    }

    // ── Constructor validation ────────────────────────────────────────────────

    @Test
    void invalidOutlierCount_throws() {
        assertThrows(IllegalArgumentException.class, () -> new RouteStatsCalculator(0));
        assertThrows(IllegalArgumentException.class, () -> new RouteStatsCalculator(-1));
    }

    // ── Empty / warmup filtering ──────────────────────────────────────────────

    @Test
    void emptyList_returnsEmpty() {
        assertTrue(calculator.calculate(Collections.emptyList()).isEmpty());
    }

    @Test
    void warmupEntries_excluded() {
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /health", 0.01, 0L, true),
                metric("GET /health", 0.01, 0L, true));
        assertTrue(calculator.calculate(metrics).isEmpty());
    }

    @Test
    void mixedWarmupAndReal_onlyRealIncluded() {
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /api", 0.02, 0L, true),
                metric("GET /api", 0.04, 0L, false));
        List<RouteStats> stats = calculator.calculate(metrics);
        assertEquals(1, stats.size());
        assertEquals(1, stats.get(0).getRequestCount());
    }

    // ── Route grouping ────────────────────────────────────────────────────────

    @Test
    void multipleRoutes_produceSeparateStats() {
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /users", 0.01),
                metric("POST /orders", 0.05),
                metric("GET /users", 0.02));
        List<RouteStats> stats = calculator.calculate(metrics);
        assertEquals(2, stats.size());
        List<String> routes = Arrays.asList(stats.get(0).getRouteTemplate(),
                                            stats.get(1).getRouteTemplate());
        assertTrue(routes.contains("GET /users"));
        assertTrue(routes.contains("POST /orders"));
    }

    @Test
    void singleRoute_requestCountIsCorrect() {
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /a", 0.01),
                metric("GET /a", 0.02),
                metric("GET /a", 0.03));
        RouteStats stats = calculator.calculate(metrics).get(0);
        assertEquals(3, stats.getRequestCount());
    }

    // ── Percentile calculations ───────────────────────────────────────────────

    @Test
    void singleEntry_allPercentilesEqual() {
        List<RequestMetrics> metrics = Collections.singletonList(metric("GET /a", 1.0));
        RouteStats stats = calculator.calculate(metrics).get(0);
        double expected = RouteStatsCalculator.costForRequest(metrics.get(0));
        assertEquals(expected, stats.getP50CostUsd(), 1e-12);
        assertEquals(expected, stats.getP95CostUsd(), 1e-12);
        assertEquals(expected, stats.getP99CostUsd(), 1e-12);
        assertEquals(expected, stats.getMaxCostUsd(), 1e-12);
    }

    @Test
    void percentile_p50IsMedian() {
        // sorted costs: 1,2,3,4,5 → p50 = 3rd value
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /a", 1.0),
                metric("GET /a", 3.0),
                metric("GET /a", 5.0),
                metric("GET /a", 2.0),
                metric("GET /a", 4.0));
        RouteStats stats = calculator.calculate(metrics).get(0);
        double p50 = stats.getP50CostUsd();
        // p50 should correspond to cpu=3.0
        double expected = 3.0 * RouteStatsCalculator.CPU_COST_PER_CORE_SECOND_USD;
        assertEquals(expected, p50, 1e-12);
    }

    @Test
    void percentile_maxIsLargest() {
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /a", 1.0),
                metric("GET /a", 10.0),
                metric("GET /a", 5.0));
        RouteStats stats = calculator.calculate(metrics).get(0);
        double expected = 10.0 * RouteStatsCalculator.CPU_COST_PER_CORE_SECOND_USD;
        assertEquals(expected, stats.getMaxCostUsd(), 1e-12);
    }

    // ── Variance ratio ────────────────────────────────────────────────────────

    @Test
    void varianceRatio_noWarning_whenConsistent() {
        // All same cost → ratio = 1.0, below threshold
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /a", 1.0),
                metric("GET /a", 1.0),
                metric("GET /a", 1.0));
        assertFalse(calculator.calculate(metrics).get(0).hasVarianceWarning());
    }

    @Test
    void varianceRatio_warning_whenHighVariance() {
        // 9 cheap + 1 very expensive → p95 will be expensive
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /a", 0.01),
                metric("GET /a", 0.01),
                metric("GET /a", 0.01),
                metric("GET /a", 0.01),
                metric("GET /a", 0.01),
                metric("GET /a", 0.01),
                metric("GET /a", 0.01),
                metric("GET /a", 0.01),
                metric("GET /a", 0.01),
                metric("GET /a", 100.0));  // extreme outlier
        RouteStats stats = calculator.calculate(metrics).get(0);
        assertTrue(stats.hasVarianceWarning());
        assertTrue(stats.getVarianceRatio() > RouteStats.VARIANCE_WARNING_THRESHOLD);
    }

    @Test
    void varianceRatio_nanWhenP50IsZero() {
        List<RequestMetrics> metrics = Collections.singletonList(
                metric("GET /a", 0.0, 0L, false));
        RouteStats stats = calculator.calculate(metrics).get(0);
        assertTrue(Double.isNaN(stats.getVarianceRatio()));
        assertFalse(stats.hasVarianceWarning());
    }

    // ── Outliers ──────────────────────────────────────────────────────────────

    @Test
    void outliers_areTopNCostliest() {
        RouteStatsCalculator calc = new RouteStatsCalculator(2);
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /a", 1.0),
                metric("GET /a", 5.0),
                metric("GET /a", 3.0),
                metric("GET /a", 2.0),
                metric("GET /a", 4.0));
        List<RequestMetrics> outliers = calc.calculate(metrics).get(0).getOutliers();
        assertEquals(2, outliers.size());
        assertEquals(5.0, outliers.get(0).getCpuCoreSeconds(), 1e-9);
        assertEquals(4.0, outliers.get(1).getCpuCoreSeconds(), 1e-9);
    }

    @Test
    void outliers_fewerEntriesThanN_returnsAll() {
        RouteStatsCalculator calc = new RouteStatsCalculator(10);
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /a", 1.0),
                metric("GET /a", 2.0));
        assertEquals(2, calc.calculate(metrics).get(0).getOutliers().size());
    }

    @Test
    void outliers_unmodifiable() {
        List<RequestMetrics> metrics = Collections.singletonList(metric("GET /a", 1.0));
        List<RequestMetrics> outliers = calculator.calculate(metrics).get(0).getOutliers();
        assertThrows(UnsupportedOperationException.class, () -> outliers.add(metric("GET /x", 1.0)));
    }

    // ── Sorting ───────────────────────────────────────────────────────────────

    @Test
    void results_sortedByP50Descending() {
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /cheap",     0.001),
                metric("GET /expensive", 100.0),
                metric("GET /medium",    1.0));
        List<RouteStats> stats = calculator.calculate(metrics);
        assertEquals("GET /expensive", stats.get(0).getRouteTemplate());
        assertEquals("GET /medium",    stats.get(1).getRouteTemplate());
        assertEquals("GET /cheap",     stats.get(2).getRouteTemplate());
    }

    // ── Cost formula ──────────────────────────────────────────────────────────

    @Test
    void costForRequest_includesCpuAndEgress() {
        RequestMetrics m = metric("GET /a", 2.0, 1_073_741_824L, false); // 1 GB egress
        double expected = 2.0 * RouteStatsCalculator.CPU_COST_PER_CORE_SECOND_USD
                        + 1_073_741_824L * RouteStatsCalculator.EGRESS_COST_PER_BYTE_USD;
        assertEquals(expected, RouteStatsCalculator.costForRequest(m), 1e-12);
    }

    // ── Static percentile helper ──────────────────────────────────────────────

    @Test
    void percentile_emptyList_returnsZero() {
        assertEquals(0.0, RouteStatsCalculator.percentile(Collections.emptyList(), 50), 1e-9);
    }

    @Test
    void percentile_singleElement() {
        assertEquals(42.0,
                RouteStatsCalculator.percentile(Collections.singletonList(42.0), 99), 1e-9);
    }
}
