package io.cloudmeter.agent;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests HttpServletInterceptor logic without a live Byte Buddy agent.
 * Requests and responses are plain anonymous inner classes exposing the
 * methods the interceptor reads via reflection.
 */
class HttpServletInterceptorTest {

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

    // ── onRequestStart ────────────────────────────────────────────────────────

    @Test
    void onRequestStart_setsContextHolder() {
        HttpServletInterceptor.onRequestStart(request("/api/users/1", "GET"));
        assertNotNull(RequestContextHolder.get());
    }

    @Test
    void onRequestStart_registersContextInRegistry() {
        HttpServletInterceptor.onRequestStart(request("/api/orders/5", "POST"));
        assertEquals(1, CloudMeterRegistry.getActiveContexts().size());
    }

    @Test
    void onRequestStart_nullRequest_doesNotThrow() {
        // null request → path falls back to "UNKNOWN"; context is still created
        assertDoesNotThrow(() -> HttpServletInterceptor.onRequestStart(null));
        // cleanup
        RequestContextHolder.clear();
        CloudMeterRegistry.reset();
    }

    @Test
    void onRequestStart_nestedCall_skipsSecondContext() {
        HttpServletInterceptor.onRequestStart(request("/outer", "GET"));
        io.cloudmeter.collector.RequestContext firstCtx = RequestContextHolder.get();
        HttpServletInterceptor.onRequestStart(request("/inner", "GET"));
        assertSame(firstCtx, RequestContextHolder.get(), "nested dispatch must not replace outer context");
        // cleanup — use reset() since getActiveContexts() is unmodifiable
        RequestContextHolder.clear();
        CloudMeterRegistry.reset();
        MetricsStore store = new MetricsStore();
        store.startRecording();
        CloudMeterRegistry.init(store);
    }

    // ── onRequestEnd ──────────────────────────────────────────────────────────

    @Test
    void onRequestEnd_addsMetricsToStore_whenRecording() {
        Object req = request("/api/health", "GET");
        Object res = response(200, 42L);

        HttpServletInterceptor.onRequestStart(req);
        HttpServletInterceptor.onRequestEnd(req, res);

        List<?> all = CloudMeterRegistry.getStore().getAll();
        assertEquals(1, all.size());
    }

    @Test
    void onRequestEnd_clearsContextHolder() {
        Object req = request("/api/check", "GET");
        HttpServletInterceptor.onRequestStart(req);
        assertNotNull(RequestContextHolder.get());

        HttpServletInterceptor.onRequestEnd(req, response(200, 0L));
        assertNull(RequestContextHolder.get());
    }

    @Test
    void onRequestEnd_unregistersContextFromRegistry() {
        Object req = request("/api/data", "GET");
        HttpServletInterceptor.onRequestStart(req);
        assertEquals(1, CloudMeterRegistry.getActiveContexts().size());

        HttpServletInterceptor.onRequestEnd(req, response(200, 0L));
        assertEquals(0, CloudMeterRegistry.getActiveContexts().size());
    }

    @Test
    void onRequestEnd_nullContext_doesNotThrow() {
        // No prior onRequestStart — context is null
        assertDoesNotThrow(() ->
                HttpServletInterceptor.onRequestEnd(request("/api/x", "GET"), response(200, 0L)));
    }

    @Test
    void onRequestEnd_nullResponse_doesNotThrow() {
        HttpServletInterceptor.onRequestStart(request("/api/x", "GET"));
        assertDoesNotThrow(() ->
                HttpServletInterceptor.onRequestEnd(request("/api/x", "GET"), null));
    }

    @Test
    void onRequestEnd_notRecording_dropsMetrics() {
        CloudMeterRegistry.getStore().stopRecording();
        Object req = request("/api/data", "GET");
        HttpServletInterceptor.onRequestStart(req);
        HttpServletInterceptor.onRequestEnd(req, response(200, 10L));
        assertEquals(0, CloudMeterRegistry.getStore().getAll().size());
    }

    // ── Route template extraction ──────────────────────────────────────────────

    @Test
    void springRouteAttribute_usedWhenPresent() {
        Object req = requestWithSpringPattern("/api/users/123", "GET", "/api/users/{id}");
        HttpServletInterceptor.onRequestStart(req);
        HttpServletInterceptor.onRequestEnd(req, response(200, 0L));

        io.cloudmeter.collector.RequestMetrics m =
                (io.cloudmeter.collector.RequestMetrics) CloudMeterRegistry.getStore().getAll().get(0);
        assertEquals("GET /api/users/{id}", m.getRouteTemplate());
    }

    @Test
    void heuristicNormalization_usedWhenNoSpringAttribute() {
        Object req = request("/api/users/123", "GET");
        HttpServletInterceptor.onRequestStart(req);
        HttpServletInterceptor.onRequestEnd(req, response(200, 0L));

        io.cloudmeter.collector.RequestMetrics m =
                (io.cloudmeter.collector.RequestMetrics) CloudMeterRegistry.getStore().getAll().get(0);
        assertEquals("GET /api/users/{id}", m.getRouteTemplate());
    }

