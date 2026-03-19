package io.cloudmeter.smoketest;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service that does CPU-heavy work on an @Async thread pool thread.
 * Used to verify that CloudMeter's ExecutorInstrumentation correctly
 * propagates the request context to async threads so CPU time is
 * attributed to the originating HTTP request.
 */
@Service
public class AsyncWorkService {

    /**
     * Runs ~25,000 tight-loop iterations on a Spring executor thread.
     * The result is returned as a CompletableFuture so the calling
     * servlet thread can block on .get() — that blocking is what allows
     * onRequestEnd() to see the accumulated async CPU after this method
     * finalizes its contribution to the RequestContext.
     */
    @Async
    public CompletableFuture<Long> doHeavyWork() {
        long acc = ThreadLocalRandom.current().nextLong();
        for (int i = 0; i < 5_000_000; i++) {
            acc = acc * 6364136223846793005L + 1442695040888963407L;
        }
        return CompletableFuture.completedFuture(acc);
    }
}
