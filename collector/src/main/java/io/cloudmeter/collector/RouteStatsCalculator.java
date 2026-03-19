package io.cloudmeter.collector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes per-route cost statistics from a list of {@link RequestMetrics}.
 *
 * Cost per request is approximated as:
 *   cpuCoreSeconds × CPU_COST_PER_CORE_SECOND_USD + egressBytes × EGRESS_COST_PER_BYTE_USD
 *
 * This is a simplified per-request cost signal used for relative ranking and
 * variance detection. Full monthly projection lives in the cost-engine module.
 */
public final class RouteStatsCalculator {

    /** Default number of outlier requests surfaced per route. */
    public static final int DEFAULT_OUTLIER_COUNT = 10;

    /**
     * Approximate cost of one CPU core-second on a mid-range cloud instance (us-east-1).
     * Used only for intra-session relative ranking — not for final cost projection.
     */
    static final double CPU_COST_PER_CORE_SECOND_USD = 0.000_024; // ~$0.024 per core-hour

    /** Approximate egress cost per byte (AWS us-east-1: $0.09/GB). */
    static final double EGRESS_COST_PER_BYTE_USD = 0.09 / 1_073_741_824.0;

    private final int outlierCount;

    public RouteStatsCalculator() {
        this(DEFAULT_OUTLIER_COUNT);
    }

    public RouteStatsCalculator(int outlierCount) {
        if (outlierCount <= 0) throw new IllegalArgumentException("outlierCount must be > 0");
        this.outlierCount = outlierCount;
    }

    /**
     * Computes {@link RouteStats} for every distinct route in the provided list.
     * Warmup entries are excluded.
     *
     * @param metrics all buffered metrics (may be empty; must not be null)
     * @return one RouteStats per distinct route template, in descending p50 cost order
     */
    public List<RouteStats> calculate(List<RequestMetrics> metrics) {
        Map<String, List<RequestMetrics>> byRoute = groupByRoute(metrics);
        List<RouteStats> result = new ArrayList<>(byRoute.size());
        for (Map.Entry<String, List<RequestMetrics>> entry : byRoute.entrySet()) {
            result.add(buildRouteStats(entry.getKey(), entry.getValue()));
        }
        Collections.sort(result, new Comparator<RouteStats>() {
            @Override
            public int compare(RouteStats a, RouteStats b) {
                return Double.compare(b.getP50CostUsd(), a.getP50CostUsd()); // descending
            }
        });
        return result;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private Map<String, List<RequestMetrics>> groupByRoute(List<RequestMetrics> metrics) {
        Map<String, List<RequestMetrics>> map = new HashMap<>();
        for (RequestMetrics m : metrics) {
            if (m.isWarmup()) continue;
            List<RequestMetrics> list = map.get(m.getRouteTemplate());
            if (list == null) {
                list = new ArrayList<>();
                map.put(m.getRouteTemplate(), list);
            }
            list.add(m);
        }
        return map;
    }

    private RouteStats buildRouteStats(String route, List<RequestMetrics> entries) {
        List<Double> costs = new ArrayList<>(entries.size());
        for (RequestMetrics m : entries) {
            costs.add(costForRequest(m));
        }
        Collections.sort(costs);

        double p50 = percentile(costs, 50);
        double p95 = percentile(costs, 95);
        double p99 = percentile(costs, 99);
        double max = costs.get(costs.size() - 1);

        List<RequestMetrics> outliers = topN(entries, outlierCount);

        return new RouteStats(route, entries.size(), p50, p95, p99, max, outliers);
    }

    static double costForRequest(RequestMetrics m) {
        return m.getCpuCoreSeconds() * CPU_COST_PER_CORE_SECOND_USD
             + m.getEgressBytes()    * EGRESS_COST_PER_BYTE_USD;
    }

    /**
     * Nearest-rank percentile. Works correctly for single-element lists.
     * @param sorted ascending-sorted list of values
     * @param p      percentile in range [0, 100]
     */
    static double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0.0;
        if (sorted.size() == 1) return sorted.get(0);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /** Returns the top-N most expensive requests (by cost descending). */
    private List<RequestMetrics> topN(List<RequestMetrics> entries, int n) {
        List<RequestMetrics> copy = new ArrayList<>(entries);
        Collections.sort(copy, new Comparator<RequestMetrics>() {
            @Override
            public int compare(RequestMetrics a, RequestMetrics b) {
                return Double.compare(costForRequest(b), costForRequest(a)); // descending
            }
        });
        return copy.subList(0, Math.min(n, copy.size()));
    }
}
