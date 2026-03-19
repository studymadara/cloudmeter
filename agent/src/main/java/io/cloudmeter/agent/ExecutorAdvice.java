package io.cloudmeter.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy {@link Advice} class for {@code TaskExecutor.execute(Runnable)}.
 *
 * Inlined by Byte Buddy into the target method's bytecode at agent install time.
 * The wrapping logic lives in {@link ExecutorInterceptor} for independent testability.
 *
 * The {@code readOnly = false} on the argument annotation tells Byte Buddy that
 * the local variable is writable; the assignment propagates back to the actual
 * argument slot so the original method receives the wrapped Runnable.
 *
 * Exceptions are suppressed (ADR-010): a CloudMeter bug must never affect the app.
 */
public final class ExecutorAdvice {

    private ExecutorAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(value = 0, readOnly = false) Runnable task) {
        task = ExecutorInterceptor.maybeWrap(task);
    }
}
