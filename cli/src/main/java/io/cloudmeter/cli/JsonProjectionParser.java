package io.cloudmeter.cli;

import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.InstanceType;
import io.cloudmeter.costengine.PricingCatalog;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.costengine.ScalePoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON parser for the dashboard's {@code /api/projections} response.
 *
 * Parses only the fields required to reconstruct {@link EndpointCostProjection}
 * objects for re-rendering.  Uses regex extraction rather than a full parser so
 * this module stays dependency-free.
 *
 * Fail-safe: any parsing error returns an empty list (ADR-010).
 */
final class JsonProjectionParser {

    // After splitting on "route"\s*:, the segment starts with the route value directly
    private static final Pattern ROUTE_VALUE_PAT = Pattern.compile("^\\s*\"([^\"]+)\"");
    private static final Pattern OBS_RPS_PAT   = Pattern.compile("\"observedRps\"\\s*:\\s*([0-9.E+-]+)");
    private static final Pattern PROJ_RPS_PAT  = Pattern.compile("\"projectedRps\"\\s*:\\s*([0-9.E+-]+)");
    private static final Pattern MONTHLY_PAT   = Pattern.compile("\"projectedMonthlyCostUsd\"\\s*:\\s*([0-9.E+-]+)");
    private static final Pattern PER_USER_PAT  = Pattern.compile("\"projectedCostPerUserUsd\"\\s*:\\s*([0-9.E+-]+)");
    private static final Pattern INSTANCE_PAT  = Pattern.compile("\"recommendedInstance\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DURATION_PAT  = Pattern.compile("\"medianDurationMs\"\\s*:\\s*([0-9.E+-]+)");
    private static final Pattern CPU_MS_PAT    = Pattern.compile("\"medianCpuMs\"\\s*:\\s*([0-9.E+-]+)");
    private static final Pattern EXCEEDS_PAT   = Pattern.compile("\"exceedsBudget\"\\s*:\\s*(true|false)");
    private static final Pattern CURVE_USERS_PAT = Pattern.compile("\"users\"\\s*:\\s*([0-9]+)");
    private static final Pattern CURVE_COST_PAT  = Pattern.compile("\"monthlyCostUsd\"\\s*:\\s*([0-9.E+-]+)");

    private JsonProjectionParser() {}

    static List<EndpointCostProjection> parse(String json, ProjectionConfig config) {
        // Split by projection object boundaries: each starts with "route":
        String[] segments = json.split("\"route\"\\s*:");
        if (segments.length < 2) return Collections.emptyList();

        List<EndpointCostProjection> result = new ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            EndpointCostProjection p = parseSegment(segments[i], config);
            if (p != null) result.add(p);
        }
        return result;
    }

    private static EndpointCostProjection parseSegment(String seg, ProjectionConfig config) {
        String route       = extract(ROUTE_VALUE_PAT, seg);
        double obsRps      = extractDouble(OBS_RPS_PAT, seg, 0.0);
        double projRps     = extractDouble(PROJ_RPS_PAT, seg, 0.0);
        double monthly     = extractDouble(MONTHLY_PAT, seg, 0.0);
        double perUser     = extractDouble(PER_USER_PAT, seg, 0.0);
        String instName    = extract(INSTANCE_PAT, seg);
        double durationMs  = extractDouble(DURATION_PAT, seg, 0.0);
        double cpuMs       = extractDouble(CPU_MS_PAT, seg, 0.0);
        // Re-evaluate against the CLI-supplied budget when one is set; otherwise
        // trust the flag the dashboard computed server-side.
        boolean exceeds = config.getBudgetUsd() > 0
                ? monthly > config.getBudgetUsd()
                : "true".equals(extractRaw(EXCEEDS_PAT, seg));
        List<ScalePoint> curve = parseCurve(seg);

        if (route == null || instName == null) return null;

        InstanceType inst = resolveInstance(instName, config.getProvider());
        return new EndpointCostProjection(route, obsRps, projRps, monthly, perUser,
                inst, curve, exceeds, durationMs, cpuMs / 1000.0);
    }

    private static List<ScalePoint> parseCurve(String seg) {
        // Find the costCurve array section
        int start = seg.indexOf("\"costCurve\"");
        if (start < 0) return Collections.emptyList();
        String curveSection = seg.substring(start);
        int end = curveSection.indexOf(']');
        // Truncate at first ']' when present; otherwise use full section
        curveSection = curveSection.substring(0, end >= 0 ? end : curveSection.length());

        Matcher usersMatcher = CURVE_USERS_PAT.matcher(curveSection);
        Matcher costMatcher  = CURVE_COST_PAT.matcher(curveSection);

        List<Integer> users = new ArrayList<>();
        List<Double>  costs = new ArrayList<>();
        while (usersMatcher.find()) users.add(Integer.parseInt(usersMatcher.group(1)));
        while (costMatcher.find())  costs.add(Double.parseDouble(costMatcher.group(1)));

        List<ScalePoint> curve = new ArrayList<>();
        int n = Math.min(users.size(), costs.size());
        for (int i = 0; i < n; i++) {
            curve.add(new ScalePoint(users.get(i), costs.get(i)));
        }
        return curve;
    }

    private static InstanceType resolveInstance(String name, CloudProvider provider) {
        // Look up in PricingCatalog first
        List<InstanceType> catalog = PricingCatalog.getInstances(provider, "");
        for (InstanceType inst : catalog) {
            if (inst.getName().equals(name)) return inst;
        }
        // Try other providers
        for (CloudProvider p : CloudProvider.values()) {
            if (p == provider) continue;
            for (InstanceType inst : PricingCatalog.getInstances(p, "")) {
                if (inst.getName().equals(name)) return inst;
            }
        }
        // Fallback: placeholder instance
        return new InstanceType(name, provider, 2, 4, 0.0);
    }

    private static String extract(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String extractRaw(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static double extractDouble(Pattern p, String s, double def) {
        String v = extract(p, s);
        if (v == null) return def;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }
    }
}
