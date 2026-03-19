package io.cloudmeter.cli;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.ProjectionConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class CloudMeterCliTest {

    private static final String SAMPLE_JSON = "{\n" +
            "  \"meta\": {\"provider\":\"AWS\",\"region\":\"us-east-1\"," +
            "\"targetUsers\":1000,\"requestsPerUserPerSecond\":1.0," +
            "\"budgetUsd\":0.0,\"pricingDate\":\"2025-01-01\"},\n" +
            "  \"projections\": [\n" +
            "    {\"route\": \"GET /api/health\",\"observedRps\": 1.0," +
            "\"projectedRps\": 100.0,\"projectedMonthlyCostUsd\": 3.8," +
            "\"projectedCostPerUserUsd\": 0.0038,\"recommendedInstance\": \"t3.nano\"," +
            "\"exceedsBudget\": false," +
            "\"costCurve\": [{\"users\": 100, \"monthlyCostUsd\": 3.8}]}\n" +
            "  ],\n" +
            "  \"summary\": {\"totalProjectedMonthlyCostUsd\": 3.8,\"anyExceedsBudget\": false}\n" +
            "}\n";

    private ReportCommand stubCmd(boolean exceed) {
        String json = exceed
                ? SAMPLE_JSON.replace("\"exceedsBudget\": false", "\"exceedsBudget\": true")
                : SAMPLE_JSON;
        return new ReportCommand(url -> json);
    }

    private static int run(String[] args, ReportCommand cmd) {
        return CloudMeterCli.run(args, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), cmd);
    }

    private static int run(String[] args, ReportCommand cmd, PrintStream out, PrintStream err) {
        return CloudMeterCli.run(args, out, err, cmd);
    }

    // ── exit codes ────────────────────────────────────────────────────────────

    @Test
    void exitCodes_definedCorrectly() {
        assertEquals(0, CloudMeterCli.EXIT_OK);
        assertEquals(1, CloudMeterCli.EXIT_BUDGET);
        assertEquals(2, CloudMeterCli.EXIT_ERROR);
    }

    // ── no args ───────────────────────────────────────────────────────────────

    @Test
    void noArgs_returnsError() {
        assertEquals(CloudMeterCli.EXIT_ERROR, run(new String[]{}, stubCmd(false)));
    }

    @Test
    void nullArgs_returnsError() {
        assertEquals(CloudMeterCli.EXIT_ERROR, run(null, stubCmd(false)));
    }

    // ── unknown subcommand ────────────────────────────────────────────────────

    @Test
    void unknownSubcommand_returnsError() {
        assertEquals(CloudMeterCli.EXIT_ERROR, run(new String[]{"attach"}, stubCmd(false)));
    }

    // ── report subcommand — success ───────────────────────────────────────────

    @Test
    void report_noFlags_returnsOk() {
        assertEquals(CloudMeterCli.EXIT_OK, run(new String[]{"report"}, stubCmd(false)));
    }

    @Test
    void report_budgetExceeded_returnsBudgetCode() {
        assertEquals(CloudMeterCli.EXIT_BUDGET,
                run(new String[]{"report", "--budget", "1"}, stubCmd(true)));
    }

    @Test
    void report_formatJson_returnsOk() {
        assertEquals(CloudMeterCli.EXIT_OK,
                run(new String[]{"report", "--format", "json"}, stubCmd(false)));
    }

    @Test
    void report_formatTerminal_returnsOk() {
        assertEquals(CloudMeterCli.EXIT_OK,
                run(new String[]{"report", "--format", "terminal"}, stubCmd(false)));
    }

    // ── flag parsing ──────────────────────────────────────────────────────────

    @Test
    void report_hostAndPort_parsed() {
        assertEquals(CloudMeterCli.EXIT_OK,
                run(new String[]{"report", "--host", "localhost", "--port", "8888"}, stubCmd(false)));
    }

    @Test
    void report_providerRegionUsersRpuBudget_parsed() {
        assertEquals(CloudMeterCli.EXIT_OK, run(new String[]{
                "report",
                "--provider", "GCP",
                "--region", "us-central1",
                "--users", "500",
                "--rpu", "0.5",
                "--budget", "0"
        }, stubCmd(false)));
    }

    @Test
    void report_invalidPort_usesDefault() {
        // Should not error, just use default port
        assertEquals(CloudMeterCli.EXIT_OK,
                run(new String[]{"report", "--port", "notanumber"}, stubCmd(false)));
    }

    @Test
    void report_unknownFlag_returnsError() {
        assertEquals(CloudMeterCli.EXIT_ERROR,
                run(new String[]{"report", "notaflag"}, stubCmd(false)));
    }

    @Test
    void report_unknownFormat_returnsError() {
        assertEquals(CloudMeterCli.EXIT_ERROR,
                run(new String[]{"report", "--format", "xml"}, stubCmd(false)));
    }

    // ── connection failure ────────────────────────────────────────────────────

    @Test
    void report_connectionFailure_returnsOk() {
        ReportCommand failCmd = new ReportCommand(url -> { throw new IOException("refused"); });
        assertEquals(CloudMeterCli.EXIT_OK, run(new String[]{"report"}, failCmd));
    }

    // ── usage output ──────────────────────────────────────────────────────────

    @Test
    void noArgs_printsUsage() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        run(new String[]{}, stubCmd(false), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err));
        assertTrue(err.toString().contains("Usage:"));
    }

    @Test
    void unknownSubcommand_printsUsage() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        run(new String[]{"bad"}, stubCmd(false), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err));
        assertTrue(err.toString().contains("Usage:"));
    }
}
