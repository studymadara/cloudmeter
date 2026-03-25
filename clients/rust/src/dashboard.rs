/// In-process dashboard server.
///
/// Runs in a background thread with its own Tokio runtime on 127.0.0.1:<port>.
/// Silently no-ops if the port is already in use.
use axum::{
    extract::State,
    response::{Html, IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use serde_json::{json, Value};
use std::net::SocketAddr;
use std::sync::Arc;

use crate::config::CloudMeterOptions;
use crate::pricing::PRICING_DATE;
use crate::projector::{compute_stats, project};
use crate::store::MetricsStore;

static DASHBOARD_HTML: &str = include_str!("../assets/dashboard.html");

#[derive(Clone)]
struct DashboardState {
    store: Arc<MetricsStore>,
    opts: Arc<CloudMeterOptions>,
}

/// Start the dashboard in a background thread. Returns immediately.
/// If the port is already bound the function silently returns.
pub fn start(store: Arc<MetricsStore>, opts: &CloudMeterOptions) {
    let state = DashboardState {
        store,
        opts: Arc::new(opts.clone()),
    };
    let port = opts.port;

    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("cloudmeter: failed to build dashboard runtime");

        rt.block_on(async move {
            let addr: SocketAddr = format!("127.0.0.1:{port}").parse().unwrap();
            let listener = match tokio::net::TcpListener::bind(addr).await {
                Ok(l) => l,
                Err(_) => return, // EADDRINUSE — silently no-op
            };

            let app = build_router(state);
            println!("[cloudmeter] dashboard → http://127.0.0.1:{port}");
            let _ = axum::serve(listener, app).await;
        });
    });
}

fn build_router(state: DashboardState) -> Router {
    Router::new()
        .route("/", get(serve_dashboard))
        .route("/api/projections", get(get_projections))
        .route("/api/stats", get(get_stats))
        .route("/api/recording/start", post(recording_start))
        .route("/api/recording/stop", post(recording_stop))
        .with_state(state)
}

async fn serve_dashboard() -> Html<&'static str> {
    Html(DASHBOARD_HTML)
}

async fn get_projections(State(s): State<DashboardState>) -> Response {
    let metrics = s.store.get_all();
    let projs = project(&metrics, &s.opts);
    let total: f64 = projs.iter().map(|p| p.projected_monthly_cost_usd).sum();
    let any_exceeds = projs.iter().any(|p| p.exceeds_budget);
    let warmup_count = metrics.iter().filter(|m| m.warmup).count();

    let body = json!({
        "meta": {
            "provider": s.opts.provider,
            "region": s.opts.region,
            "targetUsers": s.opts.target_users,
            "requestsPerUserPerSecond": s.opts.requests_per_user_per_second,
            "budgetUsd": s.opts.budget_usd,
            "pricingDate": PRICING_DATE,
            "pricingSource": "static"
        },
        "projections": projs,
        "summary": {
            "totalProjectedMonthlyCostUsd": (total * 100.0).round() / 100.0,
            "anyExceedsBudget": any_exceeds,
            "warmupMetricsExcluded": warmup_count
        }
    });

    let mut resp = Json(body).into_response();
    resp.headers_mut()
        .insert("Access-Control-Allow-Origin", "*".parse().unwrap());
    resp
}

async fn get_stats(State(s): State<DashboardState>) -> Response {
    let metrics = s.store.get_all();
    let projs = project(&metrics, &s.opts);
    let stats = compute_stats(&metrics, &projs, &s.opts);

    let mut resp = Json(json!({ "stats": stats })).into_response();
    resp.headers_mut()
        .insert("Access-Control-Allow-Origin", "*".parse().unwrap());
    resp
}

async fn recording_start(State(s): State<DashboardState>) -> Json<Value> {
    s.store.start_recording();
    Json(json!({"status": "recording"}))
}

async fn recording_stop(State(s): State<DashboardState>) -> Json<Value> {
    s.store.stop_recording();
    Json(json!({"status": "stopped"}))
}
