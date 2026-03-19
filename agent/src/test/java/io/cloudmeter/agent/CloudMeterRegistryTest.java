package io.cloudmeter.agent;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CloudMeterRegistryTest {

    @BeforeEach
    @AfterEach
    void reset() {
        CloudMeterRegistry.reset();
    }

    @Test
    void init_setsStore() {
        MetricsStore store = new MetricsStore();
        CloudMeterRegistry.init(store);
        assertSame(store, CloudMeterRegistry.getStore());
    }

    @Test
    void getStore_nullBeforeInit() {
        assertNull(CloudMeterRegistry.getStore());
    }

    @Test
    void init_clearsActiveContexts() {
        RequestContext ctx = ctx();
        CloudMeterRegistry.registerContext(ctx);
        assertFalse(CloudMeterRegistry.getActiveContexts().isEmpty());

        CloudMeterRegistry.init(new MetricsStore());

        assertTrue(CloudMeterRegistry.getActiveContexts().isEmpty());
    }

    @Test
    void registerAndUnregister_context() {
        RequestContext ctx = ctx();
        assertTrue(CloudMeterRegistry.getActiveContexts().isEmpty());

        CloudMeterRegistry.registerContext(ctx);
        assertTrue(CloudMeterRegistry.getActiveContexts().contains(ctx));

        CloudMeterRegistry.unregisterContext(ctx);
        assertFalse(CloudMeterRegistry.getActiveContexts().contains(ctx));
    }

    @Test
    void getActiveContexts_isUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> CloudMeterRegistry.getActiveContexts().add(ctx()));
    }

    @Test
    void isWarmup_trueRightAfterInit() {
        CloudMeterRegistry.init(new MetricsStore());
        assertTrue(CloudMeterRegistry.isWarmup());
    }

    @Test
    void isWarmup_falseAfterWarmupWindow() {
        CloudMeterRegistry.init(new MetricsStore());
        // Wind the clock back past the warmup window
        CloudMeterRegistry.setAgentStartMs(
                System.currentTimeMillis() - CloudMeterRegistry.WARMUP_DURATION_MS - 1_000L);
        assertFalse(CloudMeterRegistry.isWarmup());
    }

    @Test
    void reset_clearsStore() {
        CloudMeterRegistry.init(new MetricsStore());
        assertNotNull(CloudMeterRegistry.getStore());
        CloudMeterRegistry.reset();
        assertNull(CloudMeterRegistry.getStore());
    }

    @Test
    void reset_clearsContexts() {
        CloudMeterRegistry.registerContext(ctx());
        CloudMeterRegistry.reset();
        assertTrue(CloudMeterRegistry.getActiveContexts().isEmpty());
    }

    @Test
    void multipleContexts_trackedIndependently() {
        RequestContext a = ctx();
        RequestContext b = ctx();
        CloudMeterRegistry.registerContext(a);
        CloudMeterRegistry.registerContext(b);
        assertEquals(2, CloudMeterRegistry.getActiveContexts().size());

        CloudMeterRegistry.unregisterContext(a);
        assertEquals(1, CloudMeterRegistry.getActiveContexts().size());
        assertTrue(CloudMeterRegistry.getActiveContexts().contains(b));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RequestContext ctx() {
        return new RequestContext("/test", "/test", System.nanoTime(), Thread.currentThread().getId());
    }
}
