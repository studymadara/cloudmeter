package io.cloudmeter.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for CloudMeter Spring Boot integration.
 *
 * <p>All properties are prefixed with {@code spring.cloudmeter}. Example:
 * <pre>
 * spring.cloudmeter.provider=AWS
 * spring.cloudmeter.target-users=5000
 * spring.cloudmeter.budget-usd=200
 * </pre>
 *
 * <p>For WebFlux applications, no {@code -javaagent} flag is required — the
 * starter's {@link CloudMeterWebFilter} collects metrics automatically.
 * For Spring MVC applications, the {@code -javaagent} flag is still required;
 * this starter adds Actuator integration on top.
 *
 * <p><strong>Note:</strong> CloudMeter is a development and staging tool.
 * Activate only in {@code dev} or {@code test} profiles.
 */
@ConfigurationProperties(prefix = "spring.cloudmeter")
public class CloudMeterProperties {

    /** Whether CloudMeter integration is enabled. Default: {@code true}. */
    private boolean enabled = true;

    /** Cloud provider for cost projections. Default: {@code AWS}. */
    private String provider = "AWS";

    /** Cloud region for pricing. Default: {@code us-east-1}. */
    private String region = "us-east-1";

    /** Target concurrent user count for cost projections. Default: {@code 1000}. */
    private int targetUsers = 1_000;

    /** Assumed requests per user per second. Default: {@code 1.0}. */
    private double requestsPerUserPerSecond = 1.0;

    /** Monthly budget in USD — endpoints exceeding this are flagged. {@code 0} disables. */
    private double budgetUsd = 0;

    /** Max request metrics retained in memory. Default: {@code 100000}. */
    private int capacity = 100_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public int getTargetUsers() { return targetUsers; }
    public void setTargetUsers(int targetUsers) { this.targetUsers = targetUsers; }

    public double getRequestsPerUserPerSecond() { return requestsPerUserPerSecond; }
    public void setRequestsPerUserPerSecond(double requestsPerUserPerSecond) {
        this.requestsPerUserPerSecond = requestsPerUserPerSecond;
    }

    public double getBudgetUsd() { return budgetUsd; }
    public void setBudgetUsd(double budgetUsd) { this.budgetUsd = budgetUsd; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
}
