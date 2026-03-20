package io.cloudmeter.reporter;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.InstanceType;
import io.cloudmeter.costengine.PricingCatalog;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.costengine.ScalePoint;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonReporterTest {

    private static ProjectionConfig config(double budget) {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).budgetUsd(budget).build();
    }

    private static EndpointCostProjection proj(String route, double monthly, boolean exceeds) {
        InstanceType inst = new InstanceType("t3.micro", CloudProvider.AWS, 2, 1, 0.0104);
        List<ScalePoint> curve = Arrays.asList(
                new ScalePoint(100, 1.0),
                new ScalePoint(1_000, 10.0));
        return new EndpointCostProjection(route, 2.5, 250.0, monthly,
                monthly / 1_000, inst, curve, exceeds, 45.0, 0.01, 0.0, 0.0);
    }

    private static String capture(List<EndpointCostProjection> projs, ProjectionConfig cfg) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonReporter.print(projs, cfg, new PrintStream(buf));
        return buf.toString();
    }

    // ── print() ───────────────────────────────────────────────────────────────

    @Test
    void print_emptyProjections_validJson() {
        String json = capture(Collections.emptyList(), config(0));
        assertTrue(json.contains("\"projections\": ["));
        assertTrue(json.contains("\"totalProjectedMonthlyCostUsd\""));
    }

    @Test
    void print_singleRoute_containsRouteData() {
        String json = capture(
                Collections.singletonList(proj("GET /api/users", 42.5, false)),
                config(0));
        assertTrue(json.contains("\"GET /api/users\""));
        assertTrue(json.contains("\"projectedMonthlyCostUsd\""));
        assertTrue(json.contains("\"recommendedInstance\": \"t3.micro\""));
    }

    @Test
    void print_noExceeds_returnsFalse() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean result = JsonReporter.print(
                Collections.singletonList(proj("GET /cheap", 1.0, false)),
                config(500.0), new PrintStream(buf));
        assertFalse(result);
    }

    @Test
    void print_anyExceeds_returnsTrue() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean result = JsonReporter.print(
                Collections.singletonList(proj("POST /expensive", 999.0, true)),
                config(100.0), new PrintStream(buf));
        assertTrue(result);
    }

    @Test
    void print_multipleRoutes_lastHasNoTrailingComma() {
        List<EndpointCostProjection> projs = Arrays.asList(
                proj("GET /a", 10.0, false),
                proj("GET /b", 20.0, false));
        String json = capture(projs, config(0));
        // crude structural check: no ",\n  ]" pattern
        assertFalse(json.contains(",\n  ]"));
    }

    @Test
    void print_containsMetaBlock() {
        String json = capture(Collections.emptyList(), config(100.0));
        assertTrue(json.contains("\"meta\""));
        assertTrue(json.contains("\"AWS\""));
        assertTrue(json.contains("\"us-east-1\""));
        assertTrue(json.contains(PricingCatalog.PRICING_DATE));
    }

    @Test
    void print_costCurveIncluded() {
        String json = capture(
                Collections.singletonList(proj("GET /x", 5.0, false)),
                config(0));
        assertTrue(json.contains("\"costCurve\""));
        assertTrue(json.contains("\"users\": 100"));
        assertTrue(json.contains("\"users\": 1000"));
    }

    @Test
    void print_summaryTotalIsSumOfProjections() {
        List<EndpointCostProjection> projs = Arrays.asList(
                proj("GET /a", 100.0, false),
                proj("GET /b", 200.25, false));
        String json = capture(projs, config(0));
        assertTrue(json.contains("300.25"));
    }

    @Test
    void print_exceedsBudgetFlagPresent() {
        String json = capture(
                Collections.singletonList(proj("POST /big", 800.0, true)),
                config(0));
        assertTrue(json.contains("\"exceedsBudget\": true"));
    }

    // ── quote() ───────────────────────────────────────────────────────────────

    @Test
    void quote_simpleString() {
        assertEquals("\"hello\"", JsonReporter.quote("hello"));
    }

    @Test
    void quote_stringWithDoubleQuote_escaped() {
        assertEquals("\"say \\\"hi\\\"\"", JsonReporter.quote("say \"hi\""));
    }

    @Test
    void quote_stringWithBackslash_escaped() {
        assertEquals("\"a\\\\b\"", JsonReporter.quote("a\\b"));
    }
}
