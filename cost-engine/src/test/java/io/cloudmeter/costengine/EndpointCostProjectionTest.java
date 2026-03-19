package io.cloudmeter.costengine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndpointCostProjectionTest {

    private static InstanceType inst() {
        return new InstanceType("t3.medium", CloudProvider.AWS, 2, 4, 0.0416);
    }

    private static List<ScalePoint> curve() {
        return Arrays.asList(new ScalePoint(100, 1.0), new ScalePoint(1_000, 10.0));
    }

    @Test
    void constructor_setsAllFields() {
        EndpointCostProjection p = new EndpointCostProjection(
                "GET /api/users/{id}", 5.0, 500.0, 120.0, 0.012, inst(), curve(), false, 42.0, 0.005);

        assertEquals("GET /api/users/{id}", p.getRouteTemplate());
        assertEquals(5.0,   p.getObservedRps(),              1e-9);
        assertEquals(500.0, p.getProjectedRps(),             1e-9);
        assertEquals(120.0, p.getProjectedMonthlyCostUsd(),  1e-9);
        assertEquals(0.012, p.getProjectedCostPerUserUsd(),  1e-9);
        assertSame(inst().getClass(), p.getRecommendedInstance().getClass());
        assertEquals(2,     p.getCostCurve().size());
        assertFalse(p.isExceedsBudget());
        assertEquals(42.0,  p.getMedianDurationMs(),            1e-9);
        assertEquals(0.005, p.getMedianCpuCoreSecondsPerReq(),  1e-9);
    }

    @Test
    void exceedsBudget_true() {
        EndpointCostProjection p = new EndpointCostProjection(
                "POST /api/export", 1.0, 100.0, 600.0, 0.06, inst(), curve(), true, 150.0, 0.05);
        assertTrue(p.isExceedsBudget());
    }

    @Test
    void costCurve_isUnmodifiable() {
        EndpointCostProjection p = new EndpointCostProjection(
                "GET /x", 1, 10, 5.0, 0.0005, inst(), curve(), false, 0.0, 0.0);
        assertThrows(UnsupportedOperationException.class,
                () -> p.getCostCurve().add(new ScalePoint(9_999, 99.0)));
    }

    @Test
    void nullRouteTemplate_throws() {
        assertThrows(NullPointerException.class, () ->
                new EndpointCostProjection(null, 1, 10, 5.0, 0.0005, inst(), curve(), false, 0.0, 0.0));
    }

    @Test
    void nullInstance_throws() {
        assertThrows(NullPointerException.class, () ->
                new EndpointCostProjection("GET /x", 1, 10, 5.0, 0.0005, null, curve(), false, 0.0, 0.0));
    }

    @Test
    void nullCostCurve_throws() {
        assertThrows(NullPointerException.class, () ->
                new EndpointCostProjection("GET /x", 1, 10, 5.0, 0.0005, inst(), null, false, 0.0, 0.0));
    }

    @Test
    void toString_containsKeyInfo() {
        EndpointCostProjection p = new EndpointCostProjection(
                "DELETE /api/items/{id}", 2.5, 250.0, 99.99, 0.01, inst(), curve(), true, 80.0, 0.02);
        String s = p.toString();
        assertTrue(s.contains("DELETE /api/items/{id}"));
        assertTrue(s.contains("99.99"));
        assertTrue(s.contains("true"));
    }
}
