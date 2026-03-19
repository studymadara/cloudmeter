package io.cloudmeter.collector;

/**
 * Thread-local holder for the current request's context.
 *
 * Rules:
 *  - set()   called by the Servlet Filter interceptor at request entry.
 *  - clear() MUST be called in a finally block at request exit (including exceptions).
 *  - For async hand-offs, ContextPropagatingRunnable captures + restores the context
 *    on the worker thread; it also calls clear() in its own finally block.
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();

    private RequestContextHolder() {}

    public static void set(RequestContext ctx) {
        CONTEXT.set(ctx);
    }

    public static RequestContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
