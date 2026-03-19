package io.cloudmeter.agent;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies HttpServletAdvice delegates correctly to HttpServletInterceptor.
 * These methods are normally inlined by Byte Buddy; here we call them directly
 * as plain static methods to confirm they don't throw and have the expected effect.
 */
class HttpServletAdviceTest {

    @BeforeEach
    void setUp() {
        CloudMeterRegistry.reset();
        MetricsStore store = new MetricsStore();
        store.startRecording();
        CloudMeterRegistry.init(store);
        RequestContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.clear();
        CloudMeterRegistry.reset();
    }

    @Test
    void onEnter_setsRequestContext() {
        HttpServletAdvice.onEnter(request("/api/test", "GET"));
        assertNotNull(RequestContextHolder.get());
    }

    @Test
    void onExit_clearsRequestContext() {
        Object req = request("/api/test", "GET");
        HttpServletAdvice.onEnter(req);
        HttpServletAdvice.onExit(req, response(200));
        assertNull(RequestContextHolder.get());
    }

    @Test
    void onEnter_nullRequest_doesNotThrow() {
        assertDoesNotThrow(() -> HttpServletAdvice.onEnter(null));
    }

    @Test
    void onExit_nullRequest_doesNotThrow() {
        assertDoesNotThrow(() -> HttpServletAdvice.onExit(null, null));
    }

    @Test
    void fullCycle_addsMetricToStore() {
        Object req = request("/api/ping", "GET");
        Object res = response(200);

        HttpServletAdvice.onEnter(req);
        HttpServletAdvice.onExit(req, res);

        assertEquals(1, CloudMeterRegistry.getStore().getAll().size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Object request(String uri, String method) {
        return new Object() {
            public String getRequestURI() { return uri; }
            public String getMethod()     { return method; }
            public Object getAttribute(String name) { return null; }
        };
    }

    private static Object response(int status) {
        return new Object() {
            public int    getStatus() { return status; }
            public String getHeader(String name) { return null; }
        };
    }
}
