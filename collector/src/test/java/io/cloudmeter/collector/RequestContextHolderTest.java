package io.cloudmeter.collector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextHolderTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.clear();
    }

    @Test
    void get_returnsNull_whenNothingSet() {
        assertNull(RequestContextHolder.get());
    }

    @Test
    void setAndGet_returnsCorrectContext() {
        RequestContext ctx = new RequestContext("GET /x", "/x", 0L, 1L);
        RequestContextHolder.set(ctx);
        assertSame(ctx, RequestContextHolder.get());
    }

    @Test
    void clear_removesContext() {
        RequestContextHolder.set(new RequestContext("GET /x", "/x", 0L, 1L));
        RequestContextHolder.clear();
        assertNull(RequestContextHolder.get());
    }

    @Test
    void contextIsThreadLocal_otherThreadSeesNull() throws InterruptedException {
        RequestContextHolder.set(new RequestContext("GET /x", "/x", 0L, 1L));

        AtomicReference<RequestContext> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            seen.set(RequestContextHolder.get());
            done.countDown();
        });
        t.start();
        done.await();

        assertNull(seen.get(), "Other thread must not see this thread's context");
    }

    @Test
    void set_overwritesPreviousContext() {
        RequestContext first  = new RequestContext("GET /a", "/a", 0L, 1L);
        RequestContext second = new RequestContext("GET /b", "/b", 0L, 2L);
        RequestContextHolder.set(first);
        RequestContextHolder.set(second);
        assertSame(second, RequestContextHolder.get());
    }
}
