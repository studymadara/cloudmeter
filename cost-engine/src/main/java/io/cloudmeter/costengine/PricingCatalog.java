package io.cloudmeter.costengine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Static cloud pricing catalog embedded in the agent JAR.
 *
 * Prices are public on-demand (pay-as-you-go) Linux rates as of 2025-01-01.
 * Quarterly review is recommended; update the date constant when refreshing.
 *
 * Regions covered:
 *   AWS   — us-east-1 (N. Virginia); used as fallback for all AWS regions
 *   GCP   — us-central1 (Iowa);      used as fallback for all GCP regions
 *   Azure — eastus (East US);         used as fallback for all Azure regions
 *
 * ADR-003: no live cloud API calls — credentials are never required.
 */
public final class PricingCatalog {

    /** Date the embedded pricing tables were last verified against public pricing pages. */
    public static final String PRICING_DATE = "2025-01-01";

    // ── Live-pricing overlay ───────────────────────────────────────────────────
    // If fetchPrices=true and the fetch succeeds, these replace the compile-time defaults.

    private static volatile String                              liveDate      = null;
    private static volatile Map<CloudProvider, List<InstanceType>> liveInstances = null;
    private static volatile Map<CloudProvider, Double>             liveEgress    = null;

    /**
     * Returns the effective pricing date — live if a successful fetch has been applied,
     * otherwise the embedded static date.
     */
    public static String getPricingDate() {
        String d = liveDate;
        return d != null ? d : PRICING_DATE;
    }

    /**
     * Applies live-fetched pricing data. Called by {@code LivePricingFetcher} on success.
     * Thread-safe via volatile writes; reads in {@link #getInstances} and
     * {@link #getEgressRatePerGib} will see the new values immediately.
     *
     * @param date      pricing date string (e.g. "2026-03-22")
     * @param instances provider → sorted list of InstanceType
     * @param egress    provider → egress rate per GiB
     */
    public static void applyLivePricing(String date,
                                        Map<CloudProvider, List<InstanceType>> instances,
                                        Map<CloudProvider, Double> egress) {
        liveInstances = instances;
        liveEgress    = egress;
        liveDate      = date;   // write last — readers see consistent state once date is set
    }

    /** True if a live pricing update has been successfully applied. */
    public static boolean isLive() { return liveDate != null; }

    /** Hours in an average calendar month (730 = 365 days × 24 hrs / 12 months). */
    public static final double HOURS_PER_MONTH = 730.0;

    // ── AWS EC2 us-east-1  (on-demand, Linux) ────────────────────────────────

    private static final List<InstanceType> AWS_INSTANCES;

    static {
        List<InstanceType> list = new ArrayList<>();
        // t3 burstable — cost-efficient for variable workloads
        list.add(new InstanceType("t3.nano",    CloudProvider.AWS,  2,  0.5,  0.0052));
        list.add(new InstanceType("t3.micro",   CloudProvider.AWS,  2,  1,    0.0104));
        list.add(new InstanceType("t3.small",   CloudProvider.AWS,  2,  2,    0.0208));
        list.add(new InstanceType("t3.medium",  CloudProvider.AWS,  2,  4,    0.0416));
        list.add(new InstanceType("t3.large",   CloudProvider.AWS,  2,  8,    0.0832));
        list.add(new InstanceType("t3.xlarge",  CloudProvider.AWS,  4,  16,   0.1664));
        list.add(new InstanceType("t3.2xlarge", CloudProvider.AWS,  8,  32,   0.3328));
        // m5 general purpose — balanced CPU/memory
        list.add(new InstanceType("m5.large",   CloudProvider.AWS,  2,  8,    0.0960));
        list.add(new InstanceType("m5.xlarge",  CloudProvider.AWS,  4,  16,   0.1920));
        list.add(new InstanceType("m5.2xlarge", CloudProvider.AWS,  8,  32,   0.3840));
        list.add(new InstanceType("m5.4xlarge", CloudProvider.AWS,  16, 64,   0.7680));
        list.add(new InstanceType("m5.8xlarge", CloudProvider.AWS,  32, 128,  1.5360));
        // c5 compute optimised — highest CPU performance per $
        list.add(new InstanceType("c5.large",   CloudProvider.AWS,  2,  4,    0.0850));
        list.add(new InstanceType("c5.xlarge",  CloudProvider.AWS,  4,  8,    0.1700));
        list.add(new InstanceType("c5.2xlarge", CloudProvider.AWS,  8,  16,   0.3400));
        list.add(new InstanceType("c5.4xlarge", CloudProvider.AWS,  16, 32,   0.6800));
        list.add(new InstanceType("c5.9xlarge", CloudProvider.AWS,  36, 72,   1.5300));
        // r5 memory optimised
        list.add(new InstanceType("r5.large",   CloudProvider.AWS,  2,  16,   0.1260));
        list.add(new InstanceType("r5.xlarge",  CloudProvider.AWS,  4,  32,   0.2520));
        list.add(new InstanceType("r5.2xlarge", CloudProvider.AWS,  8,  64,   0.5040));

        // Sort by hourlyUsd ascending — used by instance selector
        Collections.sort(list, (a, b) -> Double.compare(a.getHourlyUsd(), b.getHourlyUsd()));
        AWS_INSTANCES = Collections.unmodifiableList(list);
    }

