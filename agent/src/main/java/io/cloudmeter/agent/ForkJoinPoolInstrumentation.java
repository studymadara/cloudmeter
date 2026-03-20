package io.cloudmeter.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Callable;

/**
 * Extends executor context propagation to {@code java.util.concurrent.ForkJoinPool}.
 *
 * This covers the remaining async gap from {@link ThreadPoolExecutorInstrumentation}:
 * {@code CompletableFuture.supplyAsync(supplier)} with NO explicit executor submits work to
 * the ForkJoinPool common pool, which this class intercepts.
 *
 * Instrumented methods:
 * <ul>
 *   <li>{@code execute(Runnable)}  — wraps Runnable via {@link ExecutorAdvice}</li>
 *   <li>{@code submit(Runnable)}   — wraps Runnable via {@link ExecutorAdvice}</li>
 *   <li>{@code submit(Callable)}   — wraps Callable via {@link CallableAdvice}</li>
 * </ul>
 *
 * Requires bootstrap class injection (see {@link AgentMain#injectBootstrapClasses}) so that
 * {@link ExecutorInterceptor} and {@link ContextPropagatingCallable} are visible from the
 * bootstrap classloader that loads JDK classes.
 */
public final class ForkJoinPoolInstrumentation {

    static final String TARGET_CLASS = "java.util.concurrent.ForkJoinPool";

    private ForkJoinPoolInstrumentation() {}

    public static void install(Instrumentation inst) {
        buildAndInstall(new AgentBuilder.Default(), inst);
    }

    static void buildAndInstall(AgentBuilder base, Instrumentation inst) {
        base.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .ignore(ElementMatchers.none())
            .type(ElementMatchers.named(TARGET_CLASS))
            .transform((builder, typeDescription, classLoader, module, domain) ->
                applyTransforms(builder))
            .installOn(inst);
    }

    /**
     * Applies all three ForkJoinPool advice visits to the given builder.
     * Extracted for independent testability (the transform lambda is only invoked
     * by Byte Buddy when it actually processes a matching class).
     */
    static net.bytebuddy.dynamic.DynamicType.Builder<?> applyTransforms(
            net.bytebuddy.dynamic.DynamicType.Builder<?> builder) {
        return builder
            .visit(Advice.to(ExecutorAdvice.class)
                .on(ElementMatchers.named("execute")
                    .and(ElementMatchers.takesArguments(Runnable.class))))
            .visit(Advice.to(ExecutorAdvice.class)
                .on(ElementMatchers.named("submit")
                    .and(ElementMatchers.takesArguments(Runnable.class))))
            .visit(Advice.to(CallableAdvice.class)
                .on(ElementMatchers.named("submit")
                    .and(ElementMatchers.takesArguments(Callable.class))));
    }
}
