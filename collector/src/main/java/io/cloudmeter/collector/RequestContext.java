package io.cloudmeter.collector;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable context accumulated while a request is in-flight.
 *
 * Thread-safety: multiple threads may write cpuNanosAccumulated and register
 * themselves in activeThreadIds (async case). All mutable state uses
 * thread-safe primitives.
 */
public final class RequestContext {

    private final String    routeTemplate;
    private final String    actualPath;
    private final long      startNanos;
    private final long      originThreadId;

    /** Accumulated CPU nanoseconds across all threads serving this request. */
    private final AtomicLong cpuNanosAccumulated = new AtomicLong(0L);

    /** CPU nanos snapshot taken at the start of each thread's work on this request. */
    private final ConcurrentHashMap<Long, Long> threadCpuStartNanos = new ConcurrentHashMap<>();

    /** All thread IDs currently active on this request (for state sampling). */
    private final Set<Long> activeThreadIds =
            Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    /** Accumulated WAITING/BLOCKED sample count (for threadWaitRatio). */
    private final AtomicLong waitSamples    = new AtomicLong(0L);

    /** Total sample count across all threads (for threadWaitRatio denominator). */
    private final AtomicLong totalSamples   = new AtomicLong(0L);

    /** Peak memory in bytes observed during this request (written by sampler). */
    private final AtomicLong peakMemoryBytes = new AtomicLong(0L);

    public RequestContext(String routeTemplate, String actualPath,
                          long startNanos, long originThreadId) {
        this.routeTemplate  = Objects.requireNonNull(routeTemplate,  "routeTemplate");
        this.actualPath     = Objects.requireNonNull(actualPath,     "actualPath");
        this.startNanos     = startNanos;
        this.originThreadId = originThreadId;
    }

    // ── Route info ────────────────────────────────────────────────────────────

    public String getRouteTemplate()   { return routeTemplate; }
    public String getActualPath()      { return actualPath; }
    public long   getStartNanos()      { return startNanos; }
    public long   getOriginThreadId()  { return originThreadId; }

    // ── CPU tracking ──────────────────────────────────────────────────────────

    /** Record the CPU-time snapshot for a thread when it starts work on this request. */
    public void recordThreadCpuStart(long threadId, long cpuNanos) {
        threadCpuStartNanos.put(threadId, cpuNanos);
        activeThreadIds.add(threadId);
    }

    /**
     * Finalize a thread's CPU contribution when it finishes its work.
     * If no start snapshot exists (CPU time unsupported) the delta is ignored.
     */
    public void finalizeThreadCpu(long threadId, long cpuNanosNow) {
        Long start = threadCpuStartNanos.remove(threadId);
        if (start != null) {
            long delta = cpuNanosNow - start;
            if (delta > 0) {
                cpuNanosAccumulated.addAndGet(delta);
            }
        }
        activeThreadIds.remove(threadId);
    }

    /** Total CPU nanoseconds accumulated so far across all threads. */
    public long getCpuNanosAccumulated() {
        return cpuNanosAccumulated.get();
    }

    public Set<Long> getActiveThreadIds() {
        return Collections.unmodifiableSet(activeThreadIds);
    }

    // ── Thread state sampling ─────────────────────────────────────────────────

    public void recordSample(boolean waiting) {
        totalSamples.incrementAndGet();
        if (waiting) {
            waitSamples.incrementAndGet();
        }
    }

    /** Returns 0.0 if no samples recorded yet. */
    public double getThreadWaitRatio() {
        long total = totalSamples.get();
        if (total == 0L) return 0.0;
        return (double) waitSamples.get() / total;
    }

    // ── Memory tracking ───────────────────────────────────────────────────────

    public void updatePeakMemory(long bytes) {
        long current;
        do {
            current = peakMemoryBytes.get();
            if (bytes <= current) break;
        } while (!peakMemoryBytes.compareAndSet(current, bytes));
    }

    public long getPeakMemoryBytes() {
        return peakMemoryBytes.get();
    }
}
