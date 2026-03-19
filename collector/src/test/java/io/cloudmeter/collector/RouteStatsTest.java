package io.cloudmeter.collector;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteStatsTest {

    private static RequestMetrics dummyMetric() {
        return RequestMetrics.builder()
                .routeTemplate("GET /a").actualPath("/a")
                .httpStatusCode(200).httpMethod("GET")
                .durationMs(10).cpuCoreSeconds(0.01)
                .peakMemoryBytes(0).egressBytes(0).threadWaitRatio(0)
                .build();
    }

    private RouteStats stats(double p50, double p95) {
        return new RouteStats("GET /a", 10, p50, p95, p95 + 0.01, p95 + 0.02,
                Collections.emptyList());
    }

    @Test
    void varianceRatio_computedFromP95DividedByP50() {
        RouteStats s = stats(2.0, 6.0);
        assertEquals(3.0, s.getVarianceRatio(), 1e-9);
    }

    @Test
    void varianceRatio_nanWhenP50IsZero() {
        RouteStats s = stats(0.0, 1.0);
        assertTrue(Double.isNaN(s.getVarianceRatio()));
    }

    @Test
    void hasVarianceWarning_trueAboveThreshold() {
        // threshold = 1.5 — ratio of 2.0 should warn
        assertTrue(stats(1.0, 2.0).hasVarianceWarning());
    }

    @Test
    void hasVarianceWarning_falseAtOrBelowThreshold() {
        assertFalse(stats(1.0, 1.4).hasVarianceWarning());
        assertFalse(stats(1.0, 1.0).hasVarianceWarning());
    }

    @Test
    void hasVarianceWarning_falseWhenNaN() {
        assertFalse(stats(0.0, 1.0).hasVarianceWarning());
    }

    @Test
    void getters_returnCorrectValues() {
        List<RequestMetrics> outliers = Collections.singletonList(dummyMetric());
        RouteStats s = new RouteStats("GET /test", 5, 1.0, 2.0, 3.0, 4.0, outliers);
        assertEquals("GET /test", s.getRouteTemplate());
        assertEquals(5,           s.getRequestCount());
        assertEquals(1.0,         s.getP50CostUsd(),  1e-9);
        assertEquals(2.0,         s.getP95CostUsd(),  1e-9);
        assertEquals(3.0,         s.getP99CostUsd(),  1e-9);
        assertEquals(4.0,         s.getMaxCostUsd(),  1e-9);
        assertEquals(1,           s.getOutliers().size());
    }

    @Test
    void outliers_unmodifiable() {
        RouteStats s = new RouteStats("GET /a", 1, 1.0, 1.0, 1.0, 1.0,
                Arrays.asList(dummyMetric()));
        assertThrows(UnsupportedOperationException.class,
                () -> s.getOutliers().add(dummyMetric()));
    }

    @Test
    void constructor_requiresNonNullRoute() {
        assertThrows(NullPointerException.class,
                () -> new RouteStats(null, 1, 1.0, 1.0, 1.0, 1.0,
                        Collections.emptyList()));
    }

    @Test
    void constructor_requiresNonNullOutliers() {
        assertThrows(NullPointerException.class,
                () -> new RouteStats("GET /a", 1, 1.0, 1.0, 1.0, 1.0, null));
    }

    @Test
    void toString_containsRoute() {
        assertTrue(stats(1.0, 2.0).toString().contains("GET /a"));
    }

    @Test
    void equals_symmetric() {
        RouteStats a = new RouteStats("GET /a", 2, 1.0, 2.0, 3.0, 4.0,
                Collections.emptyList());
        RouteStats b = new RouteStats("GET /a", 2, 1.0, 2.0, 3.0, 4.0,
                Collections.emptyList());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentRoute_notEqual() {
        RouteStats a = new RouteStats("GET /a", 2, 1.0, 2.0, 3.0, 4.0, Collections.emptyList());
        RouteStats b = new RouteStats("GET /b", 2, 1.0, 2.0, 3.0, 4.0, Collections.emptyList());
        assertNotEquals(a, b);
    }

    @Test
    void equals_null_notEqual() {
        assertNotEquals(stats(1.0, 2.0), null);
    }

    @Test
    void equals_differentType_notEqual() {
        assertNotEquals(stats(1.0, 2.0), "string");
    }
}