    @Test
    void egressBytes_setFromContentLengthHeader() {
        Object req = request("/api/export", "GET");
        HttpServletInterceptor.onRequestStart(req);
        HttpServletInterceptor.onRequestEnd(req, response(200, 8192L));

        io.cloudmeter.collector.RequestMetrics m =
                (io.cloudmeter.collector.RequestMetrics) CloudMeterRegistry.getStore().getAll().get(0);
        assertEquals(8192L, m.getEgressBytes());
    }

    @Test
    void httpStatus_capturedCorrectly() {
        Object req = request("/api/missing", "GET");
        HttpServletInterceptor.onRequestStart(req);
        HttpServletInterceptor.onRequestEnd(req, response(404, 0L));

        io.cloudmeter.collector.RequestMetrics m =
                (io.cloudmeter.collector.RequestMetrics) CloudMeterRegistry.getStore().getAll().get(0);
        assertEquals(404, m.getHttpStatusCode());
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    @Test
    void extractRequestURI_returnsPath() {
        assertEquals("/hello", HttpServletInterceptor.extractRequestURI(request("/hello", "GET")));
    }

    @Test
    void extractRequestURI_nullTarget_returnsNull() {
        assertNull(HttpServletInterceptor.extractRequestURI(null));
    }

    @Test
    void extractMethod_returnsVerb() {
        assertEquals("POST", HttpServletInterceptor.extractMethod(request("/x", "POST")));
    }

    @Test
    void extractMethod_noMethod_returnsUnknown() {
        // Object with no getMethod() — falls back to UNKNOWN
        assertEquals("UNKNOWN", HttpServletInterceptor.extractMethod(new Object()));
    }

    @Test
    void extractStatus_returnsCode() {
        assertEquals(201, HttpServletInterceptor.extractStatus(response(201, 0)));
    }

    @Test
    void extractStatus_nullResponse_returnsZero() {
        assertEquals(0, HttpServletInterceptor.extractStatus(null));
    }

    @Test
    void extractContentLength_parsesHeader() {
        assertEquals(1024L, HttpServletInterceptor.extractContentLength(response(200, 1024L)));
    }

    @Test
    void extractContentLength_negativeHeader_returnsZero() {
        assertEquals(0L, HttpServletInterceptor.extractContentLength(response(200, -1L)));
    }

    @Test
    void extractContentLength_nullResponse_returnsZero() {
        assertEquals(0L, HttpServletInterceptor.extractContentLength(null));
    }

    @Test
    void extractSpringRoute_presentsPattern() {
        Object req = requestWithSpringPattern("/api/x/42", "GET", "/api/x/{id}");
        assertEquals("/api/x/{id}", HttpServletInterceptor.extractSpringRoute(req));
    }

    @Test
    void extractSpringRoute_noAttribute_returnsNull() {
        assertNull(HttpServletInterceptor.extractSpringRoute(request("/api/x/42", "GET")));
    }

    @Test
    void invokeNoArg_missingMethod_returnsNull() {
        assertNull(HttpServletInterceptor.invokeNoArg(new Object(), "nonExistentMethod"));
    }

    @Test
    void invokeWithArg_missingMethod_returnsNull() {
        assertNull(HttpServletInterceptor.invokeWithArg(new Object(), "nonExistentMethod", String.class, "x"));
    }

    // ── buildRouteTemplate ────────────────────────────────────────────────────

    @Test
    void buildRouteTemplate_prefixesMethod() {
        io.cloudmeter.collector.RequestContext ctx = new io.cloudmeter.collector.RequestContext(
                "/api/users/7", "/api/users/7", System.nanoTime(), Thread.currentThread().getId());
        String template = HttpServletInterceptor.buildRouteTemplate("DELETE", request("/api/users/7", "DELETE"), ctx);
        assertEquals("DELETE /api/users/{id}", template);
    }

    // ── Anonymous mock helpers ─────────────────────────────────────────────────

    private static Object request(String uri, String method) {
        return new Object() {
            public String getRequestURI() { return uri; }
            public String getMethod()     { return method; }
            public Object getAttribute(String name) { return null; }
        };
    }

    private static Object requestWithSpringPattern(String uri, String method, String pattern) {
        return new Object() {
            public String getRequestURI() { return uri; }
            public String getMethod()     { return method; }
            public Object getAttribute(String name) {
                return HttpServletInterceptor.SPRING_PATTERN_ATTR.equals(name) ? pattern : null;
            }
        };
    }

    private static Object response(int status, long contentLength) {
        return new Object() {
            public int    getStatus() { return status; }
            public String getHeader(String name) {
                return "Content-Length".equals(name) ? String.valueOf(contentLength) : null;
            }
        };
    }
}
