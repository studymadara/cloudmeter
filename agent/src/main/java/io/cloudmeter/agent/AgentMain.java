package io.cloudmeter.agent;

import io.cloudmeter.cli.CliArgs;
import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.costengine.CostProjector;
import io.cloudmeter.costengine.EndpointCostProjection;
import io.cloudmeter.costengine.LivePricingFetcher;
import io.cloudmeter.costengine.PricingCatalog;
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
        injectBootstrapClasses(inst);
        initialize(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        injectBootstrapClasses(inst);
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
     * Injects the context-propagation classes into the bootstrap classloader so that
     * instrumented JDK classes (e.g. ThreadPoolExecutor) can reference them.
     *
     * Must be called as the FIRST thing in premain(), before any CloudMeter class is
     * loaded, so that parent-first classloader delegation routes all subsequent loads of
     * these classes to the bootstrap classloader's copy.
     *
     * Uses only JDK classes — no CloudMeter imports — so that calling this method does
     * not itself trigger loading of any class that we are about to inject.
     */
    static void injectBootstrapClasses(java.lang.instrument.Instrumentation inst) {
        if (inst == null) return;
        String[] resources = {
            "io/cloudmeter/collector/RequestContext.class",
            "io/cloudmeter/collector/RequestContextHolder.class",
            "io/cloudmeter/agent/ContextPropagatingRunnable.class",
            "io/cloudmeter/agent/ContextPropagatingCallable.class",
            "io/cloudmeter/agent/ExecutorInterceptor.class"
        };
        java.io.File tmpFile = null;
        try {
            tmpFile = java.io.File.createTempFile("cloudmeter-bootstrap-", ".jar");
            tmpFile.deleteOnExit();
            try (java.util.jar.JarOutputStream jos =
                    new java.util.jar.JarOutputStream(new java.io.FileOutputStream(tmpFile))) {
                for (String resource : resources) {
                    java.io.InputStream is =
                        ClassLoader.getSystemClassLoader().getResourceAsStream(resource);
                    if (is == null) continue;
                    jos.putNextEntry(new java.util.jar.JarEntry(resource));
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) >= 0) jos.write(buf, 0, n);
                    jos.closeEntry();
                    is.close();
                }
            }
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tmpFile));
        } catch (Throwable t) {
            // Non-fatal: bootstrap injection failure degrades gracefully (Spring executors still work)
        }
    }

    /**
     * Core initialization — wires the MetricsStore, HTTP instrumentation, background sampler,
     * dashboard server, and shutdown hook.
     * Separated so tests can invoke it directly with a null Instrumentation.
     */
    static void doInitialize(String args, Instrumentation inst) {
        CloudMeterLogger.info("CloudMeter agent starting (args=" + args + ")");

        // Parse configuration from agent args — merges cloudmeter.yaml with agent args
        CliArgs cliArgs = CliArgs.parseWithYaml(args);
        ProjectionConfig config = cliArgs.toProjectionConfig();

        // Optionally fetch latest pricing from the CloudMeter pricing repository.
        // Runs in a background daemon thread so it never delays application startup.
        // Falls back silently to embedded static prices if the fetch fails.
        if (cliArgs.isFetchPrices()) {
            Thread fetchThread = new Thread(AgentMain::runPricingFetch, "cloudmeter-pricing-fetch");
            fetchThread.setDaemon(true);
            fetchThread.start();
        }

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
            ThreadPoolExecutorInstrumentation.install(inst);
            CloudMeterLogger.info("JVM executor instrumentation installed (ThreadPoolExecutor context propagation)");
            ForkJoinPoolInstrumentation.install(inst);
            CloudMeterLogger.info("ForkJoinPool instrumentation installed (CompletableFuture common pool context propagation)");
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

    /** Runs a live pricing fetch and logs the outcome. Extracted for testability. */
    static void runPricingFetch() {
        CloudMeterLogger.info("Fetching live prices from CloudMeter pricing repository...");
        boolean ok = LivePricingFetcher.fetchAndApply();
        if (ok) {
            CloudMeterLogger.info("Live pricing applied — prices as of " + PricingCatalog.getPricingDate());
        }
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
