use axum::{
    extract::State,
    http::StatusCode,
    response::{Html, IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use serde_json::{json, Value};
use std::sync::Arc;

use crate::config::Config;
use crate::model::IngestPayload;
use crate::pricing::PRICING_DATE;
use crate::projector::{compute_warmup_summary, project};
use crate::store::MetricsStore;

static DASHBOARD_HTML: &str = include_str!("../assets/dashboard.html");

#[derive(Clone)]
pub struct AppState {
    pub store: Arc<MetricsStore>,
    pub config: Arc<Config>,
}

pub fn dashboard_router(state: AppState) -> Router {
    Router::new()
        .route("/", get(serve_dashboard))
        .route("/api/projections", get(get_projections))
        .route("/api/recording/start", post(recording_start))
        .route("/api/recording/stop", post(recording_stop))
        .with_state(state)
}

pub fn ingest_router(state: AppState) -> Router {
    Router::new()
        .route("/api/metrics", post(ingest_metrics))
        .route("/api/status", get(get_status))
        .with_state(state)
}

async fn serve_dashboard() -> Html<&'static str> {
    Html(DASHBOARD_HTML)
}

async fn get_projections(State(state): State<AppState>) -> Response {
    let all = state.store.get_all();
    let projections = project(&all, &state.config);
    let warmup = compute_warmup_summary(&all, &state.config);

    let total: f64 = projections
        .iter()
        .map(|p| p.projected_monthly_cost_usd)
        .sum();
    let any_exceeds = projections.iter().any(|p| p.exceeds_budget);

    let body = json!({
        "meta": {
            "provider": state.config.provider,
            "region": state.config.region,
            "targetUsers": state.config.target_users,
            "requestsPerUserPerSecond": state.config.requests_per_user_per_second,
            "budgetUsd": state.config.budget_usd,
            "pricingDate": PRICING_DATE,
            "pricingSource": "static"
        },
        "projections": projections,
        "warmupSummary": warmup,
        "summary": {
            "totalProjectedMonthlyCostUsd": (total * 100.0).round() / 100.0,
            "anyExceedsBudget": any_exceeds
        }
    });

    let mut response = Json(body).into_response();
    response
        .headers_mut()
        .insert("Access-Control-Allow-Origin", "*".parse().unwrap());
    response
}

async fn ingest_metrics(
    State(state): State<AppState>,
    Json(payload): Json<IngestPayload>,
) -> Response {
    let metrics = payload.into_metrics();
    state.store.add(metrics);
    (StatusCode::ACCEPTED, Json(json!({"accepted": true}))).into_response()
}

async fn get_status(State(state): State<AppState>) -> Json<Value> {
    Json(json!({
        "status": "ok",
        "recording": state.store.is_recording(),
        "totalMetrics": state.store.size()
    }))
}

async fn recording_start(State(state): State<AppState>) -> Json<Value> {
    state.store.start_recording();
    Json(json!({"status": "recording"}))
}

async fn recording_stop(State(state): State<AppState>) -> Json<Value> {
    state.store.stop_recording();
    Json(json!({"status": "stopped"}))
}
