package io.cloudmeter.collector;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated cost statistics for a single route template.
 * Produced by {@link RouteStatsCalculator}.
 */
public final class RouteStats {

    /** Variance ratio threshold above which a warning is surfaced. */
    public static final double VARIANCE_WARNING_THRESHOLD = 1.5;

    private final String              routeTemplate;
    private final long                requestCount;
    private final double              p50CostUsd;
    private final double              p95CostUsd;
    private final double              p99CostUsd;
    private final double              maxCostUsd;
    private final double              varianceRatio;    // p95 / p50; NaN if p50 == 0
    private final List<RequestMetrics> outliers;        // top-N most expensive calls

    public RouteStats(String routeTemplate, long requestCount,
                      double p50CostUsd, double p95CostUsd, double p99CostUsd,
                      double maxCostUsd, List<RequestMetrics> outliers) {
        this.routeTemplate = Objects.requireNonNull(routeTemplate, "routeTemplate");
        this.requestCount  = requestCount;
        this.p50CostUsd    = p50CostUsd;
        this.p95CostUsd    = p95CostUsd;
        this.p99CostUsd    = p99CostUsd;
        this.maxCostUsd    = maxCostUsd;
        this.varianceRatio = (p50CostUsd == 0.0) ? Double.NaN : p95CostUsd / p50CostUsd;
        this.outliers      = Collections.unmodifiableList(
                Objects.requireNonNull(outliers, "outliers"));
    }

    public String              getRouteTemplate()  { return routeTemplate; }
    public long                getRequestCount()   { return requestCount; }
    public double              getP50CostUsd()     { return p50CostUsd; }
    public double              getP95CostUsd()     { return p95CostUsd; }
    public double              getP99CostUsd()     { return p99CostUsd; }
    public double              getMaxCostUsd()     { return maxCostUsd; }
    public double              getVarianceRatio()  { return varianceRatio; }
    public List<RequestMetrics> getOutliers()      { return outliers; }

    public boolean hasVarianceWarning() {
        return !Double.isNaN(varianceRatio) && varianceRatio > VARIANCE_WARNING_THRESHOLD;
    }

    @Override
    public String toString() {
        return "RouteStats{"
            + "route='"       + routeTemplate + '\''
            + ", count="      + requestCount
            + ", p50=$"       + String.format("%.2f", p50CostUsd)
            + ", p95=$"       + String.format("%.2f", p95CostUsd)
            + ", variance="   + String.format("%.2f", varianceRatio)
            + ", warning="    + hasVarianceWarning()
            + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RouteStats)) return false;
        RouteStats that = (RouteStats) o;
        return requestCount == that.requestCount
            && Double.compare(p50CostUsd,   that.p50CostUsd)   == 0
            && Double.compare(p95CostUsd,   that.p95CostUsd)   == 0
            && Double.compare(p99CostUsd,   that.p99CostUsd)   == 0
            && Double.compare(maxCostUsd,   that.maxCostUsd)   == 0
            && routeTemplate.equals(that.routeTemplate)
            && outliers.equals(that.outliers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(routeTemplate, requestCount, p50CostUsd,
                p95CostUsd, p99CostUsd, maxCostUsd);
    }
}
