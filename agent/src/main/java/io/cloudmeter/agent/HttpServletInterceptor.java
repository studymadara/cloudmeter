package io.cloudmeter.agent;

import io.cloudmeter.collector.MetricsStore;
import io.cloudmeter.collector.RequestContext;
import io.cloudmeter.collector.RequestContextHolder;
import io.cloudmeter.collector.RequestMetrics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;

/**
 * Stateless interceptor logic for HTTP servlet requests.
 *
 * This class contains the actual request-tracking logic; it is called from
 * {@link HttpServletAdvice} (inlined by Byte Buddy) and can also be called directly
 * in unit tests without needing a live Byte Buddy agent.
 *
 * All public methods catch {@link Throwable} internally — ADR-010 guarantees that
 * CloudMeter never propagates failures into user code.
 *
 * Reflection is used for all Servlet API access so that the agent works with both
 * {@code javax.servlet} and {@code jakarta.servlet} runtimes without a compile-time
 * dependency on either.
 */
public final class HttpServletInterceptor {

    /** Spring MVC request attribute that holds the matched route template. */
    static final String SPRING_PATTERN_ATTR =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    private HttpServletInterceptor() {}

    // ── Entry hook ────────────────────────────────────────────────────────────

    /**
     * Called at the beginning of {@code HttpServlet.service()}.
     * Creates a {@link RequestContext}, registers it globally, and starts CPU tracking.
     *
     * @param request the {@code HttpServletRequest} (typed as Object to avoid Servlet API coupling)
     */
    public static void onRequestStart(Object request) {
        try {
            // Skip if already inside a nested dispatch (servlet forward / include)
            if (RequestContextHolder.get() != null) return;

            String path    = extractRequestURI(request);
            long   threadId   = Thread.currentThread().getId();
            long   startNanos = System.nanoTime();

            RequestContext ctx = new RequestContext(
                    path != null ? path : "UNKNOWN",
                    path != null ? path : "UNKNOWN",
                    startNanos,
                    threadId);

            RequestContextHolder.set(ctx);
            CloudMeterRegistry.registerContext(ctx);
            ctx.recordThreadCpuStart(threadId, currentThreadCpuTime());
        } catch (Throwable t) {
            // ADR-010: never let agent failures propagate to user code
        }
    }

    // ── Exit hook ─────────────────────────────────────────────────────────────

    /**
     * Called at the end of {@code HttpServlet.service()} (including exceptional exits).
     * Finalizes CPU tracking, builds a {@link RequestMetrics} snapshot, and adds it to
     * the {@link MetricsStore}.
     *
     * @param request  the {@code HttpServletRequest}
     * @param response the {@code HttpServletResponse}
     */
    public static void onRequestEnd(Object request, Object response) {
        RequestContext ctx = RequestContextHolder.get();
        try {
            if (ctx == null) return;

            long threadId = Thread.currentThread().getId();
            ctx.finalizeThreadCpu(threadId, currentThreadCpuTime());

            long   durationMs    = (System.nanoTime() - ctx.getStartNanos()) / 1_000_000L;
            String method        = extractMethod(request);
            String routeTemplate = buildRouteTemplate(method, request, ctx);
            int    status        = extractStatus(response);
            long   egressBytes   = extractContentLength(response);

            RequestMetrics metrics = RequestMetrics.builder()
                    .routeTemplate(routeTemplate)
                    .actualPath(ctx.getActualPath())
                    .httpStatusCode(status)
                    .httpMethod(method)
                    .durationMs(durationMs)
                    .cpuCoreSeconds(ctx.getCpuNanosAccumulated() / 1_000_000_000.0)
                    .peakMemoryBytes(ctx.getPeakMemoryBytes())
                    .egressBytes(egressBytes)
                    .threadWaitRatio(ctx.getThreadWaitRatio())
                    .warmup(CloudMeterRegistry.isWarmup())
                    .build();

            MetricsStore store = CloudMeterRegistry.getStore();
            if (store != null) {
                store.add(metrics);
            }
        } catch (Throwable t) {
            // ADR-010
        } finally {
            RequestContextHolder.clear();
            if (ctx != null) {
                CloudMeterRegistry.unregisterContext(ctx);
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Builds the route template string: tries the Spring MVC attribute first,
     * then falls back to heuristic normalization of the raw path.
     */
    static String buildRouteTemplate(String method, Object request, RequestContext ctx) {
        String springPattern = extractSpringRoute(request);
        String path = (springPattern != null) ? springPattern : RouteNormalizer.normalize(ctx.getActualPath());
        return method + " " + path;
    }

    /** Returns the request URI, or {@code null} on failure. */
    static String extractRequestURI(Object request) {
        return (String) invokeNoArg(request, "getRequestURI");
    }

    /** Returns the HTTP method (GET, POST, …) or {@code "UNKNOWN"}. */
    static String extractMethod(Object request) {
        String m = (String) invokeNoArg(request, "getMethod");
        return m != null ? m : "UNKNOWN";
    }

    /** Returns the HTTP response status code, or {@code 0} if unavailable. */
    static int extractStatus(Object response) {
        Object status = invokeNoArg(response, "getStatus");
        return status instanceof Integer ? (Integer) status : 0;
    }

    /**
     * Returns the Content-Length response header value as egress bytes,
     * or {@code 0} if the header is absent or unparseable.
     */
    static long extractContentLength(Object response) {
        Object header = invokeWithArg(response, "getHeader", String.class, "Content-Length");
        if (header instanceof String) {
            try {
                long v = Long.parseLong(((String) header).trim());
                return v >= 0 ? v : 0L;
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return 0L;
    }

    /**
     * Returns the Spring MVC route template from the request attribute, or {@code null}
     * if not running under Spring MVC or attribute not yet set.
     */
    static String extractSpringRoute(Object request) {
        Object pattern = invokeWithArg(request, "getAttribute", String.class, SPRING_PATTERN_ATTR);
        return (pattern instanceof String && !((String) pattern).isEmpty())
                ? (String) pattern
                : null;
    }

    /** Invokes a public no-arg method on target via reflection; returns null on any failure. */
    static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invokes a single-arg public method on target via reflection; returns null on any failure. */
    static Object invokeWithArg(Object target, String methodName, Class<?> paramType, Object arg) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName, paramType);
            return m.invoke(target, arg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns the current thread's CPU time in nanoseconds, or 0 if unsupported. */
    static long currentThreadCpuTime() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isCurrentThreadCpuTimeSupported()) return 0L;
        long t = bean.getCurrentThreadCpuTime();
        return t < 0 ? 0L : t;
    }
}
