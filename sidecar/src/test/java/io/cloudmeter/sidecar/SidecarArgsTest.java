package io.cloudmeter.sidecar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SidecarArgsTest {

    @Test
    void defaultsWhenNoArgsGiven() {
        SidecarArgs args = SidecarArgs.parse(new String[0]);

        assertEquals("AWS",       args.getProvider());
        assertEquals("us-east-1", args.getRegion());
        assertEquals(1000,        args.getTargetUsers());
        assertEquals(0.0,         args.getBudgetUsd(),                0.0001);
        assertEquals(7777,        args.getDashboardPort());
        assertEquals(7778,        args.getIngestPort());
        assertEquals(1.0,         args.getRequestsPerUserPerSecond(), 0.0001);
    }

    @Test
    void providerSetWithEqualsForm() {
        SidecarArgs args = SidecarArgs.parse(new String[]{"--provider=GCP"});
        assertEquals("GCP", args.getProvider());
    }

    @Test
    void regionSetWithSpaceForm() {
        SidecarArgs args = SidecarArgs.parse(new String[]{"--region", "us-west-2"});
        assertEquals("us-west-2", args.getRegion());
    }

    @Test
    void targetUsersSet() {
        SidecarArgs args = SidecarArgs.parse(new String[]{"--target-users", "5000"});
        assertEquals(5000, args.getTargetUsers());
    }

    @Test
    void dashboardPortSet() {
        SidecarArgs args = SidecarArgs.parse(new String[]{"--dashboard-port", "8080"});
        assertEquals(8080, args.getDashboardPort());
    }

    @Test
    void ingestPortSet() {
        SidecarArgs args = SidecarArgs.parse(new String[]{"--ingest-port", "8081"});
        assertEquals(8081, args.getIngestPort());
    }

    @Test
    void multipleArgsSet() {
        SidecarArgs args = SidecarArgs.parse(new String[]{
                "--provider=GCP",
                "--region", "europe-west1",
                "--target-users", "2000",
                "--dashboard-port", "9999"
        });
        assertEquals("GCP",           args.getProvider());
        assertEquals("europe-west1",  args.getRegion());
        assertEquals(2000,            args.getTargetUsers());
        assertEquals(9999,            args.getDashboardPort());
    }

    @Test
    void parseNullArgsReturnsDefaults() {
        SidecarArgs args = SidecarArgs.parse(null);
        assertEquals("AWS", args.getProvider());
    }

    @Test
    void unknownFlagThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SidecarArgs.parse(new String[]{"--unknown-flag", "value"}));
    }

    @Test
    void nonFlagArgumentThrows() {
        // Args not starting with "--" should throw
        assertThrows(IllegalArgumentException.class,
                () -> SidecarArgs.parse(new String[]{"AWS"}));
    }

    @Test
    void missingValueForFlagThrows() {
        // "--region" with no following value
        assertThrows(IllegalArgumentException.class,
                () -> SidecarArgs.parse(new String[]{"--region"}));
    }

    @Test
    void budgetUsdSet() {
        SidecarArgs args = SidecarArgs.parse(new String[]{"--budget-usd", "500.0"});
        assertEquals(500.0, args.getBudgetUsd(), 0.0001);
    }

    @Test
    void requestsPerUserPerSecondSet() {
        SidecarArgs args = SidecarArgs.parse(new String[]{"--requests-per-user-per-second", "2.5"});
        assertEquals(2.5, args.getRequestsPerUserPerSecond(), 0.0001);
    }
}
