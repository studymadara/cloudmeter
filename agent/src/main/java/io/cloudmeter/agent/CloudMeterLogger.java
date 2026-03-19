package io.cloudmeter.agent;

/**
 * Minimal internal logger that writes to stderr with a [CloudMeter] prefix.
 * Never depends on user-facing logging frameworks (SLF4J, Log4j, etc.).
 */
final class CloudMeterLogger {

    private CloudMeterLogger() {}

    static void info(String message) {
        System.err.println("[CloudMeter] INFO  " + message);
    }

    static void warn(String message) {
        System.err.println("[CloudMeter] WARN  " + message);
    }
}
