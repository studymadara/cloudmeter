use serde::Serialize;

/// Raw per-request metric captured by the middleware.
#[derive(Debug, Clone)]
pub struct RequestMetrics {
    pub route_template: String,
    pub http_method: String,
    pub http_status: u16,
    pub duration_ms: u64,
    /// CPU proxy: duration_ms / 1000 (wall-clock, no per-thread CPU API in Rust middleware)
    pub cpu_core_seconds: f64,
    pub egress_bytes: u64,
    /// True during the first 30 s after start_recording() — excluded from projections.
    pub warmup: bool,
}

/// API response types — camelCase to match the shared dashboard.html

#[derive(Debug, Clone, Serialize)]
pub struct InstanceType {
    pub name: String,
    pub vcpu: f64,
    #[serde(rename = "hourlyUsd")]
    pub hourly_usd: f64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScalePoint {
    pub users: u32,
    pub monthly_cost_usd: f64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EndpointCostProjection {
    pub route: String,
    pub observed_rps: f64,
    pub projected_rps: f64,
    pub projected_monthly_cost_usd: f64,
    pub projected_cost_per_user_usd: f64,
    pub recommended_instance: String,
    pub median_duration_ms: f64,
    pub median_cpu_ms: f64,
    pub exceeds_budget: bool,
    pub cost_curve: Vec<ScalePoint>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RouteStats {
    pub route: String,
    pub requests: usize,
    pub p50_cost_usd: f64,
    pub p95_cost_usd: f64,
    pub p99_cost_usd: f64,
    pub variance_ratio: f64,
    pub variance_warning: bool,
}
