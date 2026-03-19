package io.cloudmeter.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricsStoreTest {

    private MetricsStore store;

    @BeforeEach
    void setUp() {
        store = new MetricsStore(5);
        store.startRecording();
    }

    private static RequestMetrics metric(String route) {
        return RequestMetrics.builder()
                .routeTemplate(route)
                .actualPath(route)
                .httpStatusCode(200)
                .httpMethod("GET")
                .durationMs(100)
                .cpuCoreSeconds(0.01)
                .peakMemoryBytes(1024)
                .egressBytes(512)
                .threadWaitRatio(0.1)
                .timestamp(Instant.now())
                .build();
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void defaultCapacity_is10000() {
        assertEquals(10_000, new MetricsStore().capacity());
    }

    @Test
    void invalidCapacity_throws() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsStore(0));
        assertThrows(IllegalArgumentException.class, () -> new MetricsStore(-1));
    }

    // ── Recording control ─────────────────────────────────────────────────────

    @Test
    void initiallyNotRecording() {
        assertFalse(new MetricsStore().isRecording());
    }

    @Test
    void startRecording_setsRecordingTrue() {
        assertTrue(store.isRecording());
    }

    @Test
    void stopRecording_setsRecordingFalse() {
        store.stopRecording();
        assertFalse(store.isRecording());
    }

    @Test
    void startRecording_clearsExistingData() {
        store.add(metric("GET /a"));
        assertEquals(1, store.size());
        store.startRecording();
        assertEquals(0, store.size());
    }

    // ── Add ───────────────────────────────────────────────────────────────────

    @Test
    void add_whenNotRecording_isDropped() {
        store.stopRecording();
        store.add(metric("GET /a"));
        assertEquals(0, store.size());
    }

    @Test
    void add_null_isIgnored() {
        store.add(null);
        assertEquals(0, store.size());
    }

    @Test
    void add_singleEntry_storedCorrectly() {
        RequestMetrics m = metric("GET /api/users/{id}");
        store.add(m);
        List<RequestMetrics> all = store.getAll();
        assertEquals(1, all.size());
        assertEquals(m, all.get(0));
    }

    @Test
    void add_upToCapacity_allStored() {
        for (int i = 0; i < 5; i++) store.add(metric("GET /r" + i));
        assertEquals(5, store.size());
    }

    @Test
    void add_beyondCapacity_oldestEvicted() {
        for (int i = 0; i < 5; i++) store.add(metric("GET /r" + i));
        store.add(metric("GET /r5")); // overwrites r0
        List<RequestMetrics> all = store.getAll();
        assertEquals(5, all.size());
        assertEquals("GET /r1", all.get(0).getRouteTemplate());
        assertEquals("GET /r5", all.get(4).getRouteTemplate());
    }

    @Test
    void add_multipleWraparounds_ringBufferCorrect() {
        // Write 12 entries into capacity-5 store — should keep last 5
        for (int i = 0; i < 12; i++) store.add(metric("GET /r" + i));
        List<RequestMetrics> all = store.getAll();
        assertEquals(5, all.size());
        assertEquals("GET /r7",  all.get(0).getRouteTemplate());
        assertEquals("GET /r11", all.get(4).getRouteTemplate());
    }

    // ── getAll ────────────────────────────────────────────────────────────────

    @Test
    void getAll_empty_returnsEmptyList() {
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void getAll_preservesInsertionOrder() {
        store.add(metric("GET /a"));
        store.add(metric("GET /b"));
        store.add(metric("GET /c"));
        List<RequestMetrics> all = store.getAll();
        assertEquals("GET /a", all.get(0).getRouteTemplate());
        assertEquals("GET /b", all.get(1).getRouteTemplate());
        assertEquals("GET /c", all.get(2).getRouteTemplate());
    }

    // ── getByRoute ────────────────────────────────────────────────────────────

    @Test
    void getByRoute_filtersCorrectly() {
        store.add(metric("GET /users"));
        store.add(metric("POST /orders"));
        store.add(metric("GET /users"));
        List<RequestMetrics> users = store.getByRoute("GET /users");
        assertEquals(2, users.size());
        users.forEach(m -> assertEquals("GET /users", m.getRouteTemplate()));
    }

    @Test
    void getByRoute_noMatches_returnsEmpty() {
        store.add(metric("GET /users"));
        assertTrue(store.getByRoute("GET /orders").isEmpty());
    }

    @Test
    void getByRoute_null_returnsEmpty() {
        store.add(metric("GET /users"));
        assertTrue(store.getByRoute(null).isEmpty());
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    @Test
    void clear_removesAllEntries() {
        store.add(metric("GET /a"));
        store.add(metric("GET /b"));
        store.clear();
        assertEquals(0, store.size());
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void clear_doesNotChangeRecordingState() {
        store.clear();
        assertTrue(store.isRecording());
        store.stopRecording();
        store.clear();
        assertFalse(store.isRecording());
    }

    @Test
    void afterClear_canStillAddEntries() {
        store.add(metric("GET /a"));
        store.clear();
        store.add(metric("GET /b"));
        assertEquals(1, store.size());
        assertEquals("GET /b", store.getAll().get(0).getRouteTemplate());
    }

    // ── size / capacity ───────────────────────────────────────────────────────

    @Test
    void size_reflectsEntryCount() {
        assertEquals(0, store.size());
        store.add(metric("GET /a"));
        assertEquals(1, store.size());
    }

    @Test
    void size_capsAtCapacity() {
        for (int i = 0; i < 20; i++) store.add(metric("GET /r" + i));
        assertEquals(5, store.size());
    }

    @Test
    void capacity_matchesConstructorArg() {
        assertEquals(5, store.capacity());
    }
}
