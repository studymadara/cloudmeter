use clap::Parser;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::TcpListener;

use cloudmeter_sidecar::config::Config;
use cloudmeter_sidecar::server::{self, AppState};
use cloudmeter_sidecar::store::{self, MetricsStore};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let config = Arc::new(Config::parse());
    let store = MetricsStore::new(store::DEFAULT_CAPACITY);
    store.start_recording(); // auto-start — sidecar always records

    let state = AppState {
        store: Arc::clone(&store),
        config: Arc::clone(&config),
    };

    // Ingest server (7778)
    let ingest_addr: SocketAddr = format!("127.0.0.1:{}", config.ingest_port).parse()?;
    let ingest_listener = TcpListener::bind(ingest_addr).await?;
    let ingest_app = server::ingest_router(state.clone());

    // Dashboard server (7777)
    let dashboard_addr: SocketAddr = format!("127.0.0.1:{}", config.dashboard_port).parse()?;
    let dashboard_listener = TcpListener::bind(dashboard_addr).await?;
    let dashboard_app = server::dashboard_router(state.clone());

    println!(
        "[CloudMeter] Ingest   → http://127.0.0.1:{}/api/metrics",
        config.ingest_port
    );
    println!(
        "[CloudMeter] Dashboard → http://localhost:{}",
        config.dashboard_port
    );
    println!(
        "[CloudMeter] Provider : {} / {}",
        config.provider, config.region
    );
    println!("[CloudMeter] Target   : {} users", config.target_users);

    tokio::select! {
        r = axum::serve(ingest_listener, ingest_app) => r?,
        r = axum::serve(dashboard_listener, dashboard_app) => r?,
    }

    Ok(())
}
