package io.cloudmeter.agent;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestContext;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global runtime registry for the CloudMeter agent.
 *
 * Holds the singleton {@link MetricsStore}, tracks in-flight {@link RequestContext}
 * instances (needed by the background sampler), and determines whether requests
 * fall inside the warmup window (first 30 s after agent start).
 *
 * Thread-safety: all mutable state uses volatile or thread-safe collections.
 */
public final class CloudMeterRegistry {

    /** Warmup window: metrics captured in the first 30 s are flagged and excluded from projections. */
    static final long WARMUP_DURATION_MS = 30_000L;

    private static volatile MetricsStore        store;
    private static volatile long                agentStartMs = System.currentTimeMillis();
    private static volatile ThreadStateCollector sampler;

    private static final Set<RequestContext> activeContexts =
            Collections.newSetFromMap(new ConcurrentHashMap<RequestContext, Boolean>());

    private CloudMeterRegistry() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called once at agent startup. Stores the shared MetricsStore and resets the warmup clock. */
    public static void init(MetricsStore metricsStore) {
        store        = metricsStore;
        agentStartMs = System.currentTimeMillis();
        activeContexts.clear();
    }

    // ── Store access ──────────────────────────────────────────────────────────

    public static MetricsStore getStore() {
        return store;
    }

    // ── Warmup ────────────────────────────────────────────────────────────────

    /** Returns {@code true} while within the 30-second warmup window after agent init. */
    public static boolean isWarmup() {
        return (System.currentTimeMillis() - agentStartMs) < WARMUP_DURATION_MS;
    }

    // ── Active request tracking ───────────────────────────────────────────────

    /** Register an in-flight request context so the background sampler can observe it. */
    public static void registerContext(RequestContext ctx) {
        activeContexts.add(ctx);
    }

    /** Remove an in-flight request context when the request completes. */
    public static void unregisterContext(RequestContext ctx) {
        activeContexts.remove(ctx);
    }

    /** Returns an unmodifiable view of all currently in-flight request contexts. */
    public static Set<RequestContext> getActiveContexts() {
        return Collections.unmodifiableSet(activeContexts);
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    /** Registers the background sampler so reset() can stop it. Package-private. */
    static void setSampler(ThreadStateCollector collector) {
        sampler = collector;
    }

    /** Resets all registry state. Package-private — for tests only. */
    static void reset() {
        ThreadStateCollector s = sampler;
        if (s != null) {
            s.stop();
            sampler = null;
        }
        store        = null;
        agentStartMs = System.currentTimeMillis();
        activeContexts.clear();
    }

    /** Overrides the agent start time. Package-private — for warmup tests only. */
    static void setAgentStartMs(long ms) {
        agentStartMs = ms;
    }
}
