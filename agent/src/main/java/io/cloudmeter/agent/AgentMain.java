package io.cloudmeter.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point.
 *
 * premain()  — called at JVM startup via {@code -javaagent:cloudmeter-agent.jar}
 * agentmain() — called on dynamic attach via {@code cloudmeter attach <pid>}
 *
 * Both paths delegate to {@link #initialize(String, Instrumentation)}.
 * Instrumentation is installed in subsequent commits once the collector
 * module is fully wired.
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
     * Core initialization logic — separated so it can be exercised directly in tests.
     * Instrumentation wiring will be added here in subsequent commits.
     */
    static void doInitialize(String args, Instrumentation inst) {
        CloudMeterLogger.info("CloudMeter agent starting (args=" + args + ")");
    }
}
