package io.cloudmeter.costengine;

import io.cloudmeter.collector.RequestMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects monthly cloud cost per API endpoint from a list of observed {@link RequestMetrics}.
 *
 * <h2>Projection formula (ADR-006 — linear scaling model)</h2>
 * <ol>
 *   <li><b>Observe</b>: median CPU core-seconds and median egress bytes per request, per route.</li>
 *   <li><b>Scale</b>: linearly from observed RPS to target RPS using the ratio
 *       {@code targetTotalRps / totalObservedRps}.</li>
 *   <li><b>Select instance</b>: cheapest instance where
 *       {@code vcpu >= projectedRps × medianCpuCoreSecondsPerRequest}.</li>
 *   <li><b>Price</b>: {@code instance.hourlyUsd × 730 + egressGibPerMonth × egressRatePerGib}.</li>
 *   <li><b>Cost curve</b>: repeat steps 2–4 at 12 scale points (100 → 1 M concurrent users).</li>
 * </ol>
 *
 * <h2>Standalone attribution (ADR-009)</h2>
 * Each endpoint's cost is computed as if a dedicated server served only that endpoint.
 * Consequently the sum of all projections may exceed the actual server bill — this is
 * intentional and documented to users on the dashboard.
 *
 * <h2>Accuracy caveat</h2>
 * Projections target ±20% of the actual monthly bill at the stated scale. Thread-state
 * sampling is probabilistic and instance selection assumes continuous (non-burstable)
 * CPU utilisation.
 */
public final class CostProjector {

    /** GiB in bytes. */
    static final double GIB_IN_BYTES = 1_073_741_824.0;

    /**
     * Scale points used for the cost curve (concurrent user counts).
     * These 12 points form the x-axis of the dashboard cost graph.
     */
    public static final int[] SCALE_USERS = {
        100, 200, 500, 1_000, 2_000, 5_000,
        10_000, 20_000, 50_000, 100_000, 500_000, 1_000_000
    };

    private CostProjector() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Projects the cost for every distinct route in {@code metrics}.
     *
     * @param metrics  all buffered request metrics (warmup entries are automatically excluded)
     * @param config   projection parameters (provider, target users, scale)
     * @return one {@link EndpointCostProjection} per route, sorted by projected monthly cost
     *         descending (most expensive first)
     */
    public static List<EndpointCostProjection> project(
            List<RequestMetrics> metrics,
            ProjectionConfig config) {

        List<RequestMetrics> live = filterWarmup(metrics);
        if (live.isEmpty()) return Collections.emptyList();

        double totalObservedRps = computeTotalRps(live, config.getRecordingDurationSeconds());
        // Guard: treat zero observed RPS as 1 to avoid division by zero
        double safeObservedRps = totalObservedRps > 0 ? totalObservedRps : 1.0;

        List<InstanceType> instances    = PricingCatalog.getInstances(config.getProvider(), config.getRegion());
        double             egressPerGib = PricingCatalog.getEgressRatePerGib(config.getProvider(), config.getRegion());

        Map<String, List<RequestMetrics>> byRoute = groupByRoute(live);
        List<EndpointCostProjection> results = new ArrayList<>(byRoute.size());

        for (Map.Entry<String, List<RequestMetrics>> entry : byRoute.entrySet()) {
            results.add(projectRoute(
                    entry.getKey(), entry.getValue(),
                    safeObservedRps, config,
                    instances, egressPerGib));
        }

        Collections.sort(results, new Comparator<EndpointCostProjection>() {
            @Override
            public int compare(EndpointCostProjection a, EndpointCostProjection b) {
                return Double.compare(b.getProjectedMonthlyCostUsd(), a.getProjectedMonthlyCostUsd());
            }
        });
        return results;
    }

    // ── Per-route projection ──────────────────────────────────────────────────

    private static EndpointCostProjection projectRoute(
            String route,
            List<RequestMetrics> entries,
            double totalObservedRps,
            ProjectionConfig config,
            List<InstanceType> instances,
            double egressRatePerGib) {

        double observedRps    = entries.size() / config.getRecordingDurationSeconds();
        double scaleFactor    = config.getTargetTotalRps() / totalObservedRps;
        double projectedRps   = observedRps * scaleFactor;

        double medianCpu    = median(extractCpu(entries));
        double medianEgress = median(extractEgress(entries));

        double projectedMonthlyCost = computeMonthlyCost(
                projectedRps, medianCpu, medianEgress, instances, egressRatePerGib);

        double costPerUser       = projectedMonthlyCost / config.getTargetUsers();
        InstanceType recommended = selectInstance(projectedRps * medianCpu, instances);

        List<ScalePoint> costCurve = buildCostCurve(
                observedRps, totalObservedRps, medianCpu, medianEgress,
                config, instances, egressRatePerGib);

        boolean exceedsBudget = config.getBudgetUsd() > 0
                && projectedMonthlyCost > config.getBudgetUsd();

        return new EndpointCostProjection(
                route, observedRps, projectedRps,
                projectedMonthlyCost, costPerUser,
                recommended, costCurve, exceedsBudget);
    }

