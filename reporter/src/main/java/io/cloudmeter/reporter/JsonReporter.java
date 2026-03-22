package io.cloudmeter.reporter;

import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.PricingCatalog;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.costengine.ScalePoint;

import java.io.PrintStream;
import java.util.List;

/**
 * Serialises cost projections to a JSON document suitable for CI/CD pipelines.
 *
 * The output is a single JSON object with a {@code projections} array and a
 * {@code summary} block.  No third-party JSON library is used — the document is
 * assembled with a simple {@link StringBuilder} so this module stays dependency-free.
 *
 * Exit-code contract: callers should exit with code 1 when {@link #print} returns
 * {@code true} (one or more endpoints exceed the configured budget).
 */
public final class JsonReporter {

    private JsonReporter() {}

    /**
     * Writes the JSON report to {@code out}.
     *
     * @return {@code true} if any endpoint projection exceeds the budget threshold
     */
    public static boolean print(List<EndpointCostProjection> projections,
                                ProjectionConfig config, PrintStream out) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"meta\": {\n");
        sb.append("    \"provider\": ").append(quote(config.getProvider().name())).append(",\n");
        sb.append("    \"region\": ").append(quote(config.getRegion())).append(",\n");
        sb.append("    \"targetUsers\": ").append(config.getTargetUsers()).append(",\n");
        sb.append("    \"requestsPerUserPerSecond\": ").append(config.getRequestsPerUserPerSecond()).append(",\n");
        sb.append("    \"budgetUsd\": ").append(config.getBudgetUsd()).append(",\n");
        sb.append("    \"pricingDate\": ").append(quote(PricingCatalog.getPricingDate())).append(",\n");
        sb.append("    \"pricingSource\": ").append(quote(PricingCatalog.isLive() ? "live" : "static")).append("\n");
        sb.append("  },\n");

        sb.append("  \"projections\": [\n");
        boolean anyExceeds = false;
        for (int i = 0; i < projections.size(); i++) {
            EndpointCostProjection p = projections.get(i);
            if (p.isExceedsBudget()) anyExceeds = true;
            appendProjection(sb, p);
            if (i < projections.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        double totalMonthly = 0.0;
        for (EndpointCostProjection p : projections) {
            totalMonthly += p.getProjectedMonthlyCostUsd();
        }
        sb.append("  \"summary\": {\n");
        sb.append("    \"totalProjectedMonthlyCostUsd\": ").append(round2(totalMonthly)).append(",\n");
        sb.append("    \"anyExceedsBudget\": ").append(anyExceeds).append("\n");
        sb.append("  }\n");
        sb.append("}\n");

        out.print(sb);
        return anyExceeds;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void appendProjection(StringBuilder sb, EndpointCostProjection p) {
        sb.append("    {\n");
        sb.append("      \"route\": ").append(quote(p.getRouteTemplate())).append(",\n");
        sb.append("      \"observedRps\": ").append(round4(p.getObservedRps())).append(",\n");
        sb.append("      \"projectedRps\": ").append(round4(p.getProjectedRps())).append(",\n");
        sb.append("      \"projectedMonthlyCostUsd\": ").append(round2(p.getProjectedMonthlyCostUsd())).append(",\n");
        sb.append("      \"projectedCostPerUserUsd\": ").append(round6(p.getProjectedCostPerUserUsd())).append(",\n");
        sb.append("      \"recommendedInstance\": ").append(quote(p.getRecommendedInstance().getName())).append(",\n");
        sb.append("      \"medianDurationMs\": ").append(round2(p.getMedianDurationMs())).append(",\n");
        sb.append("      \"medianCpuMs\": ").append(round2(p.getMedianCpuCoreSecondsPerReq() * 1000.0)).append(",\n");
        sb.append("      \"exceedsBudget\": ").append(p.isExceedsBudget()).append(",\n");
        sb.append("      \"costCurve\": [\n");
        List<ScalePoint> curve = p.getCostCurve();
        for (int i = 0; i < curve.size(); i++) {
            ScalePoint sp = curve.get(i);
            sb.append("        {\"users\": ").append(sp.getConcurrentUsers())
              .append(", \"monthlyCostUsd\": ").append(round2(sp.getMonthlyCostUsd())).append("}");
            if (i < curve.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("      ]\n");
        sb.append("    }");
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static double round2(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round6(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.round(v * 1000000.0) / 1000000.0;
    }
}
