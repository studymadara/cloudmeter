package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;
import io.cloudmeter.collector.RequestContextHolder;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Callable;

/**
 * Wraps a {@link Callable} to propagate the current {@link RequestContext} to the
 * worker thread that executes it.
 *
 * Mirrors {@link ContextPropagatingRunnable} for the Callable case — covers
 * {@code ForkJoinPool.submit(Callable)} and similar patterns where async work
 * returns a value (e.g. {@code CompletableFuture.supplyAsync(supplier)} with a
 * custom executor that accepts Callables).
 *
 * @param <V> the result type of the wrapped callable
 */
public final class ContextPropagatingCallable<V> implements Callable<V> {

    private final RequestContext capturedContext;
    private final Callable<V>   delegate;

    /**
     * Constructs a propagating wrapper.
     * The current thread's {@link RequestContext} is captured at this point (submission time).
     *
     * @param delegate the actual work to run on the worker thread
     */
    public ContextPropagatingCallable(Callable<V> delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.capturedContext = RequestContextHolder.get();
        this.delegate        = delegate;
    }

    @Override
    public V call() throws Exception {
        RequestContext previous = RequestContextHolder.get();

        if (capturedContext != null) {
            RequestContextHolder.set(capturedContext);
            long threadId = Thread.currentThread().getId();
            capturedContext.recordThreadCpuStart(threadId, currentThreadCpuTime());
        }

        try {
            return delegate.call();
        } finally {
            if (capturedContext != null) {
                long threadId = Thread.currentThread().getId();
                capturedContext.finalizeThreadCpu(threadId, currentThreadCpuTime());
            }
            if (previous != null) {
                RequestContextHolder.set(previous);
            } else {
                RequestContextHolder.clear();
            }
        }
    }

    /** Returns the context captured at construction time (may be null). */
    RequestContext getCapturedContext() {
        return capturedContext;
    }

    private static long currentThreadCpuTime() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isCurrentThreadCpuTimeSupported()) return 0L;
        long t = bean.getCurrentThreadCpuTime();
        return t < 0 ? 0L : t;
    }
}
