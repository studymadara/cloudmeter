package io.cloudmeter.starter;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestMetrics;
import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.ProjectionConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudMeterEndpointTest {

    private static MetricsStore storeWithMetrics() {
        MetricsStore store = new MetricsStore(1000);
        store.startRecording();
        RequestMetrics m = RequestMetrics.builder()
                .routeTemplate("GET /api/test").actualPath("/api/test")
                .httpMethod("GET").httpStatusCode(200)
                .durationMs(50).cpuCoreSeconds(0.05)
                .peakMemoryBytes(1024 * 1024L).egressBytes(100)
                .threadWaitRatio(0.0).timestamp(Instant.now())
                .warmup(false).build();
        for (int i = 0; i < 10; i++) store.add(m);
        return store;
    }

    private static ProjectionConfig config() {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).budgetUsd(0).build();
    }

    @Test
    void projections_returnsMapWithProjectionsKey() {
        CloudMeterEndpoint ep = new CloudMeterEndpoint(storeWithMetrics(), config());
        Map<String, Object> result = ep.projections();
        assertTrue(result.containsKey("projections"));
        assertTrue(result.containsKey("summary"));
    }

    @Test
    void projections_emptyStore_returnsEmptyList() {
        MetricsStore empty = new MetricsStore(100);
        empty.startRecording();
        CloudMeterEndpoint ep = new CloudMeterEndpoint(empty, config());
        Map<String, Object> result = ep.projections();
        assertTrue(((java.util.List<?>) result.get("projections")).isEmpty());
    }

    @Test
    void projections_withMetrics_containsRouteData() {
        CloudMeterEndpoint ep = new CloudMeterEndpoint(storeWithMetrics(), config());
        Map<String, Object> result = ep.projections();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> projs =
                (java.util.List<Map<String, Object>>) result.get("projections");
        assertFalse(projs.isEmpty());
        Map<String, Object> first = projs.get(0);
        assertTrue(first.containsKey("route"));
        assertTrue(first.containsKey("projectedMonthlyCostUsd"));
        assertTrue(first.containsKey("recommendedInstance"));
    }

    @Test
    void projections_summary_containsTotalCost() {
        CloudMeterEndpoint ep = new CloudMeterEndpoint(storeWithMetrics(), config());
        Map<String, Object> result = ep.projections();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertTrue(summary.containsKey("totalProjectedMonthlyCostUsd"));
        assertTrue(summary.containsKey("anyExceedsBudget"));
    }
}
