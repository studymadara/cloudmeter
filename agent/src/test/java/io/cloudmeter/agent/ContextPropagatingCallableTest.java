package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;
import io.cloudmeter.collector.RequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class ContextPropagatingCallableTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.clear();
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void constructor_nullDelegate_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ContextPropagatingCallable<>(null));
    }

    @Test
    void constructor_capturesCurrentContext() {
        RequestContext ctx = new RequestContext("GET /test", "/test",
                System.nanoTime(), Thread.currentThread().getId());
        RequestContextHolder.set(ctx);

        ContextPropagatingCallable<String> cpc = new ContextPropagatingCallable<>(() -> "ok");
        assertSame(ctx, cpc.getCapturedContext());
    }

    @Test
    void constructor_noActiveContext_capturesNull() {
        ContextPropagatingCallable<String> cpc = new ContextPropagatingCallable<>(() -> "ok");
        assertNull(cpc.getCapturedContext());
    }

    // ── call() — context propagation ─────────────────────────────────────────

    @Test
    void call_noContext_delegateStillRuns() throws Exception {
        String result = new ContextPropagatingCallable<>(() -> "value").call();
        assertEquals("value", result);
    }

    @Test
    void call_withContext_propagatesContextToWorkerThread() throws Exception {
        RequestContext ctx = new RequestContext("GET /api", "/api",
                System.nanoTime(), Thread.currentThread().getId());
        RequestContextHolder.set(ctx);

        ContextPropagatingCallable<RequestContext> cpc = new ContextPropagatingCallable<>(
                RequestContextHolder::get);

        RequestContextHolder.clear();
        RequestContext seen = cpc.call();

        assertSame(ctx, seen);
    }

    @Test
    void call_restoresPreviousContextAfterRun() throws Exception {
        RequestContext outer = new RequestContext("GET /outer", "/outer",
                System.nanoTime(), Thread.currentThread().getId());
        RequestContext captured = new RequestContext("GET /inner", "/inner",
                System.nanoTime(), Thread.currentThread().getId());

        // Simulate: captured context at submit time is 'captured', worker thread had 'outer'
        RequestContextHolder.set(captured);
        ContextPropagatingCallable<Void> cpc = new ContextPropagatingCallable<>(() -> null);

        RequestContextHolder.set(outer); // worker thread's pre-existing context
        cpc.call();

        assertSame(outer, RequestContextHolder.get()); // worker restored to 'outer'
    }

    @Test
    void call_noContextCaptured_andWorkerHadNone_remainsNull() throws Exception {
        // capturedContext = null (no context at submit time), previous = null (clean worker thread)
        // → finally: clear() → context stays null
        ContextPropagatingCallable<Void> cpc = new ContextPropagatingCallable<>(() -> null);
        assertNull(RequestContextHolder.get()); // precondition
        cpc.call();
        assertNull(RequestContextHolder.get()); // still null after call
    }

    @Test
    void call_noContextCaptured_workerContextRestored() throws Exception {
        // capturedContext = null but worker thread had its own context
        // → finally: set(previous) → worker's context is preserved
        ContextPropagatingCallable<Void> cpc = new ContextPropagatingCallable<>(() -> null);

        RequestContext workerCtx = new RequestContext("GET /worker", "/worker",
                System.nanoTime(), Thread.currentThread().getId());
        RequestContextHolder.set(workerCtx);
        cpc.call();

        // Worker's own context should be restored unchanged
        assertSame(workerCtx, RequestContextHolder.get());
    }

    @Test
    void call_delegateThrows_contextStillCleared() {
        ContextPropagatingCallable<Void> cpc = new ContextPropagatingCallable<>(
                () -> { throw new RuntimeException("boom"); });

        assertThrows(RuntimeException.class, cpc::call);
        assertNull(RequestContextHolder.get());
    }

    // ── constructor is private equivalent — implements Callable ──────────────

    @Test
    void implementsCallable() {
        assertTrue(new ContextPropagatingCallable<>(() -> "x") instanceof Callable);
    }
}
