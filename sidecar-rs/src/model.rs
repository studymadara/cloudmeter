use serde::{Deserialize, Serialize};

#[derive(Debug, Clone)]
pub struct RequestMetrics {
    pub route_template: String,
    pub http_method: String,
    pub http_status: u16,
    pub duration_ms: u64,
    pub cpu_core_seconds: f64,
    pub egress_bytes: u64,
    pub warmup: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct InstanceType {
    pub name: String,
    pub vcpu: f64,
    pub hourly_usd: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct ScalePoint {
    pub users: u32,
    pub monthly_cost_usd: f64,
}

#[derive(Debug, Clone, Serialize)]
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
pub struct WarmupSummary {
    pub has_data: bool,
    pub request_count: usize,
    pub warmup_window_seconds: u32,
    pub total_cpu_core_seconds: f64,
    pub total_egress_bytes: u64,
    pub estimated_cold_start_cost_usd: f64,
}

/// JSON body accepted by POST /api/metrics
#[derive(Debug, Deserialize)]
pub struct IngestPayload {
    pub route: String,
    pub method: String,
    pub status: Option<u16>,
    #[serde(rename = "durationMs")]
    pub duration_ms: u64,
    #[serde(rename = "egressBytes")]
    pub egress_bytes: Option<u64>,
}

impl IngestPayload {
    pub fn into_metrics(self) -> RequestMetrics {
        RequestMetrics {
            route_template: self.route,
            http_method: self.method.to_uppercase(),
            http_status: self.status.unwrap_or(200),
            duration_ms: self.duration_ms,
            cpu_core_seconds: 0.0,
            egress_bytes: self.egress_bytes.unwrap_or(0),
            warmup: false,
        }
    }
}
