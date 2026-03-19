package io.cloudmeter.cli;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.ProjectionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliArgsTest {

    @TempDir
    Path tempDir;

    // ── parse() — null / empty ────────────────────────────────────────────────

    @Test
    void parse_null_returnsDefaults() {
        CliArgs a = CliArgs.parse(null);
        assertEquals(CliArgs.DEFAULT_PROVIDER,     a.getProvider());
        assertEquals(CliArgs.DEFAULT_REGION,       a.getRegion());
        assertEquals(CliArgs.DEFAULT_TARGET_USERS, a.getTargetUsers());
        assertEquals(CliArgs.DEFAULT_RPU,          a.getRpu(), 1e-9);
        assertEquals(CliArgs.DEFAULT_DURATION,     a.getDurationSeconds(), 1e-9);
        assertEquals(CliArgs.DEFAULT_BUDGET,       a.getBudgetUsd(), 1e-9);
        assertEquals(CliArgs.DEFAULT_PORT,         a.getPort());
    }

    @Test
    void parse_empty_returnsDefaults() {
        CliArgs a = CliArgs.parse("");
        assertEquals(CliArgs.DEFAULT_PROVIDER, a.getProvider());
    }

    @Test
    void parse_whitespaceOnly_returnsDefaults() {
        CliArgs a = CliArgs.parse("   ");
        assertEquals(CliArgs.DEFAULT_PROVIDER, a.getProvider());
    }

    // ── parse() — provider ────────────────────────────────────────────────────

    @Test
    void parse_providerAWS_lowercase() {
        assertEquals(CloudProvider.AWS, CliArgs.parse("provider=aws").getProvider());
    }

    @Test
    void parse_providerGCP() {
        assertEquals(CloudProvider.GCP, CliArgs.parse("provider=GCP").getProvider());
    }

    @Test
    void parse_providerAzure() {
        assertEquals(CloudProvider.AZURE, CliArgs.parse("provider=azure").getProvider());
    }

    @Test
    void parse_unknownProvider_usesDefault() {
        assertEquals(CliArgs.DEFAULT_PROVIDER, CliArgs.parse("provider=UNKNOWN").getProvider());
    }

    @Test
    void parse_emptyProvider_usesDefault() {
        assertEquals(CliArgs.DEFAULT_PROVIDER, CliArgs.parse("provider=").getProvider());
    }

    // ── parse() — numeric fields ──────────────────────────────────────────────

    @Test
    void parse_targetUsers_parsed() {
        assertEquals(5_000, CliArgs.parse("targetUsers=5000").getTargetUsers());
    }

    @Test
    void parse_targetUsersBelowMin_usesDefault() {
        assertEquals(CliArgs.DEFAULT_TARGET_USERS, CliArgs.parse("targetUsers=0").getTargetUsers());
    }

    @Test
    void parse_targetUsersNonNumeric_usesDefault() {
        assertEquals(CliArgs.DEFAULT_TARGET_USERS, CliArgs.parse("targetUsers=abc").getTargetUsers());
    }

    @Test
    void parse_rpu_parsed() {
        assertEquals(2.0, CliArgs.parse("rpu=2.0").getRpu(), 1e-9);
    }

    @Test
    void parse_rpuZero_usesDefault() {
        assertEquals(CliArgs.DEFAULT_RPU, CliArgs.parse("rpu=0").getRpu(), 1e-9);
    }

    @Test
    void parse_rpuNonNumeric_usesDefault() {
        assertEquals(CliArgs.DEFAULT_RPU, CliArgs.parse("rpu=bad").getRpu(), 1e-9);
    }

    @Test
    void parse_duration_parsed() {
        assertEquals(120.0, CliArgs.parse("duration=120.0").getDurationSeconds(), 1e-9);
    }

    @Test
    void parse_durationZero_usesDefault() {
        assertEquals(CliArgs.DEFAULT_DURATION, CliArgs.parse("duration=0").getDurationSeconds(), 1e-9);
    }

    @Test
    void parse_budget_parsed() {
        assertEquals(500.0, CliArgs.parse("budget=500").getBudgetUsd(), 1e-9);
    }

    @Test
    void parse_budgetZero_allowed() {
        assertEquals(0.0, CliArgs.parse("budget=0").getBudgetUsd(), 1e-9);
    }

    @Test
    void parse_budgetNegative_usesDefault() {
        assertEquals(CliArgs.DEFAULT_BUDGET, CliArgs.parse("budget=-1").getBudgetUsd(), 1e-9);
    }

    @Test
    void parse_port_parsed() {
        assertEquals(8888, CliArgs.parse("port=8888").getPort());
    }

    @Test
    void parse_portZero_usesDefault() {
        assertEquals(CliArgs.DEFAULT_PORT, CliArgs.parse("port=0").getPort());
    }

    // ── parse() — region ─────────────────────────────────────────────────────

    @Test
    void parse_region_parsed() {
        assertEquals("us-west-2", CliArgs.parse("region=us-west-2").getRegion());
    }

    @Test
    void parse_emptyRegion_usesDefault() {
        assertEquals(CliArgs.DEFAULT_REGION, CliArgs.parse("region=").getRegion());
    }

    // ── parse() — multiple fields ─────────────────────────────────────────────

    @Test
    void parse_allFields_parsedCorrectly() {
        CliArgs a = CliArgs.parse(
                "provider=GCP,region=us-central1,targetUsers=2000,rpu=2.0,duration=90,budget=300,port=8080");
        assertEquals(CloudProvider.GCP, a.getProvider());
        assertEquals("us-central1", a.getRegion());
        assertEquals(2_000, a.getTargetUsers());
        assertEquals(2.0,   a.getRpu(), 1e-9);
        assertEquals(90.0,  a.getDurationSeconds(), 1e-9);
        assertEquals(300.0, a.getBudgetUsd(), 1e-9);
        assertEquals(8080,  a.getPort());
    }

    @Test
    void parse_malformedPair_ignored() {
        CliArgs a = CliArgs.parse("port=9000,noequalssign,provider=GCP");
        assertEquals(9000, a.getPort());
        assertEquals(CloudProvider.GCP, a.getProvider());
    }

    // ── parseMap() ────────────────────────────────────────────────────────────

    @Test
    void parseMap_null_returnsEmpty() {
        assertTrue(CliArgs.parseMap(null).isEmpty());
    }

    @Test
    void parseMap_singlePair() {
        Map<String, String> map = CliArgs.parseMap("key=value");
        assertEquals("value", map.get("key"));
    }

    @Test
    void parseMap_keysLowercased() {
        Map<String, String> map = CliArgs.parseMap("Provider=AWS");
        assertEquals("AWS", map.get("provider"));
    }

    @Test
    void parseMap_valueWithEquals_preservedAfterFirstEquals() {
        Map<String, String> map = CliArgs.parseMap("k=a=b");
        assertEquals("a=b", map.get("k"));
    }

    // ── parseWithYaml() ───────────────────────────────────────────────────────

    private File writeYaml(String content) throws Exception {
        File f = tempDir.resolve("cloudmeter.yaml").toFile();
        try (PrintWriter pw = new PrintWriter(f)) {
            pw.print(content);
        }
        return f;
    }

    @Test
    void parseWithYaml_noYamlFile_returnsDefaults() {
        // No yaml file in working dir — should behave like parse(null)
        CliArgs a = CliArgs.parseWithYaml(null);
        assertEquals(CliArgs.DEFAULT_PROVIDER,     a.getProvider());
        assertEquals(CliArgs.DEFAULT_REGION,       a.getRegion());
        assertEquals(CliArgs.DEFAULT_TARGET_USERS, a.getTargetUsers());
    }

    @Test
    void parseWithYaml_agentArgsOverrideYamlValues() throws Exception {
        // Write a yaml, but agent arg for provider wins
        File yaml = writeYaml("provider: GCP\nregion: us-central1\n");
        // Use CloudMeterConfig directly to simulate yaml in a known location
        Map<String, String> yamlMap = CloudMeterConfig.loadYamlMap(yaml);
        Map<String, String> merged = new java.util.LinkedHashMap<>(yamlMap);
        merged.putAll(CliArgs.parseMap("provider=AZURE"));
        CliArgs a = CliArgs.parse("provider=AZURE,region=us-central1");
        assertEquals(CloudProvider.AZURE, a.getProvider());
        assertEquals("us-central1", a.getRegion());
    }

    @Test
    void parseWithYaml_nullAgentArgs_doesNotThrow() {
        assertDoesNotThrow(() -> CliArgs.parseWithYaml(null));
    }

    @Test
    void parseWithYaml_emptyAgentArgs_doesNotThrow() {
        assertDoesNotThrow(() -> CliArgs.parseWithYaml(""));
    }

    @Test
    void parseWithYaml_withAgentArgs_agentArgsWin() {
        // When cloudmeter.yaml has no targetUsers but agent args do — agent args win
        CliArgs a = CliArgs.parseWithYaml("targetUsers=9999");
        assertEquals(9999, a.getTargetUsers());
    }

    // ── toProjectionConfig() ─────────────────────────────────────────────────

    @Test
    void toProjectionConfig_returnsCorrectConfig() {
        CliArgs a = CliArgs.parse("provider=GCP,region=eu-west1,targetUsers=500,rpu=0.5,duration=30,budget=100");
        ProjectionConfig cfg = a.toProjectionConfig();
        assertEquals(CloudProvider.GCP, cfg.getProvider());
        assertEquals("eu-west1", cfg.getRegion());
        assertEquals(500, cfg.getTargetUsers());
        assertEquals(0.5, cfg.getRequestsPerUserPerSecond(), 1e-9);
        assertEquals(30.0, cfg.getRecordingDurationSeconds(), 1e-9);
        assertEquals(100.0, cfg.getBudgetUsd(), 1e-9);
    }
}
