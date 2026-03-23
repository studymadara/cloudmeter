package io.cloudmeter.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CloudMeterPropertiesTest.Config.class)
@TestPropertySource(properties = {
    "spring.cloudmeter.provider=GCP",
    "spring.cloudmeter.region=us-central1",
    "spring.cloudmeter.target-users=5000",
    "spring.cloudmeter.requests-per-user-per-second=2.0",
    "spring.cloudmeter.budget-usd=500",
    "spring.cloudmeter.capacity=50000"
})
class CloudMeterPropertiesTest {

    @EnableConfigurationProperties(CloudMeterProperties.class)
    static class Config {}

    @Autowired
    CloudMeterProperties props;

    @Test
    void provider_boundFromProperties() {
        assertEquals("GCP", props.getProvider());
    }

    @Test
    void region_boundFromProperties() {
        assertEquals("us-central1", props.getRegion());
    }

    @Test
    void targetUsers_boundFromProperties() {
        assertEquals(5000, props.getTargetUsers());
    }

    @Test
    void requestsPerUserPerSecond_boundFromProperties() {
        assertEquals(2.0, props.getRequestsPerUserPerSecond(), 1e-9);
    }

    @Test
    void budgetUsd_boundFromProperties() {
        assertEquals(500.0, props.getBudgetUsd(), 1e-9);
    }

    @Test
    void capacity_boundFromProperties() {
        assertEquals(50000, props.getCapacity());
    }

    @Test
    void defaults_whenNoPropertiesSet() {
        CloudMeterProperties defaults = new CloudMeterProperties();
        assertTrue(defaults.isEnabled());
        assertEquals("AWS", defaults.getProvider());
        assertEquals("us-east-1", defaults.getRegion());
        assertEquals(1000, defaults.getTargetUsers());
        assertEquals(1.0, defaults.getRequestsPerUserPerSecond(), 1e-9);
        assertEquals(0.0, defaults.getBudgetUsd(), 1e-9);
        assertEquals(100_000, defaults.getCapacity());
    }
}
