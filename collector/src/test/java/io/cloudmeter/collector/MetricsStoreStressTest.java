package io.cloudmeter.collector;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent stress tests for MetricsStore.
 * Validates thread-safety under heavy parallel write and read load.
 */
class MetricsStoreStressTest {

    private static final int THREADS      = 16;
    private static final int WRITES_EACH  = 500;
    private static final int CAPACITY     = 1_000;

    private static RequestMetrics metric(int i) {
        return RequestMetrics.builder()
                .routeTemplate("GET /route/" + (i % 10))
                .actualPath("/route/" + i)
                .httpStatusCode(200)
                .httpMethod("GET")
                .durationMs(i % 200)
                .cpuCoreSeconds(0.001 * i)
                .peakMemoryBytes(1024L * i)
                .egressBytes(i)
                .threadWaitRatio((i % 10) / 10.0)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void concurrentWrites_noExceptionAndSizeWithinBounds() throws InterruptedException {
        MetricsStore store = new MetricsStore(CAPACITY);
        store.startRecording();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREADS);
        AtomicInteger  errors = new AtomicInteger(0);

        for (int t = 0; t < THREADS; t++) {
            final int base = t * WRITES_EACH;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < WRITES_EACH; i++) {
                        store.add(metric(base + i));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Test timed out");
        pool.shutdown();

        assertEquals(0, errors.get(), "No exceptions expected during concurrent writes");
        assertTrue(store.size() <= CAPACITY, "Size must not exceed capacity");
        assertTrue(store.size() > 0, "Some entries must be present");
    }

    @Test
    void concurrentReadWrite_noException() throws InterruptedException {
        MetricsStore store = new MetricsStore(CAPACITY);
        store.startRecording();

        int writers = 8;
        int readers = 8;
        int totalThreads = writers + readers;

        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(totalThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < writers; t++) {
            final int base = t * 200;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) store.add(metric(base + i));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int t = 0; t < readers; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        List<RequestMetrics> all = store.getAll();
                        assertNotNull(all);
                        // Validate snapshot integrity — all entries must be non-null
                        for (RequestMetrics m : all) assertNotNull(m);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Test timed out");
        pool.shutdown();

        assertEquals(0, errors.get(), "No exceptions expected during concurrent read/write");
    }

    @Test
    void concurrentStartStop_noCorruption() throws InterruptedException {
        MetricsStore store = new MetricsStore(CAPACITY);
        store.startRecording();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(4);
        AtomicInteger errors = new AtomicInteger(0);

        // Two threads writing
        for (int t = 0; t < 2; t++) {
            final int base = t * 100;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) store.add(metric(base + i));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        // One thread toggling recording
        pool.submit(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    store.stopRecording();
                    Thread.yield();
                    store.startRecording();
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        // One thread reading
        pool.submit(() -> {
            try {
                for (int i = 0; i < 50; i++) {
                    List<RequestMetrics> snap = store.getAll();
                    assertNotNull(snap);
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(30, TimeUnit.SECONDS), "Test timed out");
        pool.shutdown();
        assertEquals(0, errors.get());
    }

    @Test
    void highThroughput_ringBufferIntegrity() throws InterruptedException {
        // 16 threads × 10 000 writes into a 100-entry store.
        // After completion the store must hold exactly 100 valid entries.
        int cap = 100;
        MetricsStore store = new MetricsStore(cap);
        store.startRecording();

        int threads = 16;
        int writesEach = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int base = t * writesEach;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesEach; i++) store.add(metric(base + i));
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(cap, store.size());
        List<RequestMetrics> all = store.getAll();
        assertEquals(cap, all.size());
        for (RequestMetrics m : all) assertNotNull(m);
    }

    @Test
    void getByRoute_concurrentWrites_noException() throws InterruptedException {
        MetricsStore store = new MetricsStore(CAPACITY);
        store.startRecording();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch done  = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < THREADS / 2; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) store.add(metric(i));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        for (int t = 0; t < THREADS / 2; t++) {
            pool.submit(() -> {
                try {
                    store.getByRoute("GET /route/0"); // concurrent read — verify no exception thrown
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, errors.get());
    }
}
