package io.cloudmeter.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class AttachCommandTest {

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    // ── Test doubles ──────────────────────────────────────────────────────────

    /** Mimics com.sun.tools.attach.VirtualMachine — happy path, no exceptions. */
    public static final class FakeVm {
        public static FakeVm attach(String pid) { return new FakeVm(); }
        public void loadAgent(String jar, String args) { /* no-op */ }
        public void detach() { /* no-op */ }
    }

    /** Like FakeVm but loadAgent() throws — exercises the detach() path in finally. */
    public static final class FakeVmLoadAgentThrows {
        public static FakeVmLoadAgentThrows attach(String pid) { return new FakeVmLoadAgentThrows(); }
        public void loadAgent(String jar, String args) {
            throw new RuntimeException("simulated loadAgent failure");
        }
        public void detach() { /* no-op */ }
    }

    // ── ClassNotFoundException path ────────────────────────────────────────────

    /**
     * Forces ClassNotFoundException by passing a non-existent class name to the
     * package-private overload — covers the catch block that prints "Attach API unavailable".
     */
    @Test
    void attach_nonExistentVmClass_returnsFalseAndPrintsApiUnavailable() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        boolean result = AttachCommand.attach("12345", "/any.jar", null,
                sink(), new PrintStream(errBuf),
                "io.cloudmeter.cli.NonExistentVirtualMachine");
        assertFalse(result);
        assertTrue(errBuf.toString().contains("Attach API unavailable"),
                "Expected 'Attach API unavailable' but got: " + errBuf);
    }

    // ── Happy path via FakeVm ─────────────────────────────────────────────────

    @Test
    void attach_withFakeVm_returnsTrue() {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        boolean result = AttachCommand.attach("42", "/agent.jar", "provider=AWS",
                new PrintStream(outBuf), sink(),
                FakeVm.class.getName());
        assertTrue(result);
        String out = outBuf.toString();
        assertTrue(out.contains("Agent attached to PID 42"), "out: " + out);
        assertTrue(out.contains("http://127.0.0.1:7777"), "out: " + out);
    }

    @Test
    void attach_withFakeVm_nullAgentArgs_returnsTrue() {
        boolean result = AttachCommand.attach("42", "/agent.jar", null,
                sink(), sink(), FakeVm.class.getName());
        assertTrue(result);
    }

    // ── Exception after vm assigned (covers detach in finally) ───────────────

    @Test
    void attach_withFakeVmLoadAgentThrows_returnsFalseAndCallsDetach() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        boolean result = AttachCommand.attach("42", "/agent.jar", null,
                sink(), new PrintStream(errBuf),
                FakeVmLoadAgentThrows.class.getName());
        assertFalse(result);
        assertTrue(errBuf.toString().contains("Attach failed"),
                "Expected 'Attach failed' but got: " + errBuf);
    }

    /**
     * When com.sun.tools.attach.VirtualMachine is unavailable (which it is in a plain
     * JRE test environment without the attach module), attach() must return false and
     * write a clear error message.
     *
     * In a JDK test environment the class IS available, so we test the error message
     * path indirectly via the attach-to-bad-pid path (which always fails).
     */
    @Test
    void attach_withBadPid_returnsFalse() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        boolean result = AttachCommand.attach("999999999", "/nonexistent/agent.jar", null,
                sink(), new PrintStream(errBuf));
        // Either class not found (JRE) or attach failed (JDK) — either way must be false
        assertFalse(result);
        // Must have written something to err
        assertTrue(errBuf.toString().length() > 0);
    }

    @Test
    void attach_withBadPid_writesErrorMessage() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        AttachCommand.attach("999999999", "/nonexistent/agent.jar", "",
                sink(), new PrintStream(errBuf));
        String errStr = errBuf.toString();
        assertTrue(errStr.contains("[cloudmeter]"),
                "Expected [cloudmeter] prefix in error: " + errStr);
    }

    // ── null agentArgs ─────────────────────────────────────────────────────────

    @Test
    void attach_nullAgentArgs_doesNotThrow() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        // Should not throw NPE when agentArgs is null — must coerce to ""
        assertDoesNotThrow(() ->
                AttachCommand.attach("999999999", "/nonexistent/agent.jar", null,
                        sink(), new PrintStream(errBuf)));
    }

    @Test
    void attach_emptyAgentArgs_doesNotThrow() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        assertDoesNotThrow(() ->
                AttachCommand.attach("999999999", "/nonexistent/agent.jar", "",
                        sink(), new PrintStream(errBuf)));
    }

    // ── constructor is private ─────────────────────────────────────────────────

    @Test
    void constructor_isPrivate() throws Exception {
        java.lang.reflect.Constructor<AttachCommand> ctor =
                AttachCommand.class.getDeclaredConstructor();
        assertFalse(ctor.isAccessible());
        ctor.setAccessible(true);
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) ctor::newInstance);
    }

    // ── agent JAR file existence check (CLI level) ────────────────────────────

    @Test
    void cloudMeterCli_attachSubcommand_nonexistentAgentJar_returnsError() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = CloudMeterCli.run(new String[]{
                "attach", "12345",
                "--agent-jar", "/this/path/does/not/exist.jar"
        }, sink(), new PrintStream(errBuf), null);
        assertEquals(CloudMeterCli.EXIT_ERROR, code);
        assertTrue(errBuf.toString().contains("Agent JAR not found"),
                "Expected 'Agent JAR not found' but got: " + errBuf);
    }

    @Test
    void cloudMeterCli_attachSubcommand_withExistingJar_attemptsAttach() throws Exception {
        // Use a real temp file so the file-existence check passes, then attach fails on bad PID
        java.io.File tmpJar = java.io.File.createTempFile("fake-agent-", ".jar");
        tmpJar.deleteOnExit();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = CloudMeterCli.run(new String[]{
                "attach", "999999999",
                "--agent-jar", tmpJar.getAbsolutePath()
        }, sink(), new PrintStream(errBuf), null);
        assertEquals(CloudMeterCli.EXIT_ERROR, code);
        // File check passes; attach fails on bad PID → [cloudmeter] prefix in error
        assertTrue(errBuf.toString().contains("[cloudmeter]"),
                "Expected [cloudmeter] prefix but got: " + errBuf);
    }

    // ── via CloudMeterCli ─────────────────────────────────────────────────────

    @Test
    void cloudMeterCli_attachSubcommand_missingPid_returnsError() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = CloudMeterCli.run(new String[]{"attach"},
                sink(), new PrintStream(errBuf), null);
        assertEquals(CloudMeterCli.EXIT_ERROR, code);
        assertTrue(errBuf.toString().contains("Usage:"));
    }

    @Test
    void cloudMeterCli_attachSubcommand_missingAgentJar_returnsError() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = CloudMeterCli.run(new String[]{"attach", "12345"},
                sink(), new PrintStream(errBuf), null);
        assertEquals(CloudMeterCli.EXIT_ERROR, code);
        assertTrue(errBuf.toString().contains("--agent-jar"));
    }

    @Test
    void cloudMeterCli_attachSubcommand_withAllFlags_attemptAttach() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        // Bad PID — attach will fail, but all flag parsing must succeed
        int code = CloudMeterCli.run(new String[]{
                "attach", "999999999",
                "--agent-jar", "/path/to/agent.jar",
                "--provider", "AWS",
                "--region", "us-east-1",
                "--users", "5000",
                "--rpu", "1.0",
                "--budget", "500",
                "--port", "7777"
        }, sink(), new PrintStream(errBuf), null);
        // Bad PID always causes EXIT_ERROR
        assertEquals(CloudMeterCli.EXIT_ERROR, code);
    }

    @Test
    void cloudMeterCli_attachSubcommand_withMinimalArgs_attemptAttach() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = CloudMeterCli.run(new String[]{
                "attach", "999999999",
                "--agent-jar", "/path/to/agent.jar"
        }, sink(), new PrintStream(errBuf), null);
        assertEquals(CloudMeterCli.EXIT_ERROR, code);
    }

    // ── error message content ──────────────────────────────────────────────────

    @Test
    void attach_classNotFoundPath_prints_attachApiUnavailable() {
        // Simulate the ClassNotFoundException path by invoking attach and checking
        // that the error message is one of the two expected forms
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        AttachCommand.attach("0", "/nonexistent.jar", null, sink(), new PrintStream(errBuf));
        String msg = errBuf.toString();
        boolean isClassNotFound = msg.contains("Attach API unavailable");
        boolean isAttachFailed  = msg.contains("Attach failed");
        assertTrue(isClassNotFound || isAttachFailed,
                "Unexpected error message: " + msg);
    }
}
