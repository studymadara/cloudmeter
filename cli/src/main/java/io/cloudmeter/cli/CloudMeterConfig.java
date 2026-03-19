package io.cloudmeter.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads optional {@code cloudmeter.yaml} from the working directory.
 *
 * Supported format (simple flat key: value, no nesting, no arrays):
 * <pre>
 *   # comment
 *   provider: AWS
 *   region: us-east-1
 *   targetUsers: 10000
 *   rpu: 1.0
 *   budget: 500
 *   port: 7777
 * </pre>
 *
 * Lines starting with {@code #} are comments. Blank lines are ignored.
 * Keys are lower-cased. Values are trimmed.
 * No external YAML library — parsed manually line-by-line.
 */
public final class CloudMeterConfig {

    static final String DEFAULT_FILENAME = "cloudmeter.yaml";

    private CloudMeterConfig() {}

    /** Loads cloudmeter.yaml from the current working directory. Returns empty map if absent. */
    public static Map<String, String> loadYamlMap() {
        return loadYamlMap(new File(DEFAULT_FILENAME));
    }

    /** Package-private overload for testing with a specific file path. */
    static Map<String, String> loadYamlMap(File file) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!file.exists() || !file.isFile()) return map;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int colon = line.indexOf(':');
                if (colon < 1) continue;
                String key = line.substring(0, colon).trim().toLowerCase();
                String val = line.substring(colon + 1).trim();
                if (!key.isEmpty()) map.put(key, val);
            }
        } catch (IOException ignored) {}
        return map;
    }
}
