package io.cloudmeter.costengine;

import java.util.Objects;

/**
 * Immutable configuration for a cost projection run.
 *
 * Mirrors the {@code cloudmeter.yaml} settings that drive scaling calculations.
 * Build with {@link Builder}:
 *
 * <pre>
 *   ProjectionConfig config = ProjectionConfig.builder()
 *       .provider(CloudProvider.AWS)
 *       .region("us-east-1")
 *       .targetUsers(10_000)
 *       .requestsPerUserPerSecond(0.5)
 *       .recordingDurationSeconds(120.0)
 *       .budgetUsd(500.0)
 *       .build();
 * </pre>
 */
public final class ProjectionConfig {

    private final CloudProvider provider;
    private final String        region;
    private final int           targetUsers;
    private final double        requestsPerUserPerSecond;
    private final double        recordingDurationSeconds;
    private final double        budgetUsd;   // 0 = no budget gate

    private ProjectionConfig(Builder b) {
        this.provider                 = Objects.requireNonNull(b.provider, "provider");
        this.region                   = Objects.requireNonNull(b.region, "region");
        this.targetUsers              = b.targetUsers;
        this.requestsPerUserPerSecond = b.requestsPerUserPerSecond;
        this.recordingDurationSeconds = b.recordingDurationSeconds;
        this.budgetUsd                = b.budgetUsd;

        if (targetUsers <= 0)               throw new IllegalArgumentException("targetUsers must be > 0");
        if (requestsPerUserPerSecond <= 0)  throw new IllegalArgumentException("requestsPerUserPerSecond must be > 0");
        if (recordingDurationSeconds <= 0)  throw new IllegalArgumentException("recordingDurationSeconds must be > 0");
        if (budgetUsd < 0)                  throw new IllegalArgumentException("budgetUsd must be >= 0");
    }

    public CloudProvider getProvider()                { return provider; }
    public String        getRegion()                  { return region; }
    public int           getTargetUsers()             { return targetUsers; }
    public double        getRequestsPerUserPerSecond() { return requestsPerUserPerSecond; }
    public double        getRecordingDurationSeconds() { return recordingDurationSeconds; }
    public double        getBudgetUsd()               { return budgetUsd; }

    /** Convenience: target total RPS = targetUsers × requestsPerUserPerSecond. */
    public double getTargetTotalRps() {
        return targetUsers * requestsPerUserPerSecond;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CloudProvider provider;
        private String        region                   = "us-east-1";
        private int           targetUsers              = 1_000;
        private double        requestsPerUserPerSecond = 0.5;
        private double        recordingDurationSeconds = 60.0;
        private double        budgetUsd                = 0.0;

        private Builder() {}

        public Builder provider(CloudProvider v)                { this.provider                 = v; return this; }
        public Builder region(String v)                         { this.region                   = v; return this; }
        public Builder targetUsers(int v)                       { this.targetUsers              = v; return this; }
        public Builder requestsPerUserPerSecond(double v)       { this.requestsPerUserPerSecond = v; return this; }
        public Builder recordingDurationSeconds(double v)       { this.recordingDurationSeconds = v; return this; }
        public Builder budgetUsd(double v)                      { this.budgetUsd                = v; return this; }

        public ProjectionConfig build() { return new ProjectionConfig(this); }
    }
}