    /** AWS egress to internet (us-east-1, first 10 TB/month, per GiB). */
    public static final double AWS_EGRESS_RATE_PER_GIB = 0.09;

    // ── GCP Compute Engine us-central1  (on-demand, Linux) ───────────────────

    private static final List<InstanceType> GCP_INSTANCES;

    static {
        List<InstanceType> list = new ArrayList<>();
        // e2 shared-core (fractional vCPU — modelled as 2 for selection purposes)
        list.add(new InstanceType("e2-micro",       CloudProvider.GCP,  2,  1,   0.0084));
        list.add(new InstanceType("e2-small",       CloudProvider.GCP,  2,  2,   0.0168));
        list.add(new InstanceType("e2-medium",      CloudProvider.GCP,  2,  4,   0.0335));
        // e2 standard
        list.add(new InstanceType("e2-standard-2",  CloudProvider.GCP,  2,  8,   0.0671));
        list.add(new InstanceType("e2-standard-4",  CloudProvider.GCP,  4,  16,  0.1341));
        list.add(new InstanceType("e2-standard-8",  CloudProvider.GCP,  8,  32,  0.2683));
        list.add(new InstanceType("e2-standard-16", CloudProvider.GCP,  16, 64,  0.5366));
        list.add(new InstanceType("e2-standard-32", CloudProvider.GCP,  32, 128, 1.0732));
        // n2 general purpose
        list.add(new InstanceType("n2-standard-2",  CloudProvider.GCP,  2,  8,   0.0971));
        list.add(new InstanceType("n2-standard-4",  CloudProvider.GCP,  4,  16,  0.1942));
        list.add(new InstanceType("n2-standard-8",  CloudProvider.GCP,  8,  32,  0.3883));
        list.add(new InstanceType("n2-standard-16", CloudProvider.GCP,  16, 64,  0.7766));
        // c2 compute optimised
        list.add(new InstanceType("c2-standard-4",  CloudProvider.GCP,  4,  16,  0.2088));
        list.add(new InstanceType("c2-standard-8",  CloudProvider.GCP,  8,  32,  0.4176));
        list.add(new InstanceType("c2-standard-16", CloudProvider.GCP,  16, 64,  0.8352));
        list.add(new InstanceType("c2-standard-30", CloudProvider.GCP,  30, 120, 1.5660));

        Collections.sort(list, (a, b) -> Double.compare(a.getHourlyUsd(), b.getHourlyUsd()));
        GCP_INSTANCES = Collections.unmodifiableList(list);
    }

    /** GCP egress to internet (per GiB, first 10 TB/month). */
    public static final double GCP_EGRESS_RATE_PER_GIB = 0.085;

    // ── Azure East US  (pay-as-you-go, Linux) ────────────────────────────────

    private static final List<InstanceType> AZURE_INSTANCES;

