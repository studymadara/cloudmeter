package io.cloudmeter.costengine;

import java.util.Objects;

/**
 * Immutable snapshot of a cloud VM instance type and its on-demand pricing.
 *
 * Prices are taken from public cloud pricing pages and embedded statically
 * (ADR-003 — no live cloud API calls). See {@link PricingCatalog} for the
 * update date and regions covered.
 */
public final class InstanceType {

    private final String        name;
    private final CloudProvider provider;
    private final double        vcpu;        // virtual CPUs (fractional for shared-core types)
    private final double        memoryGib;   // RAM in GiB
    private final double        hourlyUsd;   // on-demand $/hr, Linux, specific region

    public InstanceType(String name, CloudProvider provider,
                        double vcpu, double memoryGib, double hourlyUsd) {
        this.name      = Objects.requireNonNull(name, "name");
        this.provider  = Objects.requireNonNull(provider, "provider");
        if (vcpu <= 0)      throw new IllegalArgumentException("vcpu must be > 0");
        if (memoryGib <= 0) throw new IllegalArgumentException("memoryGib must be > 0");
        if (hourlyUsd < 0)  throw new IllegalArgumentException("hourlyUsd must be >= 0");
        this.vcpu      = vcpu;
        this.memoryGib = memoryGib;
        this.hourlyUsd = hourlyUsd;
    }

    public String        getName()      { return name; }
    public CloudProvider getProvider()  { return provider; }
    public double        getVcpu()      { return vcpu; }
    public double        getMemoryGib() { return memoryGib; }
    public double        getHourlyUsd() { return hourlyUsd; }

    /** Monthly cost assuming 730 hours/month (average calendar month). */
    public double getMonthlyUsd() {
        return hourlyUsd * 730.0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InstanceType)) return false;
        InstanceType that = (InstanceType) o;
        return Double.compare(that.vcpu,      vcpu)      == 0
            && Double.compare(that.memoryGib, memoryGib) == 0
            && Double.compare(that.hourlyUsd, hourlyUsd) == 0
            && name.equals(that.name)
            && provider == that.provider;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, provider, vcpu, memoryGib, hourlyUsd);
    }

    @Override
    public String toString() {
        return provider + ":" + name + "(" + vcpu + "vCPU," + memoryGib + "GiB,$" + hourlyUsd + "/hr)";
    }
}
