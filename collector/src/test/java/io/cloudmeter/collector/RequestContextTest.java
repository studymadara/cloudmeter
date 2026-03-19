package io.cloudmeter.collector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextTest {

    private RequestContext ctx() {
        return new RequestContext("GET /api/users/{id}", "/api/users/5", 1000L, 42L);
    }

    @Test
    void getters_returnConstructorValues() {
        RequestContext c = ctx();
        assertEquals("GET /api/users/{id}", c.getRouteTemplate());
        assertEquals("/api/users/5",         c.getActualPath());
        assertEquals(1000L,                  c.getStartNanos());
        assertEquals(42L,                    c.getOriginThreadId());
    }

    @Test
    void constructor_requiresNonNullRoute() {
        assertThrows(NullPointerException.class,
                () -> new RequestContext(null, "/x", 0L, 1L));
    }

    @Test
    void constructor_requiresNonNullPath() {
        assertThrows(NullPointerException.class,
                () -> new RequestContext("GET /x", null, 0L, 1L));
    }

    @Test
    void cpuAccumulation_singleThread() {
        RequestContext c = ctx();
        c.recordThreadCpuStart(10L, 1_000_000L);
        c.finalizeThreadCpu(10L,    3_000_000L);
        assertEquals(2_000_000L, c.getCpuNanosAccumulated());
    }

    @Test
    void cpuAccumulation_multipleThreads() {
        RequestContext c = ctx();
        c.recordThreadCpuStart(10L, 0L);
        c.recordThreadCpuStart(11L, 0L);
        c.finalizeThreadCpu(10L, 1_000_000L);
        c.finalizeThreadCpu(11L, 500_000L);
        assertEquals(1_500_000L, c.getCpuNanosAccumulated());
    }

    @Test
    void cpuAccumulation_negativeOrZeroDelta_ignored() {
        RequestContext c = ctx();
        c.recordThreadCpuStart(10L, 5_000_000L);
        c.finalizeThreadCpu(10L,    5_000_000L); // zero delta
        assertEquals(0L, c.getCpuNanosAccumulated());

        c.recordThreadCpuStart(10L, 5_000_000L);
        c.finalizeThreadCpu(10L,    4_000_000L); // negative delta (clock wrap)
        assertEquals(0L, c.getCpuNanosAccumulated());
    }

    @Test
    void finalizeThreadCpu_withoutStart_isIgnored() {
        RequestContext c = ctx();
        c.finalizeThreadCpu(99L, 9_999_999L); // no prior start recorded
        assertEquals(0L, c.getCpuNanosAccumulated());
    }

    @Test
    void activeThreadIds_addedOnStart_removedOnFinalize() {
        RequestContext c = ctx();
        c.recordThreadCpuStart(10L, 0L);
        assertTrue(c.getActiveThreadIds().contains(10L));
        c.finalizeThreadCpu(10L, 100L);
        assertFalse(c.getActiveThreadIds().contains(10L));
    }

    @Test
    void activeThreadIds_unmodifiable() {
        RequestContext c = ctx();
        c.recordThreadCpuStart(10L, 0L);
        assertThrows(UnsupportedOperationException.class,
                () -> c.getActiveThreadIds().add(99L));
    }

    @Test
    void threadWaitRatio_noSamples_returnsZero() {
        assertEquals(0.0, ctx().getThreadWaitRatio(), 1e-9);
    }

    @Test
    void threadWaitRatio_allWaiting_returnsOne() {
        RequestContext c = ctx();
        c.recordSample(true);
        c.recordSample(true);
        assertEquals(1.0, c.getThreadWaitRatio(), 1e-9);
    }

    @Test
    void threadWaitRatio_mixed() {
        RequestContext c = ctx();
        c.recordSample(true);   // 1 waiting
        c.recordSample(false);  // 1 running
        c.recordSample(false);  // 1 running
        c.recordSample(true);   // 1 waiting
        assertEquals(0.5, c.getThreadWaitRatio(), 1e-9);
    }

    @Test
    void peakMemory_updatesWhenHigher() {
        RequestContext c = ctx();
        c.updatePeakMemory(1_000L);
        c.updatePeakMemory(5_000L);
        c.updatePeakMemory(2_000L);
        assertEquals(5_000L, c.getPeakMemoryBytes());
    }

    @Test
    void peakMemory_doesNotDecrease() {
        RequestContext c = ctx();
        c.updatePeakMemory(9_000L);
        c.updatePeakMemory(1_000L);
        assertEquals(9_000L, c.getPeakMemoryBytes());
    }

    @Test
    void peakMemory_initiallyZero() {
        assertEquals(0L, ctx().getPeakMemoryBytes());
    }
}
