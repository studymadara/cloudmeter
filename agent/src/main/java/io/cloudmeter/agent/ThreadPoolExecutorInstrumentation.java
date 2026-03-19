package io.cloudmeter.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * Extends executor context propagation to {@code java.util.concurrent.ThreadPoolExecutor}.
 *
 * This covers CompletableFuture.supplyAsync(supplier, executor) and any other code
 * that submits Runnables to a ThreadPoolExecutor-backed ExecutorService (e.g.
 * Executors.newFixedThreadPool(), newCachedThreadPool(), newSingleThreadExecutor()).
 *
 * Requires bootstrap class injection (see AgentMain.injectBootstrapClasses) so that
 * the advice code referencing ExecutorInterceptor is visible from the bootstrap
 * classloader that executes instrumented JDK classes.
 *
 * Note: CompletableFuture.supplyAsync() with NO executor argument uses ForkJoinPool
 * common pool which submits ForkJoinTask (not Runnable) — not covered here.
 */
public final class ThreadPoolExecutorInstrumentation {

    static final String TARGET_CLASS = "java.util.concurrent.ThreadPoolExecutor";

    private ThreadPoolExecutorInstrumentation() {}

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
                builder.visit(
                    Advice.to(ExecutorAdvice.class)
                          .on(ElementMatchers.named("execute")
                                            .and(ElementMatchers.takesArguments(Runnable.class)))
                )
            )
            .installOn(inst);
    }
}
