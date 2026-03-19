package io.cloudmeter.agent;

import io.cloudmeter.collector.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ThreadStateCollectorTest {

    private ThreadMXBean  mockThreadBean;
    private MemoryMXBean  mockMemoryBean;
    private MemoryUsage   mockUsage;

    @BeforeEach
    void setUp() {
        mockThreadBean = mock(ThreadMXBean.class);
        mockMemoryBean = mock(MemoryMXBean.class);
        mockUsage      = mock(MemoryUsage.class);

        when(mockMemoryBean.getHeapMemoryUsage()).thenReturn(mockUsage);
        when(mockUsage.getUsed()).thenReturn(1024L);

        CloudMeterRegistry.reset();
    }

    @AfterEach
    void tearDown() {
        CloudMeterRegistry.reset();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Test
    void start_createsAndStartsDaemonThread() throws InterruptedException {
        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        collector.start();
        Thread.sleep(30);
        assertTrue(collector.isRunning());
        collector.stop();
    }

    @Test
    void stop_terminatesThread() throws InterruptedException {
        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 20L);
        collector.start();
        Thread.sleep(30);
        assertTrue(collector.isRunning());

        collector.stop();
        Thread.sleep(100);
        assertFalse(collector.isRunning());
    }

    @Test
    void isRunning_falseBeforeStart() {
        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        assertFalse(collector.isRunning());
    }

    // ── Sampling ───────────────────────────────────────────────────────────────

    @Test
    void sample_noActiveContexts_doesNothing() {
        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        assertDoesNotThrow(collector::sample);
        verify(mockThreadBean, never()).getThreadInfo(anyLong());
    }

    @Test
    void sample_updatesHeapMemoryOnContext() {
        when(mockUsage.getUsed()).thenReturn(2048L);

        RequestContext ctx = activeCtx();
        CloudMeterRegistry.registerContext(ctx);

        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        collector.sample();

        assertEquals(2048L, ctx.getPeakMemoryBytes());
    }

    @Test
    void sample_runnableThread_recordsNotWaiting() {
        RequestContext ctx = ctxWithThread(1L);
        CloudMeterRegistry.registerContext(ctx);

        ThreadInfo info = mock(ThreadInfo.class);
        when(info.getThreadState()).thenReturn(Thread.State.RUNNABLE);
        when(mockThreadBean.getThreadInfo(1L)).thenReturn(info);

        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        collector.sample();

        // RUNNABLE → not waiting → waitRatio stays 0
        assertEquals(0.0, ctx.getThreadWaitRatio(), 0.001);
    }

    @Test
    void sample_waitingThread_recordsWaiting() {
        RequestContext ctx = ctxWithThread(2L);
        CloudMeterRegistry.registerContext(ctx);

        ThreadInfo info = mock(ThreadInfo.class);
        when(info.getThreadState()).thenReturn(Thread.State.WAITING);
        when(mockThreadBean.getThreadInfo(2L)).thenReturn(info);

        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        collector.sample();

        // 1 WAITING out of 1 total → ratio = 1.0
        assertEquals(1.0, ctx.getThreadWaitRatio(), 0.001);
    }

    @Test
    void sample_timedWaitingThread_recordsWaiting() {
        RequestContext ctx = ctxWithThread(3L);
        CloudMeterRegistry.registerContext(ctx);

        ThreadInfo info = mock(ThreadInfo.class);
        when(info.getThreadState()).thenReturn(Thread.State.TIMED_WAITING);
        when(mockThreadBean.getThreadInfo(3L)).thenReturn(info);

        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        collector.sample();

        assertEquals(1.0, ctx.getThreadWaitRatio(), 0.001);
    }

    @Test
    void sample_blockedThread_recordsWaiting() {
        RequestContext ctx = ctxWithThread(4L);
        CloudMeterRegistry.registerContext(ctx);

        ThreadInfo info = mock(ThreadInfo.class);
        when(info.getThreadState()).thenReturn(Thread.State.BLOCKED);
        when(mockThreadBean.getThreadInfo(4L)).thenReturn(info);

        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        collector.sample();

        assertEquals(1.0, ctx.getThreadWaitRatio(), 0.001);
    }

    @Test
    void sample_nullThreadInfo_skipped() {
        RequestContext ctx = ctxWithThread(5L);
        CloudMeterRegistry.registerContext(ctx);

        when(mockThreadBean.getThreadInfo(5L)).thenReturn(null);

        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        assertDoesNotThrow(collector::sample);
        // No sample recorded — totalSamples = 0 → ratio = 0
        assertEquals(0.0, ctx.getThreadWaitRatio(), 0.001);
    }

    @Test
    void sample_memoryBeanThrows_doesNotCrash() {
        when(mockMemoryBean.getHeapMemoryUsage()).thenThrow(new RuntimeException("MXBean failure"));
        RequestContext ctx = activeCtx();
        CloudMeterRegistry.registerContext(ctx);

        ThreadStateCollector collector = new ThreadStateCollector(mockThreadBean, mockMemoryBean, 50L);
        assertDoesNotThrow(collector::sample);
    }

    @Test
    void productionConstructor_doesNotThrow() {
        assertDoesNotThrow(() -> new ThreadStateCollector(50L));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates an active RequestContext whose origin thread is the current thread. */
    private static RequestContext activeCtx() {
        long tid = Thread.currentThread().getId();
        RequestContext ctx = new RequestContext("/test", "/test", System.nanoTime(), tid);
        ctx.recordThreadCpuStart(tid, 0L);
        return ctx;
    }

    /** Creates a RequestContext with a specific fake thread ID registered. */
    private static RequestContext ctxWithThread(long threadId) {
        RequestContext ctx = new RequestContext("/test", "/test", System.nanoTime(), threadId);
        ctx.recordThreadCpuStart(threadId, 0L);
        return ctx;
    }
}
