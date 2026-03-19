package io.cloudmeter.collector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe fixed-capacity ring buffer for {@link RequestMetrics}.
 *
 * When capacity is reached the oldest entry is silently overwritten.
 * All writes are ignored when recording is inactive.
 */
public final class MetricsStore {

    public static final int DEFAULT_CAPACITY = 10_000;

    private final RequestMetrics[] buffer;
    private final int              capacity;
    private final ReadWriteLock    lock     = new ReentrantReadWriteLock();
    private final AtomicBoolean    recording = new AtomicBoolean(false);

    private int  head = 0;   // next write position
    private int  size = 0;   // current number of entries

    public MetricsStore() {
        this(DEFAULT_CAPACITY);
    }

    public MetricsStore(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.buffer   = new RequestMetrics[capacity];
    }

    // ── Recording control ─────────────────────────────────────────────────────

    /**
     * Start accumulating metrics. Clears any previously buffered data.
     */
    public void startRecording() {
        lock.writeLock().lock();
        try {
            clear0();
            recording.set(true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Stop accumulating metrics. Existing buffered data is retained for reporting.
     */
    public void stopRecording() {
        recording.set(false);
    }

    public boolean isRecording() {
        return recording.get();
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Add a metric entry. Silently dropped if recording is not active.
     */
    public void add(RequestMetrics metrics) {
        if (metrics == null || !recording.get()) return;
        lock.writeLock().lock();
        try {
            buffer[head] = metrics;
            head = (head + 1) % capacity;
            if (size < capacity) size++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns a snapshot of all buffered entries in insertion order.
     */
    public List<RequestMetrics> getAll() {
        lock.readLock().lock();
        try {
            return snapshot();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a snapshot filtered to a specific route template.
     */
    public List<RequestMetrics> getByRoute(String routeTemplate) {
        if (routeTemplate == null) return Collections.emptyList();
        lock.readLock().lock();
        try {
            List<RequestMetrics> result = new ArrayList<>();
            for (RequestMetrics m : snapshot()) {
                if (routeTemplate.equals(m.getRouteTemplate())) {
                    result.add(m);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    /**
     * Clears all buffered metrics. Does not change recording state.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            clear0();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Must be called with write lock held. */
    private void clear0() {
        for (int i = 0; i < capacity; i++) buffer[i] = null;
        head = 0;
        size = 0;
    }

    /**
     * Returns entries in insertion order (oldest first).
     * Must be called with at least a read lock held.
     */
    private List<RequestMetrics> snapshot() {
        List<RequestMetrics> result = new ArrayList<>(size);
        if (size == 0) return result;
        if (size < capacity) {
            // Buffer not yet wrapped — entries live at [0..size)
            for (int i = 0; i < size; i++) {
                result.add(buffer[i]);
            }
        } else {
            // Buffer full and wrapped — oldest entry is at head
            for (int i = 0; i < capacity; i++) {
                result.add(buffer[(head + i) % capacity]);
            }
        }
        return result;
    }
}
