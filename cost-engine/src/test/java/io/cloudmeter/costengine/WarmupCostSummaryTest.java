package io.cloudmeter.costengine;

import io.cloudmeter.collector.RequestMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarmupCostSummaryTest {

    private static ProjectionConfig config() {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).budgetUsd(0.0).build();
    }

    private static RequestMetrics metric(boolean warmup, double cpuCoreSeconds, long egressBytes) {
        return RequestMetrics.builder()
                .routeTemplate("GET /api/test")
                .actualPath("GET /api/test")
                .httpMethod("GET")
                .httpStatusCode(200)
                .durationMs(50L)
                .cpuCoreSeconds(cpuCoreSeconds)
                .peakMemoryBytes(0L)
                .egressBytes(egressBytes)
                .threadWaitRatio(0.0)
                .timestamp(Instant.now())
                .warmup(warmup)
                .build();
    }

    @Test
    void noWarmupMetrics_returnsEmptySummary() {
        List<RequestMetrics> all = Collections.singletonList(metric(false, 0.01, 100));
        WarmupCostSummary summary = CostProjector.computeWarmupSummary(all, config());
        assertFalse(summary.hasData());
        assertEquals(0, summary.getRequestCount());
        assertEquals(0.0, summary.getEstimatedColdStartCostUsd());
    }

    @Test
    void allWarmupMetrics_countIsCorrect() {
        List<RequestMetrics> all = Arrays.asList(
                metric(true, 0.05, 512),
                metric(true, 0.03, 256),
                metric(false, 0.01, 100));
        WarmupCostSummary summary = CostProjector.computeWarmupSummary(all, config());
        assertTrue(summary.hasData());
        assertEquals(2, summary.getRequestCount());
    }

    @Test
    void warmupCpuAndEgressAggregated() {
        List<RequestMetrics> all = Arrays.asList(
                metric(true, 0.1, 1024),
                metric(true, 0.2, 2048));
        WarmupCostSummary summary = CostProjector.computeWarmupSummary(all, config());
        assertEquals(0.3, summary.getTotalCpuCoreSeconds(), 1e-9);
        assertEquals(3072L, summary.getTotalEgressBytes());
    }

    @Test
    void coldStartCostIsPositive() {
        List<RequestMetrics> all = Collections.singletonList(metric(true, 1.0, 102_400));
        WarmupCostSummary summary = CostProjector.computeWarmupSummary(all, config());
        assertTrue(summary.getEstimatedColdStartCostUsd() > 0.0);
    }

    @Test
    void warmupSummaryConstant_is30Seconds() {
        assertEquals(30, WarmupCostSummary.WARMUP_SECONDS);
    }

    @Test
    void getTotalDurationMs_returnsAccumulatedValue() {
        List<RequestMetrics> all = Arrays.asList(
                metric(true, 0.01, 100),
                metric(true, 0.01, 100));
        WarmupCostSummary summary = CostProjector.computeWarmupSummary(all, config());
        // Each metric has durationMs=50; two warmup entries → 100
        assertEquals(100L, summary.getTotalDurationMs());
    }

    @Test
    void hasData_falseWhenEmpty() {
        WarmupCostSummary empty = new WarmupCostSummary(0, 0.0, 0L, 0L, 0.0);
        assertFalse(empty.hasData());
    }

    @Test
    void hasData_trueWhenRequestCountPositive() {
        WarmupCostSummary summary = new WarmupCostSummary(5, 0.1, 500L, 1024L, 0.0001);
        assertTrue(summary.hasData());
    }
}
