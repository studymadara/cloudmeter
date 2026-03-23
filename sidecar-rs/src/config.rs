use clap::Parser;

/// CloudMeter sidecar — native cost monitoring agent for Python/Node.js apps.
///
/// Accepts metric reports via HTTP on the ingest port, projects cloud costs,
/// and serves a live dashboard on the dashboard port.
#[derive(Parser, Debug, Clone)]
#[command(name = "cloudmeter-sidecar", version = "0.1.0")]
pub struct Config {
    /// Cloud provider: AWS, GCP, or AZURE
    #[arg(long, default_value = "AWS")]
    pub provider: String,

    /// Cloud region (e.g. us-east-1, us-central1, eastus)
    #[arg(long, default_value = "us-east-1")]
    pub region: String,

    /// Concurrent users to project costs at
    #[arg(long, default_value_t = 1000)]
    pub target_users: u32,

    /// Requests per user per second (used in scaling formula)
    #[arg(long, default_value_t = 1.0)]
    pub requests_per_user_per_second: f64,

    /// Monthly budget per endpoint in USD (0 = disabled)
    #[arg(long, default_value_t = 0.0)]
    pub budget_usd: f64,

    /// Port for the cost dashboard
    #[arg(long, default_value_t = 7777)]
    pub dashboard_port: u16,

    /// Port for metric ingest (POST /api/metrics)
    #[arg(long, default_value_t = 7778)]
    pub ingest_port: u16,
}
