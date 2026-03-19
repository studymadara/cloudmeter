package io.cloudmeter.costengine;

/**
 * One point on the endpoint cost curve: projected monthly cost at a given concurrent user count.
 * The full curve (12 points, 100 → 1 M users) is included in every {@link EndpointCostProjection}.
 */
public final class ScalePoint {

    private final int    concurrentUsers;
    private final double monthlyCostUsd;

    public ScalePoint(int concurrentUsers, double monthlyCostUsd) {
        if (concurrentUsers <= 0) throw new IllegalArgumentException("concurrentUsers must be > 0");
        if (monthlyCostUsd < 0)   throw new IllegalArgumentException("monthlyCostUsd must be >= 0");
        this.concurrentUsers = concurrentUsers;
        this.monthlyCostUsd  = monthlyCostUsd;
    }

    public int    getConcurrentUsers() { return concurrentUsers; }
    public double getMonthlyCostUsd()  { return monthlyCostUsd; }

    @Override
    public String toString() {
        return "ScalePoint{users=" + concurrentUsers + ", cost=$" + String.format("%.2f", monthlyCostUsd) + "/mo}";
    }
}
