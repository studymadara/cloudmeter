/// CloudMeter Axum demo / smoke test app.
///
/// Endpoints:
///   GET  /api/users/:user_id      — user lookup
///   GET  /api/products            — product list
///   GET  /api/reports/pdf         — expensive PDF export
///   GET  /__cloudmeter/metrics    — raw metric buffer (for smoke test assertions)
///
/// Dashboard: http://localhost:7777
use axum::{extract::Path, routing::get, Json, Router};
use cloudmeter::{CloudMeter, CloudMeterOptions};
use serde_json::{json, Value};

#[tokio::main]
async fn main() {
    let port: u16 = std::env::var("APP_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(18080);

    let dashboard_port: u16 = std::env::var("DASHBOARD_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(17777);

    let provider = std::env::var("PROVIDER").unwrap_or_else(|_| "AWS".into());
    let target_users: u32 = std::env::var("TARGET_USERS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(500);

    let cm = CloudMeter::new(CloudMeterOptions {
        provider,
        target_users,
        port: dashboard_port,
        ..Default::default()
    });

    let store = cm.store();

    let app = Router::new()
        .route("/api/users/:user_id", get(get_user))
        .route("/api/products", get(list_products))
        .route("/api/reports/pdf", get(get_pdf_report))
        // Diagnostic endpoint for smoke test assertions — not gated by CloudMeter
        .route(
            "/__cloudmeter/metrics",
            get({
                let store = store.clone();
                move || {
                    let store = store.clone();
                    async move { Json(store.get_all_json()) }
                }
            }),
        )
        .route_layer(cm.layer());

    let addr = format!("127.0.0.1:{port}");
    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    println!("[cloudmeter-demo] listening on http://{addr}");
    axum::serve(listener, app).await.unwrap();
}

async fn get_user(Path(user_id): Path<u32>) -> Json<Value> {
    Json(json!({ "id": user_id, "name": "Alice" }))
}

async fn list_products() -> Json<Value> {
    Json(json!([{ "id": 1 }, { "id": 2 }, { "id": 3 }]))
}

async fn get_pdf_report() -> Json<Value> {
    // Simulate a heavier endpoint
    tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    Json(json!({ "url": "report.pdf" }))
}

/// Extension trait to serialize RequestMetrics to JSON without exposing the type.
trait StoreExt {
    fn get_all_json(&self) -> Vec<serde_json::Value>;
}

impl StoreExt for cloudmeter::store::MetricsStore {
    fn get_all_json(&self) -> Vec<serde_json::Value> {
        self.get_all()
            .into_iter()
            .map(|m| {
                json!({
                    "route": m.route_template,
                    "method": m.http_method,
                    "status": m.http_status,
                    "durationMs": m.duration_ms,
                    "egressBytes": m.egress_bytes,
                    "warmup": m.warmup,
                })
            })
            .collect()
    }
}
