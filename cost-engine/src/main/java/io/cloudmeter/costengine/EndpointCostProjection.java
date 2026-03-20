package io.cloudmeter.costengine;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Cost projection result for a single API endpoint.
 *
 * <p>All cost figures use the <em>standalone</em> attribution model (ADR-009): they represent
 * what a server dedicated to <em>only</em> this endpoint at the projected load would cost.
 * Consequently, the sum of all endpoint projections will exceed the actual total bill.
 * This is intentional — it makes each endpoint directly comparable and actionable.
 */
public final class EndpointCostProjection {

    private final String        routeTemplate;
    private final double        observedRps;               // requests/s during the recording
    private final double        projectedRps;              // requests/s at target user count
    private final double        projectedMonthlyCostUsd;   // standalone compute + egress
    private final double        projectedCostPerUserUsd;   // monthlyCost / targetUsers
    private final InstanceType  recommendedInstance;       // cheapest instance meeting CPU need
    private final List<ScalePoint> costCurve;              // 12 points, 100 → 1 M users
    private final boolean       exceedsBudget;             // true if monthlyCost > budgetUsd
    private final double        medianDurationMs;           // median request duration in ms
    private final double        medianCpuCoreSecondsPerReq; // median CPU core-seconds per request
    private final double        medianThreadWaitRatio;      // median thread wait ratio (0.0–1.0)
    private final double        medianEgressBytesPerReq;    // median egress bytes per request

    public EndpointCostProjection(
            String routeTemplate,
            double observedRps,
            double projectedRps,
            double projectedMonthlyCostUsd,
            double projectedCostPerUserUsd,
            InstanceType recommendedInstance,
            List<ScalePoint> costCurve,
            boolean exceedsBudget,
            double medianDurationMs,
            double medianCpuCoreSecondsPerReq,
            double medianThreadWaitRatio,
            double medianEgressBytesPerReq) {

        this.routeTemplate              = Objects.requireNonNull(routeTemplate, "routeTemplate");
        this.recommendedInstance        = Objects.requireNonNull(recommendedInstance, "recommendedInstance");
        this.costCurve                  = Collections.unmodifiableList(
                Objects.requireNonNull(costCurve, "costCurve"));
        this.observedRps                = observedRps;
        this.projectedRps               = projectedRps;
        this.projectedMonthlyCostUsd    = projectedMonthlyCostUsd;
        this.projectedCostPerUserUsd    = projectedCostPerUserUsd;
        this.exceedsBudget              = exceedsBudget;
        this.medianDurationMs           = medianDurationMs;
        this.medianCpuCoreSecondsPerReq = medianCpuCoreSecondsPerReq;
        this.medianThreadWaitRatio      = medianThreadWaitRatio;
        this.medianEgressBytesPerReq    = medianEgressBytesPerReq;
    }

    public String        getRouteTemplate()               { return routeTemplate; }
    public double        getObservedRps()                  { return observedRps; }
    public double        getProjectedRps()                 { return projectedRps; }
    public double        getProjectedMonthlyCostUsd()      { return projectedMonthlyCostUsd; }
    public double        getProjectedCostPerUserUsd()      { return projectedCostPerUserUsd; }
    public InstanceType  getRecommendedInstance()          { return recommendedInstance; }
    public List<ScalePoint> getCostCurve()                 { return costCurve; }
    public boolean       isExceedsBudget()                 { return exceedsBudget; }
    public double        getMedianDurationMs()             { return medianDurationMs; }
    public double        getMedianCpuCoreSecondsPerReq()   { return medianCpuCoreSecondsPerReq; }
    public double        getMedianThreadWaitRatio()        { return medianThreadWaitRatio; }
    public double        getMedianEgressBytesPerReq()      { return medianEgressBytesPerReq; }

    @Override
    public String toString() {
        return "EndpointCostProjection{"
                + "route='" + routeTemplate + '\''
                + ", projectedRps=" + String.format("%.1f", projectedRps)
                + ", monthlyCost=$" + String.format("%.2f", projectedMonthlyCostUsd)
                + ", instance=" + recommendedInstance.getName()
                + ", medianDurationMs=" + String.format("%.1f", medianDurationMs)
                + ", medianCpuMs=" + String.format("%.3f", medianCpuCoreSecondsPerReq * 1000)
                + ", exceedsBudget=" + exceedsBudget
                + '}';
    }
}
