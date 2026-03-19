package io.cloudmeter.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy {@link Advice} class for {@code HttpServlet.service(HttpServletRequest, HttpServletResponse)}.
 *
 * This class is inlined by Byte Buddy into the target method's bytecode at agent install time.
 * The actual tracking logic lives in {@link HttpServletInterceptor}, which is tested independently
 * and remains callable without a running Byte Buddy agent.
 *
 * Both methods suppress exceptions ({@code suppress = Throwable.class}) so that any unexpected
 * failure in the advice itself can never affect the user application (ADR-010).
 */
public final class HttpServletAdvice {

    private HttpServletAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object request) {
        HttpServletInterceptor.onRequestStart(request);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response) {
        HttpServletInterceptor.onRequestEnd(request, response);
    }
}
