package io.cloudmeter.agent;

import io.cloudmeter.collector.MetricsStore;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point.
 *
 * premain()   — called at JVM startup via {@code -javaagent:cloudmeter-agent.jar}
 * agentmain() — called on dynamic attach via {@code cloudmeter attach <pid>}
 *
 * Both paths delegate to {@link #initialize(String, Instrumentation)}.
 * Failures are swallowed at the {@code initialize} boundary so that a CloudMeter bug
 * can never crash the user application (ADR-010).
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
     * Core initialization — wires the MetricsStore, HTTP instrumentation, and background sampler.
     * Separated so tests can invoke it directly with a null Instrumentation.
     */
    static void doInitialize(String args, Instrumentation inst) {
        CloudMeterLogger.info("CloudMeter agent starting (args=" + args + ")");

        // Initialise the metrics store and start recording immediately
        MetricsStore store = new MetricsStore();
        store.startRecording();
        CloudMeterRegistry.init(store);
        CloudMeterLogger.info("MetricsStore ready, recording started");

        // Install Byte Buddy HTTP instrumentation (skipped when Instrumentation is unavailable)
        if (inst != null) {
            HttpInstrumentation.install(inst);
            CloudMeterLogger.info("HTTP instrumentation installed");
        }

        // Start the background thread-state sampler at 10 ms intervals
        new ThreadStateCollector(10L).start();
        CloudMeterLogger.info("Thread-state sampler started (10 ms interval)");
    }
}
