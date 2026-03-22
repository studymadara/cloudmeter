/// Integration tests — spin up real axum servers on ephemeral ports.
///
/// Each test calls `start_ingest_server()` or `start_dashboard_server()`,
/// gets the bound address, fires HTTP requests via reqwest, and asserts on
/// the response.  Servers are aborted after each test via `JoinHandle::abort()`.
use cloudmeter_sidecar::config::Config;
use cloudmeter_sidecar::server::{dashboard_router, ingest_router, AppState};
use cloudmeter_sidecar::store::MetricsStore;
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;

// ── helpers ─────────────────────────────────────────────────────────────────

fn test_config() -> Arc<Config> {
    Arc::new(Config {
        provider: "AWS".into(),
        region: "us-east-1".into(),
        target_users: 100,
        requests_per_user_per_second: 1.0,
        budget_usd: 0.0,
        dashboard_port: 0, // unused in tests
        ingest_port: 0,    // unused in tests
    })
}

async fn start_ingest_server() -> (String, Arc<MetricsStore>, JoinHandle<()>) {
    let config = test_config();
    let store = MetricsStore::new(1000);
    store.start_recording();
    let state = AppState {
        store: Arc::clone(&store),
        config,
    };
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = ingest_router(state);
    let handle = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    (format!("http://{}", addr), store, handle)
}

async fn start_dashboard_server() -> (String, Arc<MetricsStore>, JoinHandle<()>) {
    let config = test_config();
    let store = MetricsStore::new(1000);
    store.start_recording();
    let state = AppState {
        store: Arc::clone(&store),
        config,
    };
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = dashboard_router(state);
    let handle = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    (format!("http://{}", addr), store, handle)
}

// ── ingest server tests ──────────────────────────────────────────────────────

#[tokio::test]
async fn ingest_status_returns_ok() {
    let (base, _store, handle) = start_ingest_server().await;
    let resp = reqwest::get(format!("{}/api/status", base)).await.unwrap();
    assert_eq!(200, resp.status());
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!("ok", body["status"]);
    assert_eq!(true, body["recording"]);
    assert_eq!(0, body["totalMetrics"]);
    handle.abort();
}

