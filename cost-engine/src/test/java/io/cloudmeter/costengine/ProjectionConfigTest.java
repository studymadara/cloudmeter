package io.cloudmeter.costengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionConfigTest {

    private static ProjectionConfig valid() {
        return ProjectionConfig.builder()
                .provider(CloudProvider.AWS)
                .region("us-east-1")
                .targetUsers(10_000)
                .requestsPerUserPerSecond(0.5)
                .recordingDurationSeconds(120.0)
                .budgetUsd(500.0)
                .build();
    }

    @Test
    void builder_setsAllFields() {
        ProjectionConfig cfg = valid();
        assertEquals(CloudProvider.AWS, cfg.getProvider());
        assertEquals("us-east-1",       cfg.getRegion());
        assertEquals(10_000,            cfg.getTargetUsers());
        assertEquals(0.5,               cfg.getRequestsPerUserPerSecond(), 1e-9);
        assertEquals(120.0,             cfg.getRecordingDurationSeconds(), 1e-9);
        assertEquals(500.0,             cfg.getBudgetUsd(), 1e-9);
    }

    @Test
    void getTargetTotalRps_isProductOfUsersAndRate() {
        ProjectionConfig cfg = valid();
        assertEquals(10_000 * 0.5, cfg.getTargetTotalRps(), 1e-6);
    }

    @Test
    void nullProvider_throws() {
        assertThrows(NullPointerException.class,
                () -> ProjectionConfig.builder()
                        .region("us-east-1").targetUsers(100)
                        .requestsPerUserPerSecond(1.0).recordingDurationSeconds(60)
                        .build());
    }

    @Test
    void nullRegion_throws() {
        assertThrows(NullPointerException.class,
                () -> ProjectionConfig.builder()
                        .provider(CloudProvider.AWS).targetUsers(100)
                        .requestsPerUserPerSecond(1.0).recordingDurationSeconds(60)
                        .region(null).build());
    }

    @Test
    void zeroTargetUsers_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionConfig.builder()
                        .provider(CloudProvider.AWS).region("us-east-1")
                        .targetUsers(0).requestsPerUserPerSecond(1.0)
                        .recordingDurationSeconds(60).build());
    }

    @Test
    void zeroRequestRate_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionConfig.builder()
                        .provider(CloudProvider.AWS).region("us-east-1")
                        .targetUsers(100).requestsPerUserPerSecond(0)
                        .recordingDurationSeconds(60).build());
    }

    @Test
    void zeroRecordingDuration_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionConfig.builder()
                        .provider(CloudProvider.AWS).region("us-east-1")
                        .targetUsers(100).requestsPerUserPerSecond(1.0)
                        .recordingDurationSeconds(0).build());
    }

    @Test
    void negativeBudget_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectionConfig.builder()
                        .provider(CloudProvider.AWS).region("us-east-1")
                        .targetUsers(100).requestsPerUserPerSecond(1.0)
                        .recordingDurationSeconds(60).budgetUsd(-1).build());
    }

    @Test
    void zeroBudget_isAllowed() {
        assertDoesNotThrow(() -> ProjectionConfig.builder()
                .provider(CloudProvider.AWS).region("us-east-1")
                .targetUsers(100).requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60).budgetUsd(0).build());
    }

    @Test
    void defaultRegion_isUsEast1() {
        ProjectionConfig cfg = ProjectionConfig.builder()
                .provider(CloudProvider.AWS)
                .targetUsers(100)
                .requestsPerUserPerSecond(1.0)
                .recordingDurationSeconds(60)
                .build();
        assertEquals("us-east-1", cfg.getRegion());
    }

    @Test
    void gcpProvider_buildsSuccessfully() {
        assertDoesNotThrow(() -> ProjectionConfig.builder()
                .provider(CloudProvider.GCP).region("us-central1")
                .targetUsers(5_000).requestsPerUserPerSecond(0.2)
                .recordingDurationSeconds(60).build());
    }
}
