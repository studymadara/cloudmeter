package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;
import io.cloudmeter.collector.RequestContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorAdviceTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.clear();
    }

    @Test
    void constructor_isPrivate() throws Exception {
        java.lang.reflect.Constructor<ExecutorAdvice> ctor =
                ExecutorAdvice.class.getDeclaredConstructor();
        assertFalse(ctor.isAccessible());
        ctor.setAccessible(true);
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) ctor::newInstance);
    }

    @Test
    void onEnter_withNoContext_doesNotThrow() {
        assertDoesNotThrow(() -> ExecutorAdvice.onEnter(() -> {}));
    }

    @Test
    void onEnter_withActiveContext_doesNotThrow() {
        RequestContext ctx = new RequestContext("GET /api", "/api",
                System.nanoTime(), Thread.currentThread().getId());
        RequestContextHolder.set(ctx);
        assertDoesNotThrow(() -> ExecutorAdvice.onEnter(() -> {}));
    }

    @Test
    void onEnter_withNull_doesNotThrow() {
        assertDoesNotThrow(() -> ExecutorAdvice.onEnter(null));
    }
}
