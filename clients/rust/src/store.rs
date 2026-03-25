use crate::model::RequestMetrics;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

pub const DEFAULT_CAPACITY: usize = 10_000;
const WARMUP_DURATION: Duration = Duration::from_secs(30);

pub struct MetricsStore {
    buffer: RwLock<VecDeque<RequestMetrics>>,
    capacity: usize,
    recording: AtomicBool,
    recording_start: Mutex<Option<Instant>>,
}

impl MetricsStore {
    pub fn new(capacity: usize) -> Arc<Self> {
        Arc::new(Self {
            buffer: RwLock::new(VecDeque::with_capacity(capacity)),
            capacity,
            recording: AtomicBool::new(false),
            recording_start: Mutex::new(None),
        })
    }

    /// Start recording. Clears buffered data and resets the warmup clock.
    pub fn start_recording(&self) {
        let mut buf = self.buffer.write().unwrap();
        buf.clear();
        *self.recording_start.lock().unwrap() = Some(Instant::now());
        self.recording.store(true, Ordering::SeqCst);
    }

    pub fn stop_recording(&self) {
        self.recording.store(false, Ordering::SeqCst);
    }

    pub fn is_recording(&self) -> bool {
        self.recording.load(Ordering::Relaxed)
    }

    /// Add a metric. Silently dropped when not recording.
    /// The warmup flag is set automatically based on recording_start.
    pub fn add(&self, mut m: RequestMetrics) {
        if !self.recording.load(Ordering::Relaxed) {
            return;
        }
        m.warmup = self
            .recording_start
            .lock()
            .unwrap()
            .map(|start| start.elapsed() < WARMUP_DURATION)
            .unwrap_or(false);

        let mut buf = self.buffer.write().unwrap();
        if buf.len() >= self.capacity {
            buf.pop_front(); // overwrite oldest
        }
        buf.push_back(m);
    }

    /// Snapshot of all buffered metrics in insertion order.
    pub fn get_all(&self) -> Vec<RequestMetrics> {
        self.buffer.read().unwrap().iter().cloned().collect()
    }

    pub fn size(&self) -> usize {
        self.buffer.read().unwrap().len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_metric(route: &str) -> RequestMetrics {
        RequestMetrics {
            route_template: route.to_string(),
            http_method: "GET".to_string(),
            http_status: 200,
            duration_ms: 10,
            cpu_core_seconds: 0.01,
            egress_bytes: 100,
            warmup: false,
        }
    }

    #[test]
    fn drops_metrics_when_not_recording() {
        let store = MetricsStore::new(100);
        store.add(make_metric("/test"));
        assert_eq!(0, store.size());
    }

    #[test]
    fn accepts_metrics_when_recording() {
        let store = MetricsStore::new(100);
        store.start_recording();
        store.add(make_metric("/test"));
        assert_eq!(1, store.size());
    }

    #[test]
    fn clears_on_start_recording() {
        let store = MetricsStore::new(100);
        store.start_recording();
        store.add(make_metric("/a"));
        store.add(make_metric("/b"));
        store.start_recording(); // second call should clear
        assert_eq!(0, store.size());
    }

    #[test]
    fn ring_buffer_evicts_oldest() {
        let store = MetricsStore::new(3);
        store.start_recording();
        store.add(make_metric("/a"));
        store.add(make_metric("/b"));
        store.add(make_metric("/c"));
        store.add(make_metric("/d")); // evicts /a
        let all = store.get_all();
        assert_eq!(3, all.len());
        assert_eq!("/b", all[0].route_template);
        assert_eq!("/d", all[2].route_template);
    }

    #[test]
    fn stop_recording_drops_new_metrics() {
        let store = MetricsStore::new(100);
        store.start_recording();
        store.stop_recording();
        store.add(make_metric("/test"));
        assert_eq!(0, store.size());
    }

    #[test]
    fn warmup_flag_set_immediately_after_start() {
        let store = MetricsStore::new(100);
        store.start_recording();
        store.add(make_metric("/test"));
        assert!(store.get_all()[0].warmup);
    }
}
