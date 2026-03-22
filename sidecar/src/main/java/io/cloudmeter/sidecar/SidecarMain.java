package io.cloudmeter.sidecar;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.costengine.CloudProvider;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.reporter.DashboardServer;

/**
 * Entry point for the CloudMeter sidecar process.
 *
 * Starts:
 *  - an ingest HTTP server on {@code ingestPort} (default 7778) for Python/Node.js clients
 *  - the dashboard HTTP server on {@code dashboardPort} (default 7777)
 *
 * Always starts in recording mode — no manual start needed.
 */
public final class SidecarMain {

    private SidecarMain() {}

    public static void main(String[] args) throws Exception {
        SidecarArgs cfg = SidecarArgs.parse(args);

        MetricsStore store = new MetricsStore();
        store.startRecording(); // auto-start — sidecar always records

        ProjectionConfig config = ProjectionConfig.builder()
                .provider(CloudProvider.valueOf(cfg.getProvider()))
                .region(cfg.getRegion())
                .targetUsers(cfg.getTargetUsers())
                .requestsPerUserPerSecond(cfg.getRequestsPerUserPerSecond())
                .recordingDurationSeconds(300.0) // 5-minute rolling window (reassessed on each projection)
                .budgetUsd(cfg.getBudgetUsd())
                .build();

        IngestServer ingest = new IngestServer(store, cfg.getIngestPort());
        ingest.start();

        DashboardServer dashboard = new DashboardServer(store, config, cfg.getDashboardPort());
        dashboard.start();

        System.out.println("[CloudMeter Sidecar] Ingest   : http://127.0.0.1:" + cfg.getIngestPort() + "/api/metrics");
        System.out.println("[CloudMeter Sidecar] Dashboard: http://localhost:" + cfg.getDashboardPort());
        System.out.println("[CloudMeter Sidecar] Provider : " + cfg.getProvider() + " / " + cfg.getRegion());
        System.out.println("[CloudMeter Sidecar] Target   : " + cfg.getTargetUsers() + " users");

        // Keep alive
        Thread.currentThread().join();
    }
}
