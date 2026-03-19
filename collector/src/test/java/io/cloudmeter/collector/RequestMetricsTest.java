package io.cloudmeter.collector;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RequestMetricsTest {

    private static RequestMetrics sample() {
        return RequestMetrics.builder()
                .routeTemplate("GET /api/users/{id}")
                .actualPath("/api/users/42")
                .httpStatusCode(200)
                .httpMethod("GET")
                .durationMs(120)
                .cpuCoreSeconds(0.05)
                .peakMemoryBytes(1_048_576)
                .egressBytes(2048)
                .threadWaitRatio(0.3)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .warmup(false)
                .build();
    }

    @Test
    void getters_returnCorrectValues() {
        RequestMetrics m = sample();
        assertEquals("GET /api/users/{id}", m.getRouteTemplate());
        assertEquals("/api/users/42",       m.getActualPath());
        assertEquals(200,                   m.getHttpStatusCode());
        assertEquals("GET",                 m.getHttpMethod());
        assertEquals(120,                   m.getDurationMs());
        assertEquals(0.05,                  m.getCpuCoreSeconds(), 1e-9);
        assertEquals(1_048_576,             m.getPeakMemoryBytes());
        assertEquals(2048,                  m.getEgressBytes());
        assertEquals(0.3,                   m.getThreadWaitRatio(), 1e-9);
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), m.getTimestamp());
        assertFalse(m.isWarmup());
    }

    @Test
    void warmup_flag_isPreserved() {
        RequestMetrics m = RequestMetrics.builder()
                .routeTemplate("GET /health")
                .actualPath("/health")
                .httpStatusCode(200)
                .httpMethod("GET")
                .durationMs(5)
                .cpuCoreSeconds(0.001)
                .peakMemoryBytes(0)
                .egressBytes(0)
                .threadWaitRatio(0.0)
                .warmup(true)
                .build();
        assertTrue(m.isWarmup());
    }

    @Test
    void defaultTimestamp_isNotNull() {
        RequestMetrics m = RequestMetrics.builder()
                .routeTemplate("GET /ping")
                .actualPath("/ping")
                .httpStatusCode(200)
                .httpMethod("GET")
                .durationMs(1)
                .cpuCoreSeconds(0)
                .peakMemoryBytes(0)
                .egressBytes(0)
                .threadWaitRatio(0)
                .build();
        assertNotNull(m.getTimestamp());
    }

    @Test
    void equals_reflexive() {
        RequestMetrics m = sample();
        assertEquals(m, m);
    }

    @Test
    void equals_symmetric() {
        assertEquals(sample(), sample());
    }

    @Test
    void equals_differentRoute_notEqual() {
        RequestMetrics a = sample();
        RequestMetrics b = RequestMetrics.builder()
                .routeTemplate("POST /api/users")
                .actualPath("/api/users/42")
                .httpStatusCode(200)
                .httpMethod("GET")
                .durationMs(120)
                .cpuCoreSeconds(0.05)
                .peakMemoryBytes(1_048_576)
                .egressBytes(2048)
                .threadWaitRatio(0.3)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .warmup(false)
                .build();
        assertNotEquals(a, b);
    }

    @Test
    void equals_null_notEqual() {
        assertNotEquals(sample(), null);
    }

    @Test
    void equals_differentType_notEqual() {
        assertNotEquals(sample(), "not a RequestMetrics");
    }

    @Test
    void hashCode_consistent() {
        RequestMetrics a = sample();
        RequestMetrics b = sample();
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void toString_containsRoute() {
        assertTrue(sample().toString().contains("GET /api/users/{id}"));
    }

    @Test
    void builder_requiresRouteTemplate() {
        assertThrows(NullPointerException.class, () ->
                RequestMetrics.builder()
                        .actualPath("/x").httpStatusCode(200).httpMethod("GET")
                        .durationMs(1).cpuCoreSeconds(0).peakMemoryBytes(0)
                        .egressBytes(0).threadWaitRatio(0).build());
    }

    @Test
    void builder_requiresActualPath() {
        assertThrows(NullPointerException.class, () ->
                RequestMetrics.builder()
                        .routeTemplate("GET /x").httpStatusCode(200).httpMethod("GET")
                        .durationMs(1).cpuCoreSeconds(0).peakMemoryBytes(0)
                        .egressBytes(0).threadWaitRatio(0).build());
    }

    @Test
    void builder_requiresHttpMethod() {
        assertThrows(NullPointerException.class, () ->
                RequestMetrics.builder()
                        .routeTemplate("GET /x").actualPath("/x").httpStatusCode(200)
                        .durationMs(1).cpuCoreSeconds(0).peakMemoryBytes(0)
                        .egressBytes(0).threadWaitRatio(0).build());
    }

    @Test
    void builder_requiresTimestampWhenExplicitlySetToNull() {
        assertThrows(NullPointerException.class, () ->
                RequestMetrics.builder()
                        .routeTemplate("GET /x").actualPath("/x")
                        .httpStatusCode(200).httpMethod("GET")
                        .durationMs(1).cpuCoreSeconds(0).peakMemoryBytes(0)
                        .egressBytes(0).threadWaitRatio(0).timestamp(null).build());
    }
}
