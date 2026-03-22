package io.cloudmeter.starter;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.costengine.CostProjector;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.ProjectionConfig;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint that exposes CloudMeter cost projections.
 *
 * <p>Accessible at {@code /actuator/cloudmeter} when Spring Boot Actuator is on
 * the classpath and the endpoint is exposed:
 * <pre>
 * management.endpoints.web.exposure.include=cloudmeter
 * </pre>
 *
 * <p>Returns the same JSON structure as the CloudMeter dashboard
 * {@code /api/projections} endpoint so tooling can consume either.
 */
@Endpoint(id = "cloudmeter")
public class CloudMeterEndpoint {

    private final MetricsStore store;
    private final ProjectionConfig config;

    CloudMeterEndpoint(MetricsStore store, ProjectionConfig config) {
        this.store  = store;
        this.config = config;
    }

    @ReadOperation
    public Map<String, Object> projections() {
        List<EndpointCostProjection> projs =
                CostProjector.project(store.getAll(), config);

        List<Map<String, Object>> projList = new ArrayList<>();
        double totalMonthly = 0.0;
        boolean anyExceeds = false;

        for (EndpointCostProjection p : projs) {
            if (p.isExceedsBudget()) anyExceeds = true;
            totalMonthly += p.getProjectedMonthlyCostUsd();

            Map<String, Object> entry = new HashMap<>();
            entry.put("route",                    p.getRouteTemplate());
            entry.put("observedRps",              p.getObservedRps());
            entry.put("projectedRps",             p.getProjectedRps());
            entry.put("projectedMonthlyCostUsd",  p.getProjectedMonthlyCostUsd());
            entry.put("projectedCostPerUserUsd",  p.getProjectedCostPerUserUsd());
            entry.put("recommendedInstance",      p.getRecommendedInstance().getName());
            entry.put("medianDurationMs",         p.getMedianDurationMs());
            entry.put("medianCpuMs",              p.getMedianCpuCoreSecondsPerReq() * 1000.0);
            entry.put("exceedsBudget",            p.isExceedsBudget());
            projList.add(entry);
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalProjectedMonthlyCostUsd", totalMonthly);
        summary.put("anyExceedsBudget", anyExceeds);

        Map<String, Object> result = new HashMap<>();
        result.put("projections", projList);
        result.put("summary", summary);
        return result;
    }
}
