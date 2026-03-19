package io.cloudmeter.cli;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.ProjectionConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses CloudMeter command-line / agent args into a {@link ProjectionConfig}.
 *
 * Arg format: comma-separated {@code key=value} pairs, e.g.:
 * {@code provider=AWS,region=us-east-1,targetUsers=1000,rpu=0.5,budget=500,port=7777}
 *
 * Recognised keys (all optional — defaults shown):
 * <pre>
 *   provider     AWS | GCP | AZURE         default: AWS
 *   region       any string                default: us-east-1
 *   targetUsers  positive integer          default: 1000
 *   rpu          requests/user/sec (>0)    default: 1.0
 *   duration     recording seconds (>0)    default: 60.0
 *   budget       monthly USD (0 = off)     default: 0.0
 *   port         dashboard port            default: 7777
 * </pre>
 */
public final class CliArgs {

    // Defaults
    static final CloudProvider DEFAULT_PROVIDER     = CloudProvider.AWS;
    static final String        DEFAULT_REGION       = "us-east-1";
    static final int           DEFAULT_TARGET_USERS = 1_000;
    static final double        DEFAULT_RPU          = 1.0;
    static final double        DEFAULT_DURATION     = 60.0;
    static final double        DEFAULT_BUDGET       = 0.0;
    static final int           DEFAULT_PORT         = 7777;

    private final CloudProvider     provider;
    private final String            region;
    private final int               targetUsers;
    private final double            rpu;
    private final double            durationSeconds;
    private final double            budgetUsd;
    private final int               port;

    private CliArgs(Builder b) {
        this.provider        = b.provider;
        this.region          = b.region;
        this.targetUsers     = b.targetUsers;
        this.rpu             = b.rpu;
        this.durationSeconds = b.durationSeconds;
        this.budgetUsd       = b.budgetUsd;
        this.port            = b.port;
    }

    /**
     * Parses the agent args string.  Returns defaults for any missing key.
     * Unrecognised or malformed keys are silently ignored (ADR-010 fault tolerance).
     *
     * @param args may be null or empty — returns all-defaults in that case
     */
    public static CliArgs parse(String args) {
        return buildFromMap(parseMap(args));
    }

    /**
     * Parses agent args merged with values from {@code cloudmeter.yaml} in the working
     * directory. Agent args take priority over YAML values; both override built-in defaults.
     *
     * @param agentArgs may be null or empty
     */
    public static CliArgs parseWithYaml(String agentArgs) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(CloudMeterConfig.loadYamlMap());
        merged.putAll(parseMap(agentArgs));   // agent args win
        return buildFromMap(merged);
    }

    private static CliArgs buildFromMap(Map<String, String> map) {
        Builder b = new Builder();
        b.provider        = parseProvider(map.getOrDefault("provider", ""));
        b.region          = mapGet(map, "region",      DEFAULT_REGION);
        b.targetUsers     = parseInt(map, "targetusers", DEFAULT_TARGET_USERS, 1);
        b.rpu             = parseDouble(map, "rpu",      DEFAULT_RPU, 0.0, false);
        b.durationSeconds = parseDouble(map, "duration", DEFAULT_DURATION, 0.0, false);
        b.budgetUsd       = parseDouble(map, "budget",   DEFAULT_BUDGET, 0.0, true);
        b.port            = parseInt(map, "port",        DEFAULT_PORT, 1);
        return new CliArgs(b);
    }

    /** Constructs a {@link ProjectionConfig} from the parsed arguments. */
    public ProjectionConfig toProjectionConfig() {
        return ProjectionConfig.builder()
                .provider(provider)
                .region(region)
                .targetUsers(targetUsers)
                .requestsPerUserPerSecond(rpu)
                .recordingDurationSeconds(durationSeconds)
                .budgetUsd(budgetUsd)
                .build();
    }

    public CloudProvider getProvider()        { return provider; }
    public String        getRegion()          { return region; }
    public int           getTargetUsers()     { return targetUsers; }
    public double        getRpu()             { return rpu; }
    public double        getDurationSeconds() { return durationSeconds; }
    public double        getBudgetUsd()       { return budgetUsd; }
    public int           getPort()            { return port; }

    // ── Internal helpers ──────────────────────────────────────────────────────

    static Map<String, String> parseMap(String args) {
        if (args == null || args.trim().isEmpty()) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        for (String pair : args.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 1) continue;
            String key = pair.substring(0, eq).trim().toLowerCase();
            String val = pair.substring(eq + 1).trim();
            if (!key.isEmpty()) map.put(key, val);
        }
        return map;
    }

    private static CloudProvider parseProvider(String val) {
        if (val == null || val.isEmpty()) return DEFAULT_PROVIDER;
        try {
            return CloudProvider.valueOf(val.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT_PROVIDER;
        }
    }

    private static String mapGet(Map<String, String> map, String key, String def) {
        String v = map.get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int parseInt(Map<String, String> map, String key, int def, int min) {
        String v = map.get(key);
        if (v == null) return def;
        try {
            int parsed = Integer.parseInt(v);
            return parsed >= min ? parsed : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(Map<String, String> map, String key,
                                      double def, double min, boolean allowMin) {
        String v = map.get(key);
        if (v == null) return def;
        try {
            double parsed = Double.parseDouble(v);
            boolean valid = allowMin ? (parsed >= min) : (parsed > min);
            return valid ? parsed : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    private static final class Builder {
        CloudProvider provider        = DEFAULT_PROVIDER;
        String        region          = DEFAULT_REGION;
        int           targetUsers     = DEFAULT_TARGET_USERS;
        double        rpu             = DEFAULT_RPU;
        double        durationSeconds = DEFAULT_DURATION;
        double        budgetUsd       = DEFAULT_BUDGET;
        int           port            = DEFAULT_PORT;
    }
}
