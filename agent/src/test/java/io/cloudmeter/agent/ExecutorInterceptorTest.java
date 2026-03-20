package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;
import io.cloudmeter.collector.RequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorInterceptorTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.clear();
    }

    @Test
    void maybeWrap_nullTask_returnsNull() {
        assertNull(ExecutorInterceptor.maybeWrap(null));
    }

    @Test
    void maybeWrap_noActiveContext_returnsTaskUnchanged() {
        Runnable task = () -> {};
        assertSame(task, ExecutorInterceptor.maybeWrap(task));
    }

    @Test
    void maybeWrap_withActiveContext_wrapsTask() {
        RequestContext ctx = new RequestContext("GET /api/test", "/api/test", System.nanoTime(), Thread.currentThread().getId());
        RequestContextHolder.set(ctx);

        Runnable task = () -> {};
        Runnable result = ExecutorInterceptor.maybeWrap(task);

        assertInstanceOf(ContextPropagatingRunnable.class, result);
        assertSame(ctx, ((ContextPropagatingRunnable) result).getCapturedContext());
    }

    @Test
    void maybeWrap_alreadyWrapped_returnsAsIs() {
        RequestContext ctx = new RequestContext("GET /api/test", "/api/test", System.nanoTime(), Thread.currentThread().getId());
        RequestContextHolder.set(ctx);

        ContextPropagatingRunnable wrapped = new ContextPropagatingRunnable(() -> {});
        assertSame(wrapped, ExecutorInterceptor.maybeWrap(wrapped));
    }

    // ── maybeWrapCallable ─────────────────────────────────────────────────────

    @Test
    void maybeWrapCallable_nullTask_returnsNull() {
        assertNull(ExecutorInterceptor.maybeWrapCallable(null));
    }

    @Test
    void maybeWrapCallable_noActiveContext_returnsTaskUnchanged() {
        java.util.concurrent.Callable<String> task = () -> "x";
        assertSame(task, ExecutorInterceptor.maybeWrapCallable(task));
    }

    @Test
    void maybeWrapCallable_withActiveContext_wrapsTask() {
        RequestContext ctx = new RequestContext("GET /api/test", "/api/test", System.nanoTime(), Thread.currentThread().getId());
        RequestContextHolder.set(ctx);

        java.util.concurrent.Callable<String> task = () -> "x";
        java.util.concurrent.Callable<?> result = ExecutorInterceptor.maybeWrapCallable(task);

        assertInstanceOf(ContextPropagatingCallable.class, result);
        assertSame(ctx, ((ContextPropagatingCallable<?>) result).getCapturedContext());
    }

    @Test
    void maybeWrapCallable_alreadyWrapped_returnsAsIs() {
        RequestContext ctx = new RequestContext("GET /api/test", "/api/test", System.nanoTime(), Thread.currentThread().getId());
        RequestContextHolder.set(ctx);

        ContextPropagatingCallable<String> wrapped = new ContextPropagatingCallable<>(() -> "x");
        assertSame(wrapped, ExecutorInterceptor.maybeWrapCallable(wrapped));
    }
}
