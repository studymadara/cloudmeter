package io.cloudmeter.agent;

import io.cloudmeter.cli.CliArgs;
import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.costengine.CostProjector;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.ProjectionConfig;
import io.cloudmeter.reporter.DashboardServer;
import io.cloudmeter.reporter.TerminalReporter;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.List;

/**
 * Java agent entry point.
 *
 * premain()   — called at JVM startup via {@code -javaagent:cloudmeter-agent.jar}
 * agentmain() — called on dynamic attach via {@code cloudmeter attach <pid>}
 *
 * Both paths delegate to {@link #initialize(String, Instrumentation)}.
 * Failures are swallowed at the {@code initialize} boundary so that a CloudMeter bug
 * can never crash the user application (ADR-010).
 *
 * Agent args (comma-separated key=value):
 *   provider=AWS|GCP|AZURE   (default: AWS)
 *   region=us-east-1         (default: us-east-1)
 *   targetUsers=1000         (default: 1000)
 *   rpu=1.0                  requests/user/sec (default: 1.0)
 *   duration=60              recording seconds (default: 60)
 *   budget=0                 monthly USD budget, 0 = disabled (default: 0)
 *   port=7777                dashboard port (default: 7777)
 *
 * Example: {@code -javaagent:cloudmeter-agent.jar=provider=AWS,targetUsers=5000,budget=500}
 */
public final class AgentMain {

    private AgentMain() {}

    public static void premain(String args, Instrumentation inst) {
        initialize(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        initialize(args, inst);
    }

    static void initialize(String args, Instrumentation inst) {
        try {
            doInitialize(args, inst);
        } catch (Throwable t) {
            // ADR-010: agent initialization failures must never propagate to user code.
            CloudMeterLogger.warn("CloudMeter agent failed to initialize: " + t.getMessage());
        }
    }

    /**
     * Core initialization — wires the MetricsStore, HTTP instrumentation, background sampler,
     * dashboard server, and shutdown hook.
     * Separated so tests can invoke it directly with a null Instrumentation.
     */
    static void doInitialize(String args, Instrumentation inst) {
        CloudMeterLogger.info("CloudMeter agent starting (args=" + args + ")");

        // Parse configuration from agent args
        CliArgs cliArgs = CliArgs.parse(args);
        ProjectionConfig config = cliArgs.toProjectionConfig();

        // Initialise the metrics store and start recording immediately
        MetricsStore store = new MetricsStore();
        store.startRecording();
        CloudMeterRegistry.init(store);
        CloudMeterLogger.info("MetricsStore ready, recording started");

        // Install Byte Buddy instrumentation (skipped when Instrumentation is unavailable)
        if (inst != null) {
            HttpInstrumentation.install(inst);
            CloudMeterLogger.info("HTTP instrumentation installed");
            ExecutorInstrumentation.install(inst);
            CloudMeterLogger.info("Executor instrumentation installed (Spring @Async context propagation)");
        }

        // Start the background thread-state sampler at 10 ms intervals
        ThreadStateCollector collector = new ThreadStateCollector(10L);
        collector.start();
        CloudMeterRegistry.setSampler(collector);
        CloudMeterLogger.info("Thread-state sampler started (10 ms interval)");

        // Start the embedded dashboard server on the configured port
        startDashboard(store, config, cliArgs.getPort());

        // Register shutdown hook: project costs and print terminal report on JVM exit
        registerShutdownHook(store, config);
    }

    static void startDashboard(MetricsStore store, ProjectionConfig config, int port) {
        try {
            DashboardServer dashboard = new DashboardServer(store, config, port);
            dashboard.start();
            CloudMeterLogger.info("Dashboard started at http://127.0.0.1:" + dashboard.getPort());
        } catch (IOException e) {
            CloudMeterLogger.warn("Dashboard could not start on port " + port + ": " + e.getMessage());
        }
    }

    static void registerShutdownHook(MetricsStore store, ProjectionConfig config) {
        Thread hook = new Thread(() -> {
            try {
                store.stopRecording();
                List<EndpointCostProjection> projections =
                        CostProjector.project(store.getAll(), config);
                if (!projections.isEmpty()) {
                    TerminalReporter.print(projections, config, System.out);
                }
            } catch (Throwable t) {
                CloudMeterLogger.warn("Shutdown report failed: " + t.getMessage());
            }
        }, "cloudmeter-shutdown");
        hook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(hook);
        CloudMeterLogger.info("Shutdown hook registered — cost report will print on JVM exit");
    }
}
