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
}
