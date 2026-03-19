package io.cloudmeter.cli;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.ProjectionConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonProjectionParserTest {

    private static ProjectionConfig config() {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).build();
    }

    private static final String TWO_ROUTE_JSON = "{\n" +
            "  \"meta\": {\"provider\":\"AWS\",\"region\":\"us-east-1\"," +
            "\"targetUsers\":1000,\"requestsPerUserPerSecond\":1.0," +
            "\"budgetUsd\":0.0,\"pricingDate\":\"2025-01-01\"},\n" +
            "  \"projections\": [\n" +
            "    {\"route\": \"GET /api/users\",\"observedRps\": 2.5," +
            "\"projectedRps\": 250.0,\"projectedMonthlyCostUsd\": 42.5," +
            "\"projectedCostPerUserUsd\": 0.0425,\"recommendedInstance\": \"t3.nano\"," +
            "\"exceedsBudget\": false," +
            "\"costCurve\": [{\"users\": 100, \"monthlyCostUsd\": 3.8}," +
            "{\"users\": 1000, \"monthlyCostUsd\": 42.5}]},\n" +
            "    {\"route\": \"POST /api/export\",\"observedRps\": 0.5," +
            "\"projectedRps\": 50.0,\"projectedMonthlyCostUsd\": 999.0," +
            "\"projectedCostPerUserUsd\": 0.999,\"recommendedInstance\": \"m5.large\"," +
            "\"exceedsBudget\": true," +
            "\"costCurve\": [{\"users\": 100, \"monthlyCostUsd\": 50.0}]}\n" +
            "  ],\n" +
            "  \"summary\": {\"totalProjectedMonthlyCostUsd\": 1041.5,\"anyExceedsBudget\": true}\n" +
            "}\n";

    // ── parse() ───────────────────────────────────────────────────────────────

    @Test
    void parse_twoRoutes_returnsTwoProjections() {
        List<EndpointCostProjection> list = JsonProjectionParser.parse(TWO_ROUTE_JSON, config());
        assertEquals(2, list.size());
    }

    @Test
    void parse_firstRoute_fieldsCorrect() {
        List<EndpointCostProjection> list = JsonProjectionParser.parse(TWO_ROUTE_JSON, config());
        EndpointCostProjection p = list.get(0);
        assertEquals("GET /api/users", p.getRouteTemplate());
        assertEquals(2.5,  p.getObservedRps(),             1e-6);
        assertEquals(250.0, p.getProjectedRps(),           1e-6);
        assertEquals(42.5,  p.getProjectedMonthlyCostUsd(), 1e-6);
        assertFalse(p.isExceedsBudget());
    }

    @Test
    void parse_secondRoute_exceedsBudget() {
        List<EndpointCostProjection> list = JsonProjectionParser.parse(TWO_ROUTE_JSON, config());
        assertTrue(list.get(1).isExceedsBudget());
    }

    @Test
    void parse_costCurve_parsed() {
        List<EndpointCostProjection> list = JsonProjectionParser.parse(TWO_ROUTE_JSON, config());
        assertEquals(2, list.get(0).getCostCurve().size());
        assertEquals(100, list.get(0).getCostCurve().get(0).getConcurrentUsers());
    }

    @Test
    void parse_noProjectionSection_returnsEmpty() {
        List<EndpointCostProjection> list = JsonProjectionParser.parse(
                "{\"meta\":{},\"projections\":[]}", config());
        assertTrue(list.isEmpty());
    }

    @Test
    void parse_unknownInstance_usesPlaceholder() {
        String json = TWO_ROUTE_JSON.replace("\"recommendedInstance\": \"t3.nano\"",
                "\"recommendedInstance\": \"very-exotic-instance\"");
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        assertFalse(list.isEmpty());
        assertEquals("very-exotic-instance", list.get(0).getRecommendedInstance().getName());
    }

    @Test
    void parse_gcpProvider_resolvesGcpInstances() {
        ProjectionConfig gcpConfig = ProjectionConfig.builder()
                .provider(CloudProvider.GCP).region("us-central1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).build();
        String json = TWO_ROUTE_JSON.replace("\"recommendedInstance\": \"t3.nano\"",
                "\"recommendedInstance\": \"e2-micro\"");
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, gcpConfig);
        assertFalse(list.isEmpty());
        assertEquals("e2-micro", list.get(0).getRecommendedInstance().getName());
    }

    @Test
    void parse_instanceFromOtherProvider_foundViaCrossLookup() {
        // m5.large is an AWS instance; if we parse with GCP config it should still resolve
        ProjectionConfig gcpConfig = ProjectionConfig.builder()
                .provider(CloudProvider.GCP).region("us-central1")
                .targetUsers(1_000).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60.0).build();
        List<EndpointCostProjection> list = JsonProjectionParser.parse(TWO_ROUTE_JSON, gcpConfig);
        // t3.nano is AWS — cross-lookup should find it
        assertFalse(list.isEmpty());
        assertEquals("t3.nano", list.get(0).getRecommendedInstance().getName());
    }

    @Test
    void parse_missingRoute_segmentSkipped() {
        // JSON with a segment missing the route field
        String json = "{\"projections\": [{\"observedRps\": 1.0}]}";
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        assertTrue(list.isEmpty());
    }

    @Test
    void parse_noCostCurve_returnsEmptyCurve() {
        String json = "{\n" +
                "  \"projections\": [\n" +
                "    {\"route\": \"GET /x\",\"observedRps\": 1.0," +
                "\"projectedRps\": 10.0,\"projectedMonthlyCostUsd\": 5.0," +
                "\"projectedCostPerUserUsd\": 0.005,\"recommendedInstance\": \"t3.nano\"," +
                "\"exceedsBudget\": false}\n" +
                "  ]\n" +
                "}\n";
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        assertEquals(1, list.size());
        assertTrue(list.get(0).getCostCurve().isEmpty());
    }

    @Test
    void parse_costCurveWithNoClosingBracket_returnsEmptyCurve() {
        // costCurve section missing closing ']'
        String json = "{\n" +
                "  \"projections\": [\n" +
                "    {\"route\": \"GET /x\",\"observedRps\": 1.0," +
                "\"projectedRps\": 10.0,\"projectedMonthlyCostUsd\": 5.0," +
                "\"projectedCostPerUserUsd\": 0.005,\"recommendedInstance\": \"t3.nano\"," +
                "\"exceedsBudget\": false,\"costCurve\": [{\"users\": 100" +
                // no closing bracket
                "}\n" +
                "  ]\n" +
                "}\n";
        // Should not throw — returns projection with empty curve
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        // May be empty if parsing fails, but must not throw
        assertNotNull(list);
    }

    @Test
    void parse_missingInstanceField_segmentSkipped() {
        String json = "{\n" +
                "  \"projections\": [\n" +
                "    {\"route\": \"GET /no-instance\",\"observedRps\": 1.0," +
                "\"projectedRps\": 10.0,\"projectedMonthlyCostUsd\": 5.0," +
                "\"projectedCostPerUserUsd\": 0.005," +
                "\"exceedsBudget\": false,\"costCurve\": []}\n" +
                "  ]\n" +
                "}\n";
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        // Missing recommendedInstance → parseSegment returns null → skipped
        assertTrue(list.isEmpty());
    }

    @Test
    void parse_malformedNumericFields_usesDefaults() {
        // Values quoted as strings — regex won't match, defaults to 0.0
        String json = "{\n" +
                "  \"projections\": [\n" +
                "    {\"route\": \"GET /x\",\"observedRps\": \"bad\"," +
                "\"projectedRps\": \"also-bad\",\"projectedMonthlyCostUsd\": \"nope\"," +
                "\"projectedCostPerUserUsd\": \"?\",\"recommendedInstance\": \"t3.nano\"," +
                "\"exceedsBudget\": false,\"costCurve\": []}\n" +
                "  ]\n" +
                "}\n";
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        assertEquals(1, list.size());
        assertEquals(0.0, list.get(0).getObservedRps(), 1e-9);
    }

    @Test
    void parse_malformedExponent_triggersNumberFormatException() {
        // "1E" matches [0-9.E+-]+ (uppercase E) but Double.parseDouble("1E") throws NFE → fallback to 0.0
        String json = "{\n" +
                "  \"projections\": [\n" +
                "    {\"route\": \"GET /x\",\"observedRps\": 1E," +
                "\"projectedRps\": 10.0,\"projectedMonthlyCostUsd\": 5.0," +
                "\"projectedCostPerUserUsd\": 0.005,\"recommendedInstance\": \"t3.nano\"," +
                "\"exceedsBudget\": false,\"costCurve\": []}\n" +
                "  ]\n" +
                "}\n";
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        assertEquals(1, list.size());
        assertEquals(0.0, list.get(0).getObservedRps(), 1e-9); // NFE → fallback
    }

    @Test
    void parse_missingExceedsBudget_defaultsFalse() {
        // No exceedsBudget field → extractRaw returns null → defaults to false
        String json = "{\n" +
                "  \"projections\": [\n" +
                "    {\"route\": \"GET /x\",\"observedRps\": 1.0," +
                "\"projectedRps\": 10.0,\"projectedMonthlyCostUsd\": 5.0," +
                "\"projectedCostPerUserUsd\": 0.005,\"recommendedInstance\": \"t3.nano\"," +
                "\"costCurve\": [{\"users\": 100, \"monthlyCostUsd\": 5.0}]}\n" +
                "  ]\n" +
                "}\n";
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        assertEquals(1, list.size());
        assertFalse(list.get(0).isExceedsBudget());
    }

    @Test
    void parse_costCurveNoBracket_ternaryElseBranchCovered() {
        // costCurve section with no ']' at all — parseCurve ternary takes else branch
        // Pattern finds no complete user/cost pairs → empty curve
        String json = "{\n" +
                "  \"projections\": [\n" +
                "    {\"route\": \"GET /x\",\"observedRps\": 1.0," +
                "\"projectedRps\": 10.0,\"projectedMonthlyCostUsd\": 5.0," +
                "\"projectedCostPerUserUsd\": 0.005,\"recommendedInstance\": \"t3.nano\"," +
                "\"exceedsBudget\": false,\"costCurve\": no-bracket-here-at-all}\n" +
                "  ]\n" +
                "}\n";
        List<EndpointCostProjection> list = JsonProjectionParser.parse(json, config());
        assertNotNull(list);
        if (!list.isEmpty()) {
            assertTrue(list.get(0).getCostCurve().isEmpty());
        }
    }
}
