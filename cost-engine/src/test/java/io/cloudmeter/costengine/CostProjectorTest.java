package io.cloudmeter.costengine;

import io.cloudmeter.collector.RequestMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CostProjectorTest {

    // ── project() — top-level ─────────────────────────────────────────────────

    @Test
    void emptyMetrics_returnsEmptyList() {
        List<EndpointCostProjection> results = CostProjector.project(
                Collections.emptyList(), awsConfig(10_000, 0.5, 120.0));
        assertTrue(results.isEmpty());
    }

    @Test
    void allWarmup_returnsEmptyList() {
        List<RequestMetrics> metrics = Arrays.asList(
                metric("GET /api/health", 0.001, 100, true),
                metric("GET /api/health", 0.001, 100, true));
        assertTrue(CostProjector.project(metrics, awsConfig(10_000, 0.5, 120.0)).isEmpty());
    }

    @Test
    void singleRoute_returnsOneProjection() {
        List<RequestMetrics> metrics = metrics10x("GET /api/users/{id}", 0.01, 1024);
        List<EndpointCostProjection> results = CostProjector.project(
                metrics, awsConfig(10_000, 0.5, 120.0));
        assertEquals(1, results.size());
    }

    @Test
    void multipleRoutes_sortedByMonthlyCostDescending() {
        List<RequestMetrics> metrics = new ArrayList<>();
        // cheap route: 1ms CPU
        metrics.addAll(metrics10x("GET /api/ping", 0.001, 0));
        // expensive route: 100ms CPU
        metrics.addAll(metrics10x("POST /api/export/pdf", 0.100, 0));

        List<EndpointCostProjection> results = CostProjector.project(
                metrics, awsConfig(10_000, 0.5, 120.0));

        assertEquals(2, results.size());
        assertTrue(results.get(0).getProjectedMonthlyCostUsd()
                >= results.get(1).getProjectedMonthlyCostUsd(),
                "results must be sorted by cost descending");
        assertEquals("POST /api/export/pdf", results.get(0).getRouteTemplate());
    }

    @Test
    void warmupEntriesExcluded_fromProjection() {
        // Mix warmup + live metrics on same route
        List<RequestMetrics> metrics = new ArrayList<>();
        metrics.add(metric("GET /health", 0.001, 0, true));   // warmup — excluded
        metrics.add(metric("GET /health", 0.001, 0, false));  // live — included
        metrics.add(metric("GET /health", 0.001, 0, false));

        List<EndpointCostProjection> results = CostProjector.project(
                metrics, awsConfig(1_000, 0.5, 60.0));

        // Only 2 live metrics — verify sample count drives the RPS
        assertEquals(1, results.size());
        assertEquals(2.0 / 60.0, results.get(0).getObservedRps(), 1e-6);
    }

    @Test
    void projectedRps_scaledFromObserved() {
        // 100 requests in 100s → 1 RPS observed
        // target = 10,000 users × 1 req/sec/user = 10,000 total RPS
        // scale factor = 10,000 / 1 = 10,000
        // projected endpoint RPS = 1 × 10,000 = 10,000
        List<RequestMetrics> metrics = metricsN("GET /api/data", 0.001, 0, 100);
        List<EndpointCostProjection> results = CostProjector.project(
                metrics, awsConfig(10_000, 1.0, 100.0));

        assertEquals(10_000.0, results.get(0).getProjectedRps(), 1.0);
    }

    @Test
    void costCurve_has12Points() {
        List<RequestMetrics> metrics = metrics10x("GET /api/check", 0.005, 512);
        List<EndpointCostProjection> results = CostProjector.project(
                metrics, awsConfig(10_000, 0.5, 60.0));

        assertEquals(CostProjector.SCALE_USERS.length, results.get(0).getCostCurve().size());
    }

    @Test
    void costCurve_scaleUsersMatchConstants() {
        List<RequestMetrics> metrics = metrics10x("GET /api/x", 0.005, 0);
        List<ScalePoint> curve = CostProjector.project(
                metrics, awsConfig(10_000, 0.5, 60.0)).get(0).getCostCurve();

        for (int i = 0; i < CostProjector.SCALE_USERS.length; i++) {
            assertEquals(CostProjector.SCALE_USERS[i], curve.get(i).getConcurrentUsers());
        }
    }

    @Test
    void costCurve_isNonDecreasing() {
        List<RequestMetrics> metrics = metrics10x("GET /api/items", 0.01, 1024);
        List<ScalePoint> curve = CostProjector.project(
                metrics, awsConfig(10_000, 0.5, 60.0)).get(0).getCostCurve();

        for (int i = 1; i < curve.size(); i++) {
            assertTrue(curve.get(i).getMonthlyCostUsd() >= curve.get(i - 1).getMonthlyCostUsd(),
                    "cost curve must be non-decreasing at index " + i);
        }
    }

    @Test
    void budgetExceeded_flaggedWhenCostExceedsBudget() {
        // Use very high CPU time to force a large projected cost
        List<RequestMetrics> metrics = metricsN("POST /api/expensive", 10.0, 0, 1000);
        ProjectionConfig cfg = ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(100_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).budgetUsd(1.0) // tiny budget
                .build();
        List<EndpointCostProjection> results = CostProjector.project(metrics, cfg);
        assertTrue(results.get(0).isExceedsBudget());
    }

    @Test
    void budgetNotExceeded_whenCostBelowBudget() {
        List<RequestMetrics> metrics = metrics10x("GET /api/health", 0.0001, 0);
        ProjectionConfig cfg = ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(100).requestsPerUserPerSecond(0.1)
                .recordingDurationSeconds(60.0).budgetUsd(1_000_000.0) // huge budget
                .build();
        assertFalse(CostProjector.project(metrics, cfg).get(0).isExceedsBudget());
    }

    @Test
    void zeroBudget_neverFlagsExceeded() {
        List<RequestMetrics> metrics = metricsN("POST /big", 100.0, 0, 100);
        ProjectionConfig cfg = ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(100_000).requestsPerUserPerSecond(10.0)
                .recordingDurationSeconds(60.0).budgetUsd(0) // 0 = disabled
                .build();
        assertFalse(CostProjector.project(metrics, cfg).get(0).isExceedsBudget());
    }

    @Test
    void gcpProvider_producesValidProjection() {
        List<RequestMetrics> metrics = metrics10x("GET /api/items", 0.005, 1024);
        ProjectionConfig cfg = ProjectionConfig.builder()
                .provider(CloudProvider.GCP).region("us-central1")
                .targetUsers(1_000).requestsPerUserPerSecond(0.5)
                .recordingDurationSeconds(60.0).build();
        List<EndpointCostProjection> results = CostProjector.project(metrics, cfg);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getProjectedMonthlyCostUsd() > 0);
    }

    @Test
    void azureProvider_producesValidProjection() {
        List<RequestMetrics> metrics = metrics10x("GET /api/orders", 0.008, 512);
        ProjectionConfig cfg = ProjectionConfig.builder()
                .provider(CloudProvider.AZURE).region("eastus")
                .targetUsers(1_000).requestsPerUserPerSecond(0.5)
                .recordingDurationSeconds(60.0).build();
        List<EndpointCostProjection> results = CostProjector.project(metrics, cfg);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getProjectedMonthlyCostUsd() > 0);
    }

    @Test
    void egressCost_addsToProjection() {
        // Two identical requests except one has egress bytes
        List<RequestMetrics> noEgress = metrics10x("GET /api/tiny", 0.001, 0);
        List<RequestMetrics> withEgress = metrics10x("GET /api/heavy", 0.001, 1_000_000_000); // 1 GB

        ProjectionConfig cfg = awsConfig(1_000, 1.0, 60.0);
        double costNoEgress   = CostProjector.project(noEgress,   cfg).get(0).getProjectedMonthlyCostUsd();
        double costWithEgress = CostProjector.project(withEgress, cfg).get(0).getProjectedMonthlyCostUsd();

        assertTrue(costWithEgress > costNoEgress, "egress should increase projected cost");
    }

    @Test
    void costPerUser_isMonthlyCostDividedByTargetUsers() {
        List<RequestMetrics> metrics = metrics10x("GET /api/v1/status", 0.005, 0);
        ProjectionConfig cfg = awsConfig(5_000, 0.5, 60.0);
        EndpointCostProjection p = CostProjector.project(metrics, cfg).get(0);
        assertEquals(p.getProjectedMonthlyCostUsd() / 5_000,
                p.getProjectedCostPerUserUsd(), 1e-6);
    }

    // ── selectInstance ─────────────────────────────────────────────────────────

    @Test
    void selectInstance_returnsSmallestSufficientInstance() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.AWS, "us-east-1");
        // Need just 1 core — should get the cheapest instance
        InstanceType selected = CostProjector.selectInstance(1.0, instances);
        assertNotNull(selected);
        assertTrue(selected.getVcpu() >= 1.0);
        // t3.nano has 2 vCPU and is cheapest — but t3.micro is also 2 vCPU and more expensive
        // Selected instance should have the lowest hourly rate that meets requirement
        for (InstanceType inst : instances) {
            if (inst.getVcpu() >= 1.0 && inst.getHourlyUsd() < selected.getHourlyUsd()) {
                fail("Found cheaper qualifying instance: " + inst);
            }
        }
    }

    @Test
    void selectInstance_requirementExceedsCatalog_returnsLargest() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.AWS, "us-east-1");
        InstanceType selected = CostProjector.selectInstance(99999.0, instances);
        // Should return the last (largest) instance
        assertSame(instances.get(instances.size() - 1), selected);
    }

    @Test
    void selectInstance_zeroCores_returnsCheapest() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.AWS, "us-east-1");
        InstanceType selected = CostProjector.selectInstance(0.0, instances);
        // 0 cores required → any instance qualifies → cheapest
        assertEquals(instances.get(0), selected);
    }

    // ── median ─────────────────────────────────────────────────────────────────

    @Test
    void median_emptyList_returnsZero() {
        assertEquals(0.0, CostProjector.median(new ArrayList<>()), 1e-9);
    }

    @Test
    void median_singleElement() {
        assertEquals(7.0, CostProjector.median(listOf(7.0)), 1e-9);
    }

    @Test
    void median_oddCount() {
        assertEquals(3.0, CostProjector.median(listOf(1.0, 3.0, 5.0)), 1e-9);
    }

    @Test
    void median_evenCount_averagesMiddleTwo() {
        assertEquals(2.5, CostProjector.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9);
    }

    @Test
    void median_unsortedInput_sortedBeforeComputation() {
        assertEquals(3.0, CostProjector.median(listOf(5.0, 1.0, 3.0)), 1e-9);
    }

    // ── computeMonthlyCost ─────────────────────────────────────────────────────

    @Test
    void computeMonthlyCost_onlyCompute_noEgress() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.AWS, "us-east-1");
        double cost = CostProjector.computeMonthlyCost(
                1.0,    // 1 RPS
                0.001,  // 1ms CPU per request → 0.001 cores needed
                0,      // no egress
                instances, PricingCatalog.AWS_EGRESS_RATE_PER_GIB);
        // Cheapest AWS instance (t3.nano, $0.0052/hr) × 730h = $3.796
        double expected = instances.get(0).getHourlyUsd() * PricingCatalog.HOURS_PER_MONTH;
        assertEquals(expected, cost, 1e-6);
    }

    @Test
    void computeMonthlyCost_withEgress_addsEgressComponent() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.AWS, "us-east-1");
        double egressRate = PricingCatalog.AWS_EGRESS_RATE_PER_GIB;
        double costNoEgress = CostProjector.computeMonthlyCost(1.0, 0.001, 0,         instances, egressRate);
        double costWithEgress = CostProjector.computeMonthlyCost(1.0, 0.001, 1e9,     instances, egressRate);
        assertTrue(costWithEgress > costNoEgress);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static ProjectionConfig awsConfig(int targetUsers, double rpu, double durationSec) {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS)
                .region("us-east-1")
                .targetUsers(targetUsers)
                .requestsPerUserPerSecond(rpu)
                .recordingDurationSeconds(durationSec)
                .build();
    }

    private static RequestMetrics metric(String route, double cpuCoreSeconds,
                                          long egressBytes, boolean warmup) {
        return RequestMetrics.builder()
                .routeTemplate(route)
                .actualPath(route)
                .httpMethod("GET")
                .httpStatusCode(200)
                .durationMs(50)
                .cpuCoreSeconds(cpuCoreSeconds)
                .peakMemoryBytes(1024 * 1024L)
                .egressBytes(egressBytes)
                .threadWaitRatio(0.1)
                .timestamp(Instant.now())
                .warmup(warmup)
                .build();
    }

    private static List<RequestMetrics> metrics10x(String route, double cpu, long egress) {
        return metricsN(route, cpu, egress, 10);
    }

    private static List<RequestMetrics> metricsN(String route, double cpu, long egress, int n) {
        List<RequestMetrics> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(metric(route, cpu, egress, false));
        }
        return list;
    }

    private static List<Double> listOf(double... values) {
        List<Double> list = new ArrayList<>();
        for (double v : values) list.add(v);
        return list;
    }
}
