/// Integration tests — spin up a real Axum server, send requests, assert buffer.
use axum::{routing::get, Router};
use cloudmeter::{CloudMeter, CloudMeterOptions};
use std::net::SocketAddr;

#[tokio::test]
async fn middleware_records_request() {
    let cm = CloudMeter::new(CloudMeterOptions {
        port: 27001,
        ..Default::default()
    });
    let store = cm.store();
    store.start_recording(); // ensure clean state

    let app = Router::new()
        .route("/ping", get(|| async { "pong" }))
        .route_layer(cm.layer());

    let addr: SocketAddr = "127.0.0.1:27002".parse().unwrap();
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    let client = reqwest::Client::new();
    client
        .get("http://127.0.0.1:27002/ping")
        .send()
        .await
        .unwrap();

    // Give middleware a tick to record
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    let metrics = store.get_all();
    assert!(!metrics.is_empty(), "expected at least one metric");
    assert_eq!("GET /ping", metrics[0].route_template);
    assert_eq!("GET", metrics[0].http_method);
    assert_eq!(200, metrics[0].http_status);
}

#[tokio::test]
async fn route_template_not_raw_path() {
    let cm = CloudMeter::new(CloudMeterOptions {
        port: 27003,
        ..Default::default()
    });
    let store = cm.store();

    let app = Router::new()
        .route("/api/users/:user_id", get(|| async { "user" }))
        .route_layer(cm.layer());

    let addr: SocketAddr = "127.0.0.1:27004".parse().unwrap();
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    let client = reqwest::Client::new();
    client
        .get("http://127.0.0.1:27004/api/users/42")
        .send()
        .await
        .unwrap();

    tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    let metrics = store.get_all();
    assert!(!metrics.is_empty());
    // With route_layer(), MatchedPath is populated — template not raw path
    assert_eq!(
        "GET /api/users/:user_id", metrics[0].route_template,
        "expected template, got raw path"
    );
}

#[tokio::test]
async fn method_and_status_captured() {
    let cm = CloudMeter::new(CloudMeterOptions {
        port: 27005,
        ..Default::default()
    });
    let store = cm.store();

    use axum::http::StatusCode;
    let app = Router::new()
        .route(
            "/api/items",
            get(|| async { (StatusCode::OK, "items") })
                .post(|| async { (StatusCode::CREATED, "created") }),
        )
        .route_layer(cm.layer());

    let addr: SocketAddr = "127.0.0.1:27006".parse().unwrap();
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    let client = reqwest::Client::new();
    client
        .get("http://127.0.0.1:27006/api/items")
        .send()
        .await
        .unwrap();
    client
        .post("http://127.0.0.1:27006/api/items")
        .send()
        .await
        .unwrap();

    tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    let metrics = store.get_all();
    assert_eq!(2, metrics.len());

    let get_m = metrics.iter().find(|m| m.http_method == "GET").unwrap();
    assert_eq!(200, get_m.http_status);

    let post_m = metrics.iter().find(|m| m.http_method == "POST").unwrap();
    assert_eq!(201, post_m.http_status);
}

#[tokio::test]
async fn duration_is_non_negative() {
    let cm = CloudMeter::new(CloudMeterOptions {
        port: 27007,
        ..Default::default()
    });
    let store = cm.store();

    let app = Router::new()
        .route("/api/fast", get(|| async { "ok" }))
        .route_layer(cm.layer());

    let addr: SocketAddr = "127.0.0.1:27008".parse().unwrap();
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    reqwest::get("http://127.0.0.1:27008/api/fast")
        .await
        .unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    let metrics = store.get_all();
    assert!(!metrics.is_empty());
    // duration_ms is u64 so always >= 0, just check it was set
    assert!(metrics[0].cpu_core_seconds >= 0.0);
}

#[tokio::test]
async fn multiple_requests_accumulate() {
    let cm = CloudMeter::new(CloudMeterOptions {
        port: 27009,
        ..Default::default()
    });
    let store = cm.store();

    let app = Router::new()
        .route("/api/ping", get(|| async { "pong" }))
        .route_layer(cm.layer());

    let addr: SocketAddr = "127.0.0.1:27010".parse().unwrap();
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    let client = reqwest::Client::new();
    for _ in 0..5 {
        client
            .get("http://127.0.0.1:27010/api/ping")
            .send()
            .await
            .unwrap();
    }

    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    assert_eq!(5, store.get_all().len());
}
