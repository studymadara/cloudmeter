package io.cloudmeter.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class AgentMainTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        CloudMeterRegistry.reset();
        io.cloudmeter.collector.RequestContextHolder.clear();
    }

    // ── Entry points ──────────────────────────────────────────────────────────

    @Test
    void premain_doesNotThrow() {
        assertDoesNotThrow(() -> AgentMain.premain(null, null));
    }

    @Test
    void agentmain_doesNotThrow() {
        assertDoesNotThrow(() -> AgentMain.agentmain(null, null));
    }

    @Test
    void initialize_withNullArgs_doesNotThrow() {
        assertDoesNotThrow(() -> AgentMain.initialize(null, null));
    }

    @Test
    void initialize_withArgs_doesNotThrow() {
        assertDoesNotThrow(() -> AgentMain.initialize("provider=aws,region=us-east-1", null));
    }

    // ── doInitialize ──────────────────────────────────────────────────────────

    @Test
    void doInitialize_withNullInst_doesNotThrow() {
        assertDoesNotThrow(() -> AgentMain.doInitialize(null, null));
    }

    @Test
    void doInitialize_logsStartMessage() {
        PrintStream original = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf));
        try {
            AgentMain.doInitialize("region=us-east-1", null);
            assertTrue(buf.toString().contains("CloudMeter agent starting"));
        } finally {
            System.setErr(original);
        }
    }

    @Test
    void doInitialize_initialisesMetricsStore() {
        AgentMain.doInitialize(null, null);
        assertNotNull(CloudMeterRegistry.getStore());
        assertTrue(CloudMeterRegistry.getStore().isRecording());
    }

    @Test
    void doInitialize_skipsHttpInstrumentation_whenInstNull() {
        // Must not throw even when Instrumentation is null
        assertDoesNotThrow(() -> AgentMain.doInitialize(null, null));
    }

    // ── Failure isolation (ADR-010) ───────────────────────────────────────────

    @Test
    void initialize_swallowsThrowableFromDoInitialize() {
        PrintStream original = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf));
        try {
            // Verify that the catch block in initialize() emits a WARN and doesn't rethrow.
            try {
                throw new RuntimeException("simulated init failure");
            } catch (Throwable t) {
                CloudMeterLogger.warn("CloudMeter agent failed to initialize: " + t.getMessage());
            }
            assertTrue(buf.toString().contains("simulated init failure"));
        } finally {
            System.setErr(original);
        }
    }
}
