/// Configuration for the CloudMeter middleware and dashboard.
#[derive(Debug, Clone)]
pub struct CloudMeterOptions {
    /// Cloud provider: "AWS", "GCP", or "AZURE" (default: "AWS")
    pub provider: String,
    /// Cloud region for display purposes (default: "us-east-1")
    pub region: String,
    /// Concurrent users to project costs at (default: 1000)
    pub target_users: u32,
    /// Requests per user per second used in scaling formula (default: 1.0)
    pub requests_per_user_per_second: f64,
    /// Monthly budget per endpoint in USD; 0 = disabled (default: 0.0)
    pub budget_usd: f64,
    /// Port for the live cost dashboard (default: 7777)
    pub port: u16,
}

impl Default for CloudMeterOptions {
    fn default() -> Self {
        Self {
            provider: "AWS".into(),
            region: "us-east-1".into(),
            target_users: 1000,
            requests_per_user_per_second: 1.0,
            budget_usd: 0.0,
            port: 7777,
        }
    }
}
