package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;
import io.cloudmeter.collector.RequestContextHolder;

import java.util.concurrent.Callable;

/**
 * Intercept logic for executor task submission.
 *
 * Called from {@link ExecutorAdvice} and {@link CallableAdvice} (inlined by Byte Buddy into
 * executor methods). Separated from the Advice classes so it can be tested independently
 * without a running Byte Buddy agent.
 *
 * If a {@link RequestContext} is active on the submitting thread, the task is wrapped in a
 * {@link ContextPropagatingRunnable} / {@link ContextPropagatingCallable} so that CPU time
 * contributed by async work is attributed to the originating request.
 *
 * Idempotent: already-wrapped tasks are returned as-is.
 */
public final class ExecutorInterceptor {

    private ExecutorInterceptor() {}

    /**
     * Returns a {@link ContextPropagatingRunnable} wrapping {@code task} when a
     * request context is active on the calling thread, or {@code task} unchanged
     * when there is no active context or the task is already wrapped.
     *
     * @param task the Runnable being submitted to an executor (may be null)
     * @return the (possibly wrapped) Runnable
     */
    public static Runnable maybeWrap(Runnable task) {
        if (task == null || task instanceof ContextPropagatingRunnable) return task;
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null) return task;
        return new ContextPropagatingRunnable(task);
    }

    /**
     * Returns a {@link ContextPropagatingCallable} wrapping {@code task} when a
     * request context is active on the calling thread, or {@code task} unchanged
     * when there is no active context or the task is already wrapped.
     *
     * @param task the Callable being submitted to an executor (may be null)
     * @return the (possibly wrapped) Callable
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Callable maybeWrapCallable(Callable task) {
        if (task == null || task instanceof ContextPropagatingCallable) return task;
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null) return task;
        return new ContextPropagatingCallable<>(task);
    }
}
