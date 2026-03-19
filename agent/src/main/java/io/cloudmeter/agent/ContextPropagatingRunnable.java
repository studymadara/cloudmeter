package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;
import io.cloudmeter.collector.RequestContextHolder;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Wraps a {@link Runnable} to propagate the current {@link RequestContext} to the
 * worker thread that executes it.
 *
 * This covers async hand-offs where the originating thread hands work to an executor
 * (Spring {@code @Async}, {@code CompletableFuture}, {@code DeferredResult}).
 * Without this wrapper, the {@link RequestContextHolder} ThreadLocal would be empty
 * on the worker thread and the CPU time contributed by async work would be lost.
 *
 * Usage:
 * <pre>
 *   executor.submit(new ContextPropagatingRunnable(myRunnable));
 * </pre>
 *
 * Behaviour:
 * <ul>
 *   <li>Captures the {@link RequestContext} present on the submitting thread at construction time.</li>
 *   <li>On the worker thread: sets the context, records CPU start, runs the delegate.</li>
 *   <li>In a finally block: finalizes CPU contribution, then restores the worker thread's
 *       previous context (or clears if there was none).</li>
 * </ul>
 */
public final class ContextPropagatingRunnable implements Runnable {

    private final RequestContext capturedContext;
    private final Runnable       delegate;

    /**
     * Constructs a propagating wrapper.
     * The current thread's {@link RequestContext} is captured at this point (submission time).
     *
     * @param delegate the actual work to run on the worker thread
     */
    public ContextPropagatingRunnable(Runnable delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.capturedContext = RequestContextHolder.get();   // snapshot at submit-time
        this.delegate        = delegate;
    }

    @Override
    public void run() {
        RequestContext previous = RequestContextHolder.get();

        if (capturedContext != null) {
            RequestContextHolder.set(capturedContext);
            long threadId = Thread.currentThread().getId();
            capturedContext.recordThreadCpuStart(threadId, currentThreadCpuTime());
        }

        try {
            delegate.run();
        } finally {
            if (capturedContext != null) {
                long threadId = Thread.currentThread().getId();
                capturedContext.finalizeThreadCpu(threadId, currentThreadCpuTime());
            }
            // Restore worker thread to its previous state
            if (previous != null) {
                RequestContextHolder.set(previous);
            } else {
                RequestContextHolder.clear();
            }
        }
    }

    /** Returns the context that was captured at construction time (may be null). */
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
