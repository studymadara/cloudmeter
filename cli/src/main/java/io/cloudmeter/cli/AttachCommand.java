package io.cloudmeter.cli;

import java.io.PrintStream;
import java.lang.reflect.Method;

/**
 * Attaches CloudMeter to a running JVM via the JVM Attach API (triggers agentmain()).
 *
 * Uses reflection to load {@code com.sun.tools.attach.VirtualMachine} so that
 * the CLI JAR compiles without a hard dependency on the {@code jdk.attach} module.
 * Fails gracefully with a clear error if the Attach API is unavailable (JRE-only install).
 */
public final class AttachCommand {

    private AttachCommand() {}

    /**
     * Attaches the given agent JAR to the target JVM.
     *
     * @param pid       target process ID
     * @param agentJar  absolute path to cloudmeter-agent.jar
     * @param agentArgs agent args string (e.g. "provider=AWS,targetUsers=1000"), may be null/empty
     * @param out       output stream for status messages
     * @param err       output stream for error messages
     * @return true on success, false on failure
     */
    public static boolean attach(String pid, String agentJar, String agentArgs,
                                 PrintStream out, PrintStream err) {
        return attach(pid, agentJar, agentArgs, out, err,
                      "com.sun.tools.attach.VirtualMachine");
    }

    /**
     * Package-private overload that allows tests to inject a substitute VM class name
     * (e.g. a test double or a non-existent class to trigger ClassNotFoundException).
     */
    static boolean attach(String pid, String agentJar, String agentArgs,
                          PrintStream out, PrintStream err, String vmClassName) {
        Object vm = null;
        Class<?> vmClass;
        try {
            vmClass = Class.forName(vmClassName);
        } catch (ClassNotFoundException e) {
            err.println("[cloudmeter] Attach API unavailable. Run with a JDK (Java 11+), not a JRE.");
            return false;
        }
        try {
            Method attachMethod = vmClass.getMethod("attach", String.class);
            vm = attachMethod.invoke(null, pid);
            Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
            loadAgent.invoke(vm, agentJar, agentArgs != null ? agentArgs : "");
            out.println("[cloudmeter] Agent attached to PID " + pid);
            out.println("[cloudmeter] Dashboard available at http://127.0.0.1:7777");
            return true;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            err.println("[cloudmeter] Attach failed: " + cause.getMessage());
            return false;
        } finally {
            if (vm != null) {
                try {
                    vmClass.getMethod("detach").invoke(vm);
                } catch (Exception ignored) {}
            }
        }
    }
}
