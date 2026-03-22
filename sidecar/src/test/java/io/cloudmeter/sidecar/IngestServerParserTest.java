package io.cloudmeter.sidecar;

import io.cloudmeter.collector.RequestMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IngestServer's static JSON parsing methods.
 * Tests at the method level — no HTTP server needed.
 */
class IngestServerParserTest {

    // ── parseMetrics ──────────────────────────────────────────────────────────

    @Test
    void parseMetrics_nullBody_throws() {
        assertThrows(IngestServer.ParseException.class, () -> IngestServer.parseMetrics(null));
    }

    @Test
    void parseMetrics_blankBody_throws() {
        assertThrows(IngestServer.ParseException.class, () -> IngestServer.parseMetrics("   "));
    }

    @Test
    void parseMetrics_notAnObject_throws() {
        assertThrows(IngestServer.ParseException.class,
                () -> IngestServer.parseMetrics("[\"array\"]"));
    }

    @Test
    void parseMetrics_missingMethod_throws() {
        // Has route and durationMs but no method
        String json = "{\"route\":\"GET /api/x\",\"status\":200,\"durationMs\":10}";
        assertThrows(IngestServer.ParseException.class, () -> IngestServer.parseMetrics(json));
    }

    @Test
    void parseMetrics_withEgressBytes_setsEgressBytes() throws Exception {
        String json = "{\"route\":\"GET /api/x\",\"method\":\"GET\","
                + "\"status\":200,\"durationMs\":30,\"egressBytes\":512}";
        RequestMetrics m = IngestServer.parseMetrics(json);
        assertEquals(512L, m.getEgressBytes());
    }

    @Test
    void parseMetrics_withoutEgressBytes_defaultsToZero() throws Exception {
        String json = "{\"route\":\"POST /api/ingest\",\"method\":\"POST\","
                + "\"status\":201,\"durationMs\":20}";
        RequestMetrics m = IngestServer.parseMetrics(json);
        assertEquals(0L, m.getEgressBytes());
    }

    @Test
    void parseMetrics_cpuCoreSecondsAlwaysZero() throws Exception {
        String json = "{\"route\":\"GET /ok\",\"method\":\"GET\","
                + "\"status\":200,\"durationMs\":5}";
        RequestMetrics m = IngestServer.parseMetrics(json);
        assertEquals(0.0, m.getCpuCoreSeconds());
    }

    @Test
    void parseMetrics_warmupAlwaysFalse() throws Exception {
        String json = "{\"route\":\"GET /ok\",\"method\":\"GET\","
                + "\"status\":200,\"durationMs\":5}";
        RequestMetrics m = IngestServer.parseMetrics(json);
        assertFalse(m.isWarmup());
    }

    // ── extractStringField ────────────────────────────────────────────────────

    @Test
    void extractStringField_fieldAbsent_returnsNull() throws Exception {
        assertNull(IngestServer.extractStringField("{\"other\":\"val\"}", "route"));
    }

    @Test
    void extractStringField_valueWithEscapedQuote() throws Exception {
        // route value contains an escaped quote
        String json = "{\"route\":\"GET /api/\\\"test\\\"\",\"extra\":1}";
        assertEquals("GET /api/\"test\"", IngestServer.extractStringField(json, "route"));
    }

    @Test
    void extractStringField_unterminatedString_throws() {
        // No closing quote for the value
        String json = "{\"route\":\"GET /api/unterminated";
        assertThrows(IngestServer.ParseException.class,
                () -> IngestServer.extractStringField(json, "route"));
    }

    @Test
    void extractStringField_nonStringValue_throws() {
        // Status is a number, not a string — should throw when asked for string
        String json = "{\"route\":123}";
        assertThrows(IngestServer.ParseException.class,
                () -> IngestServer.extractStringField(json, "route"));
    }

    @Test
    void extractStringField_malformedMissingColon_throws() {
        // Field key present but no colon separator
        String json = "{\"route\"\"value\"}";
        assertThrows(IngestServer.ParseException.class,
                () -> IngestServer.extractStringField(json, "route"));
    }

    // ── extractIntField ───────────────────────────────────────────────────────

    @Test
    void extractIntField_absent_returnsDefault() throws Exception {
        assertEquals(200, IngestServer.extractIntField("{\"durationMs\":10}", "status", 200));
    }

    @Test
    void extractIntField_present_returnsParsedValue() throws Exception {
        assertEquals(404, IngestServer.extractIntField("{\"status\":404}", "status", 200));
    }

    @Test
    void extractIntField_nonNumeric_throws() {
        assertThrows(IngestServer.ParseException.class,
                () -> IngestServer.extractIntField("{\"status\":\"ok\"}", "status", 200));
    }

    // ── extractLongField ──────────────────────────────────────────────────────

    @Test
    void extractLongField_absent_throws() {
        assertThrows(IngestServer.ParseException.class,
                () -> IngestServer.extractLongField("{\"other\":1}", "durationMs"));
    }

    @Test
    void extractLongField_nonNumeric_throws() {
        assertThrows(IngestServer.ParseException.class,
                () -> IngestServer.extractLongField("{\"durationMs\":\"fast\"}", "durationMs"));
    }

    // ── extractOptionalLongField ──────────────────────────────────────────────

    @Test
    void extractOptionalLongField_absent_returnsDefault() throws Exception {
        assertEquals(99L, IngestServer.extractOptionalLongField("{\"a\":1}", "egressBytes", 99L));
    }

    @Test
    void extractOptionalLongField_nonNumeric_throws() {
        assertThrows(IngestServer.ParseException.class,
                () -> IngestServer.extractOptionalLongField(
                        "{\"egressBytes\":\"big\"}", "egressBytes", 0L));
    }
}
