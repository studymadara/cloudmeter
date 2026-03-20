package io.cloudmeter.agent;

import net.bytebuddy.asm.Advice;

import java.util.concurrent.Callable;

/**
 * Byte Buddy {@link Advice} class for methods that accept a {@link Callable} argument.
 *
 * Inlined by Byte Buddy into the target method's bytecode at agent install time.
 * Used by {@link ForkJoinPoolInstrumentation} to wrap {@code ForkJoinPool.submit(Callable)}
 * so that request context is propagated to callable tasks (e.g. via explicit executor submit).
 *
 * Exceptions are suppressed (ADR-010): a CloudMeter bug must never affect the app.
 */
public final class CallableAdvice {

    private CallableAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @SuppressWarnings("rawtypes")
    public static void onEnter(
            @Advice.Argument(value = 0, readOnly = false) Callable task) {
        task = ExecutorInterceptor.maybeWrapCallable(task);
    }
}
