package io.cloudmeter.reporter;

import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.ProjectionConfig;

import java.io.PrintStream;
import java.util.List;

/**
 * Prints a human-readable cost projection table to a {@link PrintStream}.
 *
 * Output is designed for developer terminals: fixed-width columns, ANSI budget
 * warning markers, and a summary footer with total projected monthly spend.
 */
public final class TerminalReporter {

    // Column widths
    static final int COL_ROUTE    = 42;
    static final int COL_OBS_RPS  = 10;
    static final int COL_PROJ_RPS = 10;
    static final int COL_MONTHLY  = 12;
    static final int COL_PER_USER = 12;
    static final int COL_AVG_MS   = 10;
    static final int COL_CPU_MS   = 12;
    static final int COL_INSTANCE = 14;

    public static final String BUDGET_MARKER = " !!";
    static final String DIVIDER = repeat("-", COL_ROUTE + COL_OBS_RPS + COL_PROJ_RPS
            + COL_MONTHLY + COL_PER_USER + COL_AVG_MS + COL_CPU_MS + COL_INSTANCE + 13);

    private TerminalReporter() {}

    /**
     * Prints the full cost projection report.
     *
     * @param projections sorted list of endpoint projections (usually descending by cost)
     * @param config      the projection configuration used to produce the projections
     * @param out         destination stream (typically {@code System.out})
     */
    public static void print(List<EndpointCostProjection> projections,
                             ProjectionConfig config, PrintStream out) {
        printHeader(config, out);
        out.println(DIVIDER);
        printColumnHeaders(out);
        out.println(DIVIDER);

        double totalMonthly = 0.0;
        boolean anyExceeds = false;
        for (EndpointCostProjection p : projections) {
            printRow(p, out);
            totalMonthly += p.getProjectedMonthlyCostUsd();
            if (p.isExceedsBudget()) {
                anyExceeds = true;
            }
        }

        out.println(DIVIDER);
        printFooter(totalMonthly, config, anyExceeds, out);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void printHeader(ProjectionConfig config, PrintStream out) {
        out.println();
        out.println("  CloudMeter Cost Projection Report");
        out.println("  Provider : " + config.getProvider() + " (" + config.getRegion() + ")");
        out.println("  Target   : " + config.getTargetUsers() + " concurrent users"
                + "  @  " + config.getRequestsPerUserPerSecond() + " req/user/sec");
        if (config.getBudgetUsd() > 0) {
            out.println("  Budget   : $" + String.format("%.2f", config.getBudgetUsd()) + " /month per endpoint");
        }
        out.println();
    }

    private static void printColumnHeaders(PrintStream out) {
        out.println(
            padRight("  Route", COL_ROUTE + 2) +
            padLeft("Obs RPS", COL_OBS_RPS) +
            padLeft("Proj RPS", COL_PROJ_RPS + 2) +
            padLeft("$/month", COL_MONTHLY) +
            padLeft("$/user", COL_PER_USER) +
            padLeft("Avg ms", COL_AVG_MS) +
            padLeft("CPU ms/req", COL_CPU_MS) +
            padLeft("Instance", COL_INSTANCE)
        );
    }

    private static void printRow(EndpointCostProjection p, PrintStream out) {
        String budgetFlag = p.isExceedsBudget() ? BUDGET_MARKER : "";
        out.println(
            padRight("  " + truncate(p.getRouteTemplate(), COL_ROUTE), COL_ROUTE + 2) +
            padLeft(String.format("%.2f", p.getObservedRps()), COL_OBS_RPS) +
            padLeft(String.format("%.1f", p.getProjectedRps()), COL_PROJ_RPS + 2) +
            padLeft("$" + String.format("%.2f", p.getProjectedMonthlyCostUsd()), COL_MONTHLY) +
            padLeft("$" + String.format("%.4f", p.getProjectedCostPerUserUsd()), COL_PER_USER) +
            padLeft(String.format("%.1f", p.getMedianDurationMs()), COL_AVG_MS) +
            padLeft(String.format("%.2f", p.getMedianCpuCoreSecondsPerReq() * 1000.0), COL_CPU_MS) +
            padLeft(p.getRecommendedInstance().getName(), COL_INSTANCE) +
            budgetFlag
        );
    }

    private static void printFooter(double totalMonthly, ProjectionConfig config,
                                    boolean anyExceeds, PrintStream out) {
        out.println(String.format("  Total projected monthly cost: $%.2f", totalMonthly));
        if (config.getBudgetUsd() > 0 && anyExceeds) {
            out.println("  !! One or more endpoints exceed the budget threshold.");
        }
        out.println("  Pricing: " + config.getProvider()
                + " on-demand Linux (PricingCatalog " + io.cloudmeter.costengine.PricingCatalog.PRICING_DATE + ")");
        out.println("  Note: costs are standalone per-endpoint estimates (ADR-009).");
        out.println();
    }

    // ── String utilities ──────────────────────────────────────────────────────

    static String padRight(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    static String padLeft(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < width - s.length()) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    private static String repeat(String c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