#[tokio::test]
async fn ingest_metrics_returns_202() {
    let (base, _store, handle) = start_ingest_server().await;
    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/api/metrics", base))
        .json(&serde_json::json!({
            "route": "GET /api/users",
            "method": "GET",
            "status": 200,
            "durationMs": 42
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(202, resp.status());
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(true, body["accepted"]);
    handle.abort();
}

#[tokio::test]
async fn ingest_metrics_updates_store_count() {
    let (base, _store, handle) = start_ingest_server().await;
    let client = reqwest::Client::new();

    for i in 0..5 {
        client
            .post(format!("{}/api/metrics", base))
            .json(&serde_json::json!({
                "route": format!("GET /api/item/{}", i),
                "method": "GET",
                "status": 200,
                "durationMs": 10
            }))
            .send()
            .await
            .unwrap();
    }

    let resp = reqwest::get(format!("{}/api/status", base)).await.unwrap();
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(5, body["totalMetrics"]);
    handle.abort();
}

#[tokio::test]
async fn ingest_metrics_with_egress_bytes() {
    let (base, _store, handle) = start_ingest_server().await;
    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/api/metrics", base))
        .json(&serde_json::json!({
            "route": "GET /api/export",
            "method": "GET",
            "status": 200,
            "durationMs": 150,
            "egressBytes": 102400
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(202, resp.status());

    let status = reqwest::get(format!("{}/api/status", base)).await.unwrap();
    let body: serde_json::Value = status.json().await.unwrap();
    assert_eq!(1, body["totalMetrics"]);
    handle.abort();
}

#[tokio::test]
async fn ingest_bad_json_returns_422() {
    let (base, _store, handle) = start_ingest_server().await;
    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/api/metrics", base))
        .header("Content-Type", "application/json")
        .body("{not valid json}")
        .send()
        .await
        .unwrap();
    // axum returns 400 for syntactically invalid JSON
    assert_eq!(400, resp.status());
    handle.abort();
}

#[tokio::test]
async fn ingest_missing_required_field_returns_422() {
    let (base, _store, handle) = start_ingest_server().await;
    let client = reqwest::Client::new();
    // "durationMs" is required
    let resp = client
        .post(format!("{}/api/metrics", base))
        .json(&serde_json::json!({
            "route": "GET /api/users",
            "method": "GET"
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(422, resp.status());
    handle.abort();
}

// ── dashboard server tests ───────────────────────────────────────────────────

#[tokio::test]
async fn dashboard_root_returns_html() {
    let (base, _store, handle) = start_dashboard_server().await;
    let resp = reqwest::get(format!("{}/", base)).await.unwrap();
    assert_eq!(200, resp.status());
    let content_type = resp
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    assert!(
        content_type.contains("text/html"),
        "expected text/html, got: {}",
        content_type
    );
    let body = resp.text().await.unwrap();
    assert!(
        body.contains("<!DOCTYPE html") || body.contains("<html"),
        "response is not HTML"
    );
    handle.abort();
}

#[tokio::test]
async fn dashboard_projections_empty_store() {
    let (base, _store, handle) = start_dashboard_server().await;
    let resp = reqwest::get(format!("{}/api/projections", base))
        .await
        .unwrap();
    assert_eq!(200, resp.status());

    let body: serde_json::Value = resp.json().await.unwrap();
    assert!(body["projections"].is_array());
    assert_eq!(0, body["projections"].as_array().unwrap().len());
    assert!(body["meta"]["provider"].as_str().unwrap() == "AWS");
    handle.abort();
}

#[tokio::test]
async fn dashboard_projections_has_cors_header() {
    let (base, _store, handle) = start_dashboard_server().await;
    let resp = reqwest::get(format!("{}/api/projections", base))
        .await
        .unwrap();
    let cors = resp
        .headers()
        .get("access-control-allow-origin")
        .and_then(|v| v.to_str().ok());
    assert_eq!(Some("*"), cors);
    handle.abort();
}

#[tokio::test]
async fn dashboard_recording_start_clears_and_responds() {
    let (base, store, handle) = start_dashboard_server().await;
    // Manually add a metric so we can verify start_recording clears it
    store.start_recording();
    store.add(cloudmeter_sidecar::model::RequestMetrics {
        route_template: "GET /test".into(),
        http_method: "GET".into(),
        http_status: 200,
        duration_ms: 10,
        cpu_core_seconds: 0.01,
        egress_bytes: 100,
        warmup: false,
    });
    assert_eq!(1, store.size());

    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/api/recording/start", base))
        .send()
        .await
        .unwrap();
    assert_eq!(200, resp.status());
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!("recording", body["status"]);
    // Buffer cleared by start_recording
    assert_eq!(0, store.size());
    handle.abort();
}

#[tokio::test]
async fn dashboard_recording_stop_responds() {
    let (base, _store, handle) = start_dashboard_server().await;
    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/api/recording/stop", base))
        .send()
        .await
        .unwrap();
    assert_eq!(200, resp.status());
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!("stopped", body["status"]);
    handle.abort();
}

// ── end-to-end flow: ingest → projections ───────────────────────────────────

#[tokio::test]
async fn end_to_end_metrics_appear_in_projections() {
    // Share a single store between ingest and dashboard
    let config = test_config();
    let store = MetricsStore::new(1000);
    store.start_recording();

    let ingest_state = AppState {
        store: Arc::clone(&store),
        config: Arc::clone(&config),
    };
    let dashboard_state = AppState {
        store: Arc::clone(&store),
        config: Arc::clone(&config),
    };

    let ingest_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let ingest_addr = ingest_listener.local_addr().unwrap();
    let dashboard_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let dashboard_addr = dashboard_listener.local_addr().unwrap();

    let h1 = tokio::spawn(async move {
        axum::serve(ingest_listener, ingest_router(ingest_state))
            .await
            .unwrap();
    });
    let h2 = tokio::spawn(async move {
        axum::serve(dashboard_listener, dashboard_router(dashboard_state))
            .await
            .unwrap();
    });

    let client = reqwest::Client::new();
    let ingest_base = format!("http://{}", ingest_addr);
    let dashboard_base = format!("http://{}", dashboard_addr);

    // POST 20 metrics for two routes
    for _ in 0..10 {
        client
            .post(format!("{}/api/metrics", ingest_base))
            .json(&serde_json::json!({
                "route": "GET /api/users",
                "method": "GET",
                "status": 200,
                "durationMs": 30,
                "egressBytes": 512
            }))
            .send()
            .await
            .unwrap();
    }
    for _ in 0..10 {
        client
            .post(format!("{}/api/metrics", ingest_base))
            .json(&serde_json::json!({
                "route": "POST /api/orders",
                "method": "POST",
                "status": 201,
                "durationMs": 80,
                "egressBytes": 2048
            }))
            .send()
            .await
            .unwrap();
    }

    // Fetch projections
    let resp = reqwest::get(format!("{}/api/projections", dashboard_base))
        .await
        .unwrap();
    assert_eq!(200, resp.status());
    let body: serde_json::Value = resp.json().await.unwrap();

    let projections = body["projections"].as_array().unwrap();
    assert_eq!(2, projections.len(), "expected 2 route projections");

    for p in projections {
        assert!(p["projected_monthly_cost_usd"].as_f64().unwrap() >= 0.0);
        assert_eq!(12, p["cost_curve"].as_array().unwrap().len());
    }

    let summary = &body["summary"];
    assert!(summary["totalProjectedMonthlyCostUsd"].as_f64().unwrap() >= 0.0);

    h1.abort();
    h2.abort();
}
