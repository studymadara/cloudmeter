package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Set;

/**
 * Background daemon thread that samples the JVM thread states of all in-flight requests.
 *
 * Every {@code intervalMs} milliseconds the sampler polls each thread that is currently
 * serving a request via {@link CloudMeterRegistry#getActiveContexts()}. It records:
 * <ul>
 *   <li>Whether the thread is WAITING / TIMED_WAITING / BLOCKED (idle compute waste)
 *       via {@link RequestContext#recordSample(boolean)}</li>
 *   <li>Current JVM heap usage as a candidate peak-memory watermark
 *       via {@link RequestContext#updatePeakMemory(long)}</li>
 * </ul>
 *
 * The thread is a daemon so it does not prevent JVM shutdown.
 * Thread state sampling is probabilistic — see arc42 Section 11 for the documented caveat.
 */
public final class ThreadStateCollector {

    private final ThreadMXBean threadMXBean;
    private final MemoryMXBean memoryMXBean;
    private final long         intervalMs;

    private volatile Thread  samplerThread;
    private volatile boolean running;

    /** Production constructor — uses platform MXBeans. */
    public ThreadStateCollector(long intervalMs) {
        this(ManagementFactory.getThreadMXBean(),
             ManagementFactory.getMemoryMXBean(),
             intervalMs);
    }

    /** Testing constructor — accepts injectable MXBeans. */
    ThreadStateCollector(ThreadMXBean threadMXBean, MemoryMXBean memoryMXBean, long intervalMs) {
        this.threadMXBean = threadMXBean;
        this.memoryMXBean = memoryMXBean;
        this.intervalMs   = intervalMs;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Starts the background sampler daemon thread. */
    public void start() {
        running = true;
        samplerThread = new Thread(this::samplerLoop, "cloudmeter-sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    /** Signals the sampler to stop and interrupts its sleep. */
    public void stop() {
        running = false;
        Thread t = samplerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Returns {@code true} if the sampler thread is alive. */
    public boolean isRunning() {
        Thread t = samplerThread;
        return t != null && t.isAlive();
    }

    // ── Sampling ──────────────────────────────────────────────────────────────

    private void samplerLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                sample();
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Performs one sampling round across all active request contexts.
     * Package-private for direct invocation in tests.
     */
    void sample() {
        long heapUsed = currentHeapUsed();

        Set<RequestContext> contexts = CloudMeterRegistry.getActiveContexts();
        for (RequestContext ctx : contexts) {
            // Update peak memory watermark for each in-flight request
            ctx.updatePeakMemory(heapUsed);

            // Sample thread states
            for (Long threadId : ctx.getActiveThreadIds()) {
                ThreadInfo info = threadMXBean.getThreadInfo(threadId);
                if (info != null) {
                    Thread.State state = info.getThreadState();
                    boolean waiting = state == Thread.State.WAITING
                                   || state == Thread.State.TIMED_WAITING
                                   || state == Thread.State.BLOCKED;
                    ctx.recordSample(waiting);
                }
            }
        }
    }

    private long currentHeapUsed() {
        try {
            java.lang.management.MemoryUsage usage = memoryMXBean.getHeapMemoryUsage();
            return usage != null ? usage.getUsed() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
