package io.cloudmeter.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * Installs Byte Buddy HTTP instrumentation into the running JVM.
 *
 * Targets both the legacy {@code javax.servlet.http.HttpServlet} (Servlet containers up to 4.x,
 * Spring Boot 2.x) and the modern {@code jakarta.servlet.http.HttpServlet} (Servlet 5.x+,
 * Spring Boot 3.x). Only the class actually present in the JVM is transformed.
 *
 * The two-argument {@code service(*, *)} overload is intercepted so that the agent sees every
 * fully parsed HTTP request (typed HttpServletRequest + HttpServletResponse) rather than the
 * raw {@code service(ServletRequest, ServletResponse)} fallback.
 */
public final class HttpInstrumentation {

    static final String JAVAX_SERVLET   = "javax.servlet.http.HttpServlet";
    static final String JAKARTA_SERVLET = "jakarta.servlet.http.HttpServlet";
    static final String SERVICE_METHOD  = "service";

    private HttpInstrumentation() {}

    /**
     * Installs Byte Buddy transformers.
     * Called once from {@link AgentMain#doInitialize}.
     *
     * Note: bootstrap injection is intentionally skipped. HttpServlet is loaded by the
     * application classloader which delegates to the system classloader — agent types
     * on the system classpath are therefore visible to the inlined advice bytecode
     * without needing bootstrap-level visibility.
     */
    public static void install(Instrumentation inst) {
        buildAndInstall(new AgentBuilder.Default(), inst);
    }

    /** Builds the Byte Buddy agent and installs it. Separated for testability. */
    static void buildAndInstall(AgentBuilder base, Instrumentation inst) {
        base.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .type(ElementMatchers.namedOneOf(JAVAX_SERVLET, JAKARTA_SERVLET))
            .transform((builder, typeDescription, classLoader, module, domain) ->
                builder.visit(
                    Advice.to(HttpServletAdvice.class)
                          .on(ElementMatchers.named(SERVICE_METHOD)
                                            .and(ElementMatchers.takesArguments(2)))
                )
            )
            .installOn(inst);
    }


}
