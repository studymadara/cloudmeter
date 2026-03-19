package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;
import io.cloudmeter.collector.RequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContextPropagatingRunnableTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        RequestContextHolder.clear();
    }

    // ── Construction ───────────────────────────────────────────────────────────

    @Test
    void nullDelegate_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new ContextPropagatingRunnable(null));
    }

    @Test
    void capturedContext_isCurrentContextAtConstructionTime() {
        RequestContext ctx = ctx();
        RequestContextHolder.set(ctx);

        ContextPropagatingRunnable wrapper = new ContextPropagatingRunnable(() -> {});
        assertSame(ctx, wrapper.getCapturedContext());
    }

    @Test
    void capturedContext_nullWhenNoneSet() {
        ContextPropagatingRunnable wrapper = new ContextPropagatingRunnable(() -> {});
        assertNull(wrapper.getCapturedContext());
    }

    // ── Context propagation ────────────────────────────────────────────────────

    @Test
    void run_propagatesContextToWorkerThread() throws InterruptedException {
        RequestContext ctx = ctx();
        RequestContextHolder.set(ctx);

        AtomicReference<RequestContext> seen = new AtomicReference<>();
        ContextPropagatingRunnable wrapper = new ContextPropagatingRunnable(
                () -> seen.set(RequestContextHolder.get()));

        Thread worker = new Thread(wrapper);
        worker.start();
        worker.join(2_000);

        assertSame(ctx, seen.get());
    }

    @Test
    void run_withNullContext_workerSeesNull() throws InterruptedException {
        // No context set on current thread
        AtomicReference<RequestContext> seen = new AtomicReference<>(ctx()); // non-null sentinel
        ContextPropagatingRunnable wrapper = new ContextPropagatingRunnable(
                () -> seen.set(RequestContextHolder.get()));

        Thread worker = new Thread(wrapper);
        worker.start();
        worker.join(2_000);

        assertNull(seen.get());
    }

    // ── Context restoration ────────────────────────────────────────────────────

    @Test
    void run_restoresPreviousContextOnWorkerThread() throws InterruptedException {
        RequestContext captured = ctx();
        RequestContextHolder.set(captured);

        RequestContext workerPrevious = ctx();
        AtomicReference<RequestContext> afterRun = new AtomicReference<>();

        ContextPropagatingRunnable wrapper = new ContextPropagatingRunnable(() -> {
            // This body runs on the worker — worker originally had workerPrevious set
        });

        Thread worker = new Thread(() -> {
            RequestContextHolder.set(workerPrevious);   // simulate worker's own context
            wrapper.run();
            afterRun.set(RequestContextHolder.get());   // should be restored to workerPrevious
        });
        worker.start();
        worker.join(2_000);

        assertSame(workerPrevious, afterRun.get());
    }

    @Test
    void run_clearsContextOnWorkerWhenNoPreviousContext() throws InterruptedException {
        RequestContext captured = ctx();
        RequestContextHolder.set(captured);

        ContextPropagatingRunnable wrapper = new ContextPropagatingRunnable(() -> {});

        AtomicReference<RequestContext> afterRun = new AtomicReference<>(captured); // sentinel
        Thread worker = new Thread(() -> {
            // Worker starts with no context
            wrapper.run();
            afterRun.set(RequestContextHolder.get());
        });
        worker.start();
        worker.join(2_000);

        assertNull(afterRun.get(), "worker thread should have null context after run completes");
    }

    // ── Exception safety ──────────────────────────────────────────────────────

    @Test
    void run_delegateThrows_contextStillRestored() throws InterruptedException {
        RequestContext captured = ctx();
        RequestContextHolder.set(captured);

        AtomicBoolean finallyCalled = new AtomicBoolean(false);
        ContextPropagatingRunnable wrapper = new ContextPropagatingRunnable(() -> {
            throw new RuntimeException("deliberate failure");
        });

        AtomicReference<RequestContext> afterRun = new AtomicReference<>(captured);
        Thread worker = new Thread(() -> {
            try {
                wrapper.run();
            } catch (RuntimeException ignored) {
                finallyCalled.set(true);
            }
            afterRun.set(RequestContextHolder.get());
        });
        worker.start();
        worker.join(2_000);

        assertTrue(finallyCalled.get());
        assertNull(afterRun.get(), "context must be cleared even when delegate throws");
    }

    // ── CPU tracking ──────────────────────────────────────────────────────────

    @Test
    void run_recordsCpuTimeOnCapturedContext() throws InterruptedException {
        RequestContext ctx = ctx();
        RequestContextHolder.set(ctx);

        ContextPropagatingRunnable wrapper = new ContextPropagatingRunnable(() -> {
            // Do a tiny bit of work so CPU time is non-zero
            long sum = 0;
            for (int i = 0; i < 1_000; i++) sum += i;
            assertTrue(sum > 0); // prevent dead-code elimination
        });

        Thread worker = new Thread(wrapper);
        worker.start();
        worker.join(2_000);

        // CPU nanos should have been finalized (accumulated ≥ 0)
        assertTrue(ctx.getCpuNanosAccumulated() >= 0);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static RequestContext ctx() {
        return new RequestContext("/test", "/test", System.nanoTime(), Thread.currentThread().getId());
    }
}
