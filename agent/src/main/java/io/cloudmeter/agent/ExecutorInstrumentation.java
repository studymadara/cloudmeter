package io.cloudmeter.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * Installs Byte Buddy instrumentation for Spring task executors.
 *
 * Targets {@code org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor}
 * and {@code org.springframework.core.task.SimpleAsyncTaskExecutor}, which are the
 * executor implementations used by Spring's {@code @Async} facility. When a request
 * context is active on the submitting thread, the submitted {@link Runnable} is
 * wrapped in a {@link ContextPropagatingRunnable} so that CPU time contributed by
 * async work is attributed to the originating HTTP request.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Spring {@code @Async} — uses {@code ThreadPoolTaskExecutor} by default</li>
 *   <li>Manual {@code executor.execute(runnable)} where the executor is a Spring bean</li>
 * </ul>
 *
 * <p>Not covered (v1 limitation):
 * <ul>
 *   <li>{@code CompletableFuture.supplyAsync()} using the JVM common ForkJoinPool
 *       (bootstrap-loaded; requires bootstrap injection which was removed in ADR-014)</li>
 * </ul>
 */
public final class ExecutorInstrumentation {

    static final String THREAD_POOL_TASK_EXECUTOR =
            "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
    static final String SIMPLE_ASYNC_TASK_EXECUTOR =
            "org.springframework.core.task.SimpleAsyncTaskExecutor";

    private ExecutorInstrumentation() {}

    /**
     * Installs the executor transformer using a fresh {@link AgentBuilder.Default}.
     * Safe to call alongside {@link HttpInstrumentation#install} — each call adds
     * an independent class-file transformer to the {@link Instrumentation}.
     */
    public static void install(Instrumentation inst) {
        buildAndInstall(new AgentBuilder.Default(), inst);
    }

    /** Builds and installs the transformer. Separated for testability. */
    static void buildAndInstall(AgentBuilder base, Instrumentation inst) {
        base.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .type(ElementMatchers.namedOneOf(
                    THREAD_POOL_TASK_EXECUTOR,
                    SIMPLE_ASYNC_TASK_EXECUTOR))
            .transform((builder, typeDescription, classLoader, module, domain) ->
                builder.visit(
                    Advice.to(ExecutorAdvice.class)
                          .on(ElementMatchers.named("execute")
                                            .and(ElementMatchers.takesArguments(Runnable.class)))
                )
            )
            .installOn(inst);
    }
}