    // ── Cost curve ────────────────────────────────────────────────────────────

    private static List<ScalePoint> buildCostCurve(
            double observedEndpointRps,
            double totalObservedRps,
            double medianCpu,
            double medianEgress,
            ProjectionConfig config,
            List<InstanceType> instances,
            double egressRatePerGib) {

        List<ScalePoint> curve = new ArrayList<>(SCALE_USERS.length);
        for (int scaleUsers : SCALE_USERS) {
            double scaleTargetRps = scaleUsers * config.getRequestsPerUserPerSecond();
            double scaledRps      = observedEndpointRps * (scaleTargetRps / totalObservedRps);
            double scaledCost     = computeMonthlyCost(
                    scaledRps, medianCpu, medianEgress, instances, egressRatePerGib);
            curve.add(new ScalePoint(scaleUsers, scaledCost));
        }
        return curve;
    }

    // ── Monthly cost formula ──────────────────────────────────────────────────

    /**
     * Computes monthly cost for an endpoint at the given projected RPS.
     *
     * <pre>
     *   compute = instance.hourlyUsd × HOURS_PER_MONTH
     *   egress  = projectedRps × medianEgressBytes × secondsPerMonth / GiB × egressRate
     *   total   = compute + egress
     * </pre>
     */
    static double computeMonthlyCost(
            double projectedRps,
            double medianCpuCoreSeconds,
            double medianEgressBytes,
            List<InstanceType> instances,
            double egressRatePerGib) {

        double requiredCores  = projectedRps * medianCpuCoreSeconds;
        InstanceType instance = selectInstance(requiredCores, instances);

        double secondsPerMonth   = PricingCatalog.HOURS_PER_MONTH * 3600.0;
        double egressGibPerMonth = projectedRps * medianEgressBytes * secondsPerMonth / GIB_IN_BYTES;

        return instance.getHourlyUsd() * PricingCatalog.HOURS_PER_MONTH
             + egressGibPerMonth * egressRatePerGib;
    }

    /**
     * Selects the cheapest instance type whose vCPU count meets the continuous
     * CPU requirement. Returns the largest available instance if none is sufficient
     * (handles extreme scale projections gracefully).
     *
     * <p>Instances must be pre-sorted by hourly price ascending (as returned by
     * {@link PricingCatalog#getInstances}).
     */
    static InstanceType selectInstance(double requiredCores, List<InstanceType> instances) {
        for (InstanceType inst : instances) {
            if (inst.getVcpu() >= requiredCores) {
                return inst;
            }
        }
        // requiredCores exceeds every instance in the catalog — return the largest
        return instances.get(instances.size() - 1);
    }

    // ── Statistical helpers ───────────────────────────────────────────────────

    /**
     * Nearest-rank median of a sorted list. Returns 0 if the list is empty.
     * The input list is sorted in-place.
     */
    static double median(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        Collections.sort(values);
        int mid = values.size() / 2;
        // For even-length lists use the lower middle (consistent with nearest-rank p50)
        return values.size() % 2 == 0
                ? (values.get(mid - 1) + values.get(mid)) / 2.0
                : values.get(mid);
    }

    // ── Data extraction helpers ───────────────────────────────────────────────

    private static List<Double> extractCpu(List<RequestMetrics> entries) {
        List<Double> result = new ArrayList<>(entries.size());
        for (RequestMetrics m : entries) {
            result.add(m.getCpuCoreSeconds());
        }
        return result;
    }

    private static List<Double> extractEgress(List<RequestMetrics> entries) {
        List<Double> result = new ArrayList<>(entries.size());
        for (RequestMetrics m : entries) {
            result.add((double) m.getEgressBytes());
        }
        return result;
    }

    private static List<RequestMetrics> filterWarmup(List<RequestMetrics> metrics) {
        List<RequestMetrics> result = new ArrayList<>();
        for (RequestMetrics m : metrics) {
            if (!m.isWarmup()) result.add(m);
        }
        return result;
    }

    private static double computeTotalRps(List<RequestMetrics> live, double durationSeconds) {
        return live.size() / durationSeconds;
    }

    private static Map<String, List<RequestMetrics>> groupByRoute(List<RequestMetrics> metrics) {
        Map<String, List<RequestMetrics>> map = new HashMap<>();
        for (RequestMetrics m : metrics) {
            List<RequestMetrics> list = map.get(m.getRouteTemplate());
            if (list == null) {
                list = new ArrayList<>();
                map.put(m.getRouteTemplate(), list);
            }
            list.add(m);
        }
        return map;
    }
}
