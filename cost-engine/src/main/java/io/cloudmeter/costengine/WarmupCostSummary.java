package io.cloudmeter.costengine;

/**
 * Aggregated cost of the JVM warmup period (first 30 seconds after agent attach).
 *
 * <p>Every time a JVM restarts — pod scale-out, rolling deploy, crash-restart — it
 * incurs this one-time cost before the JIT stabilises and metrics enter the normal
 * projection window.
 *
 * <p>Use {@link #getEstimatedColdStartCostUsd()} to understand the per-restart charge.
 * Multiply by {@code restarts × pods × 730} to get monthly cold-start spend.
 */
public final class WarmupCostSummary {

    /** Warmup window length in seconds (must match agent's WARMUP_SECONDS). */
    public static final int WARMUP_SECONDS = 30;

    private final int    requestCount;
    private final double totalCpuCoreSeconds;
    private final long   totalDurationMs;
    private final long   totalEgressBytes;
    private final double estimatedColdStartCostUsd;

    public WarmupCostSummary(int requestCount,
                             double totalCpuCoreSeconds,
                             long totalDurationMs,
                             long totalEgressBytes,
                             double estimatedColdStartCostUsd) {
        this.requestCount              = requestCount;
        this.totalCpuCoreSeconds       = totalCpuCoreSeconds;
        this.totalDurationMs           = totalDurationMs;
        this.totalEgressBytes          = totalEgressBytes;
        this.estimatedColdStartCostUsd = estimatedColdStartCostUsd;
    }

    /** {@code true} if any warmup-period requests were observed. */
    public boolean hasData() {
        return requestCount > 0;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public double getTotalCpuCoreSeconds() {
        return totalCpuCoreSeconds;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public long getTotalEgressBytes() {
        return totalEgressBytes;
    }

    /**
     * Estimated dollar cost of one JVM cold-start (30 s window).
     * Does NOT include the steady-state running cost — that is handled by the
     * endpoint projections.
     */
    public double getEstimatedColdStartCostUsd() {
        return estimatedColdStartCostUsd;
    }
}
