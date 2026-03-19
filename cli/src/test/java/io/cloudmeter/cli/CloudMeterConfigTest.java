package io.cloudmeter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudMeterConfigTest {

    @TempDir
    Path tempDir;

    // ── helper ────────────────────────────────────────────────────────────────

    private File writeYaml(String content) throws IOException {
        File f = tempDir.resolve("cloudmeter.yaml").toFile();
        try (PrintWriter pw = new PrintWriter(f)) {
            pw.print(content);
        }
        return f;
    }

    // ── absent file ───────────────────────────────────────────────────────────

    @Test
    void loadYamlMap_fileAbsent_returnsEmptyMap() {
        File nonExistent = tempDir.resolve("missing.yaml").toFile();
        assertTrue(CloudMeterConfig.loadYamlMap(nonExistent).isEmpty());
    }

    @Test
    void loadYamlMap_fileIsDirectory_returnsEmptyMap() {
        // Pass the tempDir itself (which is a directory, not a file)
        assertTrue(CloudMeterConfig.loadYamlMap(tempDir.toFile()).isEmpty());
    }

    // ── empty / whitespace-only file ──────────────────────────────────────────

    @Test
    void loadYamlMap_emptyFile_returnsEmptyMap() throws IOException {
        File f = writeYaml("");
        assertTrue(CloudMeterConfig.loadYamlMap(f).isEmpty());
    }

    @Test
    void loadYamlMap_blankLinesOnly_returnsEmptyMap() throws IOException {
        File f = writeYaml("\n\n   \n");
        assertTrue(CloudMeterConfig.loadYamlMap(f).isEmpty());
    }

    // ── comments ──────────────────────────────────────────────────────────────

    @Test
    void loadYamlMap_commentsOnly_returnsEmptyMap() throws IOException {
        File f = writeYaml("# This is a comment\n# Another comment\n");
        assertTrue(CloudMeterConfig.loadYamlMap(f).isEmpty());
    }

    @Test
    void loadYamlMap_commentLinesIgnored() throws IOException {
        File f = writeYaml("# comment\nprovider: AWS\n# another comment\nregion: us-west-2\n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertEquals(2, map.size());
        assertEquals("AWS", map.get("provider"));
        assertEquals("us-west-2", map.get("region"));
    }

    // ── basic parsing ─────────────────────────────────────────────────────────

    @Test
    void loadYamlMap_singleKey_parsed() throws IOException {
        File f = writeYaml("provider: GCP\n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertEquals("GCP", map.get("provider"));
    }

    @Test
    void loadYamlMap_keysAreLowerCased() throws IOException {
        File f = writeYaml("Provider: AWS\nREGION: eu-west-1\n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertEquals("AWS", map.get("provider"));
        assertEquals("eu-west-1", map.get("region"));
    }

    @Test
    void loadYamlMap_valuesAreTrimmed() throws IOException {
        File f = writeYaml("provider:   AWS   \nregion:  us-east-1  \n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertEquals("AWS", map.get("provider"));
        assertEquals("us-east-1", map.get("region"));
    }

    @Test
    void loadYamlMap_allSupportedKeys_parsed() throws IOException {
        File f = writeYaml(
                "provider: AWS\n" +
                "region: us-east-1\n" +
                "targetUsers: 10000\n" +
                "rpu: 1.5\n" +
                "budget: 500\n" +
                "port: 7777\n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertEquals("AWS",      map.get("provider"));
        assertEquals("us-east-1", map.get("region"));
        assertEquals("10000",    map.get("targetusers"));
        assertEquals("1.5",      map.get("rpu"));
        assertEquals("500",      map.get("budget"));
        assertEquals("7777",     map.get("port"));
    }

    // ── malformed lines ───────────────────────────────────────────────────────

    @Test
    void loadYamlMap_lineWithoutColon_ignored() throws IOException {
        File f = writeYaml("thisisalinewithoutcolon\nprovider: AWS\n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertEquals(1, map.size());
        assertEquals("AWS", map.get("provider"));
    }

    @Test
    void loadYamlMap_lineWithColonAtPosition0_ignored() throws IOException {
        // colon < 1 means key length 0 — should be skipped
        File f = writeYaml(":value\nprovider: GCP\n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertEquals(1, map.size());
        assertEquals("GCP", map.get("provider"));
    }

    @Test
    void loadYamlMap_emptyValueAfterColon_stored() throws IOException {
        // "key: " with empty value — stored as empty string
        File f = writeYaml("provider: \n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertTrue(map.containsKey("provider"));
        assertEquals("", map.get("provider"));
    }

    @Test
    void loadYamlMap_valueWithColonInside_preservedAfterFirstColon() throws IOException {
        // YAML values like "http://example.com" should keep the colon in value
        File f = writeYaml("endpoint: http://example.com\n");
        Map<String, String> map = CloudMeterConfig.loadYamlMap(f);
        assertEquals("http://example.com", map.get("endpoint"));
    }

    // ── DEFAULT_FILENAME constant ─────────────────────────────────────────────

    @Test
    void defaultFilename_isCloudMeterYaml() {
        assertEquals("cloudmeter.yaml", CloudMeterConfig.DEFAULT_FILENAME);
    }

    // ── loadYamlMap() public overload (uses working dir) ─────────────────────

    @Test
    void loadYamlMap_publicOverload_doesNotThrow() {
        // Working dir almost certainly has no cloudmeter.yaml — must return empty, not throw
        assertDoesNotThrow(() -> {
            Map<String, String> map = CloudMeterConfig.loadYamlMap();
            assertNotNull(map);
        });
    }

    // ── constructor is private ────────────────────────────────────────────────

    @Test
    void constructor_isPrivate() throws Exception {
        java.lang.reflect.Constructor<CloudMeterConfig> ctor =
                CloudMeterConfig.class.getDeclaredConstructor();
        assertFalse(ctor.isAccessible());
        ctor.setAccessible(true);
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) ctor::newInstance);
    }
}
