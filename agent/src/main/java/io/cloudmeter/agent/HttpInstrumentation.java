package io.cloudmeter.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

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
     * Injects the agent JAR into the bootstrap classloader and installs Byte Buddy transformers.
     * Called once from {@link AgentMain#doInitialize}.
     */
    public static void install(Instrumentation inst) {
        injectBootstrap(inst);
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

    /**
     * Appends the agent JAR to the bootstrap classloader search path so that CloudMeter
     * classes (collector, registry, etc.) are visible from all application classloaders.
     * This is required for the inlined advice bytecode to resolve our types at runtime.
     *
     * Silently skips if the agent is running from an exploded directory (e.g. during tests).
     */
    static void injectBootstrap(Instrumentation inst) {
        try {
            ProtectionDomain pd = HttpInstrumentation.class.getProtectionDomain();
            if (pd == null || pd.getCodeSource() == null) return;
            URL location = pd.getCodeSource().getLocation();
            if (location == null) return;
            File agentJar = new File(location.toURI());
            if (agentJar.isFile()) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
                CloudMeterLogger.info("Agent JAR injected into bootstrap classloader: " + agentJar);
            }
        } catch (Throwable t) {
            CloudMeterLogger.warn("Bootstrap injection skipped: " + t.getMessage());
        }
    }
}
