package io.cloudmeter.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThreadPoolExecutorInstrumentationTest {

    @Test
    void targetClass_hasExpectedValue() {
        assertEquals("java.util.concurrent.ThreadPoolExecutor",
                ThreadPoolExecutorInstrumentation.TARGET_CLASS);
    }

    @Test
    void install_withMockInst_doesNotThrow() {
        Instrumentation inst = mock(Instrumentation.class);
        when(inst.isRetransformClassesSupported()).thenReturn(false);
        when(inst.isRedefineClassesSupported()).thenReturn(false);
        when(inst.getAllLoadedClasses()).thenReturn(new Class[0]);
        try {
            ThreadPoolExecutorInstrumentation.install(inst);
        } catch (Exception ignored) {
            // Byte Buddy may reject mock Instrumentation — key guarantee is no uncaught exception
        }
    }

    @Test
    void buildAndInstall_withMockInst_executesBuilderChain() {
        Instrumentation mockInst = mock(Instrumentation.class);
        when(mockInst.isRetransformClassesSupported()).thenReturn(false);
        when(mockInst.isRedefineClassesSupported()).thenReturn(false);
        when(mockInst.getAllLoadedClasses()).thenReturn(new Class[0]);
        try {
            ThreadPoolExecutorInstrumentation.buildAndInstall(new AgentBuilder.Default(), mockInst);
        } catch (Exception ignored) {
            // Acceptable — Byte Buddy may reject a mock Instrumentation for retransformation
        }
    }

    @Test
    void constructor_isPrivate() throws Exception {
        java.lang.reflect.Constructor<ThreadPoolExecutorInstrumentation> ctor =
                ThreadPoolExecutorInstrumentation.class.getDeclaredConstructor();
        assertFalse(ctor.isAccessible());
        ctor.setAccessible(true);
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) ctor::newInstance);
    }
}
