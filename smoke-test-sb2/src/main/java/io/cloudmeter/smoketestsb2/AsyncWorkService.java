package io.cloudmeter.smoketestsb2;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AsyncWorkService {

    @Async
    public CompletableFuture<Long> doHeavyWork() {
        long acc = ThreadLocalRandom.current().nextLong();
        for (int i = 0; i < 5_000_000; i++) {
            acc = acc * 6364136223846793005L + 1442695040888963407L;
        }
        return CompletableFuture.completedFuture(acc);
    }
}