    static {
        List<InstanceType> list = new ArrayList<>();
        // B-series burstable
        list.add(new InstanceType("B1ms",    CloudProvider.AZURE, 1,  2,   0.0207));
        list.add(new InstanceType("B2s",     CloudProvider.AZURE, 2,  4,   0.0416));
        list.add(new InstanceType("B2ms",    CloudProvider.AZURE, 2,  8,   0.0832));
        list.add(new InstanceType("B4ms",    CloudProvider.AZURE, 4,  16,  0.1664));
        list.add(new InstanceType("B8ms",    CloudProvider.AZURE, 8,  32,  0.3328));
        list.add(new InstanceType("B16ms",   CloudProvider.AZURE, 16, 64,  0.6656));
        // D v5 general purpose
        list.add(new InstanceType("D2s_v5",  CloudProvider.AZURE, 2,  8,   0.0960));
        list.add(new InstanceType("D4s_v5",  CloudProvider.AZURE, 4,  16,  0.1920));
        list.add(new InstanceType("D8s_v5",  CloudProvider.AZURE, 8,  32,  0.3840));
        list.add(new InstanceType("D16s_v5", CloudProvider.AZURE, 16, 64,  0.7680));
        list.add(new InstanceType("D32s_v5", CloudProvider.AZURE, 32, 128, 1.5360));
        // F v2 compute optimised
        list.add(new InstanceType("F2s_v2",  CloudProvider.AZURE, 2,  4,   0.0850));
        list.add(new InstanceType("F4s_v2",  CloudProvider.AZURE, 4,  8,   0.1700));
        list.add(new InstanceType("F8s_v2",  CloudProvider.AZURE, 8,  16,  0.3400));
        list.add(new InstanceType("F16s_v2", CloudProvider.AZURE, 16, 32,  0.6800));
        list.add(new InstanceType("F32s_v2", CloudProvider.AZURE, 32, 64,  1.3600));

        Collections.sort(list, (a, b) -> Double.compare(a.getHourlyUsd(), b.getHourlyUsd()));
        AZURE_INSTANCES = Collections.unmodifiableList(list);
    }

    /** Azure outbound data transfer (per GiB, first 10 TB/month). */
    public static final double AZURE_EGRESS_RATE_PER_GIB = 0.087;

    // ── Lookup maps (EnumMap — no unreachable default branches) ──────────────

    private static final Map<CloudProvider, List<InstanceType>> INSTANCE_MAP;
    private static final Map<CloudProvider, Double>             EGRESS_MAP;

    static {
        Map<CloudProvider, List<InstanceType>> im = new EnumMap<>(CloudProvider.class);
        im.put(CloudProvider.AWS,   AWS_INSTANCES);
        im.put(CloudProvider.GCP,   GCP_INSTANCES);
        im.put(CloudProvider.AZURE, AZURE_INSTANCES);
        INSTANCE_MAP = Collections.unmodifiableMap(im);

        Map<CloudProvider, Double> em = new EnumMap<>(CloudProvider.class);
        em.put(CloudProvider.AWS,   AWS_EGRESS_RATE_PER_GIB);
        em.put(CloudProvider.GCP,   GCP_EGRESS_RATE_PER_GIB);
        em.put(CloudProvider.AZURE, AZURE_EGRESS_RATE_PER_GIB);
        EGRESS_MAP = Collections.unmodifiableMap(em);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    private PricingCatalog() {}

    /**
     * Returns the instance catalog for the given provider, sorted by hourly price ascending.
     * The {@code region} parameter is accepted but currently all providers use a single
     * representative region — regional multipliers are a v2 feature.
     *
     * @throws IllegalArgumentException if provider is null
     */
    public static List<InstanceType> getInstances(CloudProvider provider, String region) {
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        Map<CloudProvider, List<InstanceType>> live = liveInstances;
        if (live != null) {
            List<InstanceType> result = live.get(provider);
            if (result != null) return result;
        }
        return INSTANCE_MAP.get(provider);
    }

    /**
     * Returns the egress rate ($/GiB) for outbound internet data for the given provider.
     * Regional differences are a v2 feature; this returns the primary-region rate.
     *
     * @throws IllegalArgumentException if provider is null
     */
    public static double getEgressRatePerGib(CloudProvider provider, String region) {
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        Map<CloudProvider, Double> live = liveEgress;
        if (live != null) {
            Double rate = live.get(provider);
            if (rate != null) return rate;
        }
        return EGRESS_MAP.get(provider);
    }

    /** Package-private: resets the live overlay. Used in tests only. */
    static void resetLivePricing() {
        liveDate      = null;
        liveInstances = null;
        liveEgress    = null;
    }
}
