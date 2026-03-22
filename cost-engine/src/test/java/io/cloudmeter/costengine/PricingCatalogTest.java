package io.cloudmeter.costengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PricingCatalogTest {

    @ParameterizedTest
    @EnumSource(CloudProvider.class)
    void getInstances_returnsNonEmptyList(CloudProvider provider) {
        List<InstanceType> instances = PricingCatalog.getInstances(provider, "any-region");
        assertFalse(instances.isEmpty(), "catalog should have instances for " + provider);
    }

    @ParameterizedTest
    @EnumSource(CloudProvider.class)
    void getInstances_sortedByHourlyUsdAscending(CloudProvider provider) {
        List<InstanceType> instances = PricingCatalog.getInstances(provider, "any-region");
        for (int i = 1; i < instances.size(); i++) {
            assertTrue(instances.get(i - 1).getHourlyUsd() <= instances.get(i).getHourlyUsd(),
                    "instances must be sorted by hourlyUsd asc at index " + i);
        }
    }

    @ParameterizedTest
    @EnumSource(CloudProvider.class)
    void getInstances_allHavePositiveVcpuAndMemory(CloudProvider provider) {
        for (InstanceType inst : PricingCatalog.getInstances(provider, "any-region")) {
            assertTrue(inst.getVcpu() > 0,      inst.getName() + " vcpu must be > 0");
            assertTrue(inst.getMemoryGib() > 0, inst.getName() + " memory must be > 0");
            assertTrue(inst.getHourlyUsd() > 0, inst.getName() + " hourlyUsd must be > 0");
        }
    }

    @Test
    void getInstances_nullProvider_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> PricingCatalog.getInstances(null, "us-east-1"));
    }

    @ParameterizedTest
    @EnumSource(CloudProvider.class)
    void getEgressRatePerGib_returnsPositive(CloudProvider provider) {
        double rate = PricingCatalog.getEgressRatePerGib(provider, "any");
        assertTrue(rate > 0, "egress rate must be > 0 for " + provider);
    }

    @Test
    void getEgressRatePerGib_nullProvider_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> PricingCatalog.getEgressRatePerGib(null, "us-east-1"));
    }

    @Test
    void awsEgressRate_matchesExpected() {
        assertEquals(0.09, PricingCatalog.getEgressRatePerGib(CloudProvider.AWS, "us-east-1"), 1e-6);
    }

    @Test
    void gcpEgressRate_matchesExpected() {
        assertEquals(0.085, PricingCatalog.getEgressRatePerGib(CloudProvider.GCP, "us-central1"), 1e-6);
    }

    @Test
    void azureEgressRate_matchesExpected() {
        assertEquals(0.087, PricingCatalog.getEgressRatePerGib(CloudProvider.AZURE, "eastus"), 1e-6);
    }

    @Test
    void awsCatalog_containsExpectedInstances() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.AWS, "us-east-1");
        assertTrue(instances.stream().anyMatch(i -> "t3.micro".equals(i.getName())));
        assertTrue(instances.stream().anyMatch(i -> "m5.xlarge".equals(i.getName())));
        assertTrue(instances.stream().anyMatch(i -> "c5.2xlarge".equals(i.getName())));
    }

    @Test
    void gcpCatalog_containsExpectedInstances() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.GCP, "us-central1");
        assertTrue(instances.stream().anyMatch(i -> "e2-standard-4".equals(i.getName())));
        assertTrue(instances.stream().anyMatch(i -> "n2-standard-8".equals(i.getName())));
    }

    @Test
    void azureCatalog_containsExpectedInstances() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.AZURE, "eastus");
        assertTrue(instances.stream().anyMatch(i -> "D4s_v5".equals(i.getName())));
        assertTrue(instances.stream().anyMatch(i -> "F8s_v2".equals(i.getName())));
    }

    @Test
    void pricingDate_isSet() {
        assertNotNull(PricingCatalog.PRICING_DATE);
        assertFalse(PricingCatalog.PRICING_DATE.isEmpty());
    }

    @Test
    void getInstances_returnsUnmodifiableList() {
        List<InstanceType> instances = PricingCatalog.getInstances(CloudProvider.AWS, "us-east-1");
        assertThrows(UnsupportedOperationException.class,
                () -> instances.add(new InstanceType("fake", CloudProvider.AWS, 1, 1, 0.01)));
    }

    // ── Live-pricing overlay ───────────────────────────────────────────────────

    @org.junit.jupiter.api.AfterEach
    void resetLive() {
        PricingCatalog.resetLivePricing();
    }

    @Test
    void getPricingDate_beforeLiveUpdate_returnsStaticDate() {
        assertEquals(PricingCatalog.PRICING_DATE, PricingCatalog.getPricingDate());
    }

    @Test
    void isLive_beforeLiveUpdate_returnsFalse() {
        assertFalse(PricingCatalog.isLive());
    }

    @Test
    void applyLivePricing_thenGetPricingDate_returnsLiveDate() {
        PricingCatalog.applyLivePricing("2099-01-01",
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap());
        assertEquals("2099-01-01", PricingCatalog.getPricingDate());
    }

    @Test
    void applyLivePricing_thenIsLive_returnsTrue() {
        PricingCatalog.applyLivePricing("2099-01-01",
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap());
        assertTrue(PricingCatalog.isLive());
    }

    @Test
    void applyLivePricing_overridesInstancesForProvider() {
        java.util.Map<CloudProvider, List<InstanceType>> liveInstances =
                new java.util.EnumMap<>(CloudProvider.class);
        List<InstanceType> liveAws = List.of(new InstanceType("t4g.nano", CloudProvider.AWS, 2, 0.5, 0.0042));
        liveInstances.put(CloudProvider.AWS, liveAws);

        PricingCatalog.applyLivePricing("2099-01-01", liveInstances,
                java.util.Collections.emptyMap());

        List<InstanceType> result = PricingCatalog.getInstances(CloudProvider.AWS, "us-east-1");
        assertEquals(1, result.size());
        assertEquals("t4g.nano", result.get(0).getName());
    }

    @Test
    void applyLivePricing_overridesEgressRate() {
        java.util.Map<CloudProvider, Double> liveEgress = new java.util.EnumMap<>(CloudProvider.class);
        liveEgress.put(CloudProvider.AWS, 0.05);

        PricingCatalog.applyLivePricing("2099-01-01",
                java.util.Collections.emptyMap(), liveEgress);

        assertEquals(0.05, PricingCatalog.getEgressRatePerGib(CloudProvider.AWS, "us-east-1"), 1e-9);
    }

    @Test
    void getInstances_liveMapMissingProvider_fallsBackToStatic() {
        // Apply live pricing for AWS only — GCP should still return static data
        java.util.Map<CloudProvider, List<InstanceType>> partial = new java.util.EnumMap<>(CloudProvider.class);
        partial.put(CloudProvider.AWS, List.of());
        PricingCatalog.applyLivePricing("2099-01-01", partial, java.util.Collections.emptyMap());

        // GCP not in live map — must still return static instances
        assertFalse(PricingCatalog.getInstances(CloudProvider.GCP, "us-central1").isEmpty());
    }

    @Test
    void getEgressRatePerGib_liveMapMissingProvider_fallsBackToStatic() {
        // Apply live egress for AWS only
        java.util.Map<CloudProvider, Double> partialEgress = new java.util.EnumMap<>(CloudProvider.class);
        partialEgress.put(CloudProvider.AWS, 0.05);
        PricingCatalog.applyLivePricing("2099-01-01",
                java.util.Collections.emptyMap(), partialEgress);

        // GCP not in live egress map — must return static rate
        assertEquals(PricingCatalog.GCP_EGRESS_RATE_PER_GIB,
                PricingCatalog.getEgressRatePerGib(CloudProvider.GCP, "us-central1"), 1e-9);
    }
}
