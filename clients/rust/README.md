# CloudMeter — Rust (Axum) Client

Per-endpoint cloud cost attribution for [Axum](https://github.com/tokio-rs/axum) services.
Add one line of middleware and CloudMeter will tell you exactly what each route costs to run —
e.g. `GET /api/export/pdf` → **$340/month at 10 000 users**.

No sidecar. No cloud credentials. Fully in-process.

---

## Quick start

```toml
# Cargo.toml
[dependencies]
cloudmeter = "0.1"
```

```rust
use axum::{Router, routing::get};
use cloudmeter::{CloudMeter, CloudMeterOptions};

#[tokio::main]
async fn main() {
    let cm = CloudMeter::new(CloudMeterOptions::default());

    let app = Router::new()
        .route("/api/users/:id",  get(get_user))
        .route("/api/products",   get(list_products))
        .route("/api/export/pdf", get(export_pdf))
        .route_layer(cm.layer()); // <-- one line

    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
```

Open **http://localhost:7777** to see the live cost dashboard.

---

## How it works

```
Request in
    │
    ▼
CloudMeterLayer (Tower middleware)
    │  records: route template, method, status, duration_ms, egress_bytes
    ▼
Your handler
    │
    ▼
Response out
    │
    ▼
MetricsStore (in-process ring buffer, 10 000 req cap)
    │
    ▼
Cost projector  ──►  monthly_cost = instance_hours × rate + egress_gib × rate
    │                (scales linearly from observed RPS to target user count)
    ▼
Dashboard  http://localhost:7777  (background thread, vanilla JS)
```

A 30-second warmup window excludes JIT-cold requests from projections.

---

## Configuration

```rust
CloudMeterOptions {
    provider: "AWS".into(),           // "AWS" | "GCP" | "Azure"
    region:   "us-east-1".into(),     // informational label
    target_users: 10_000,             // scale target for projections
    requests_per_user_per_second: 1.0,
    budget_usd: 500.0,                // 0.0 = no budget check
    port: 7777,                       // dashboard port
}
```

`CloudMeterOptions::default()` → AWS / us-east-1 / 1 000 users / port 7777.

---

## Route templates vs raw paths

Use **`Router::route_layer()`** (not `layer()`) so the middleware sees the matched
route template (`/api/users/:id`) instead of the raw request path (`/api/users/42`).

```rust
// CORRECT — route template captured
app.route("/api/users/:id", get(handler))
   .route_layer(cm.layer())

// raw path captured (/api/users/42) — each user ID becomes a separate "route"
app.route("/api/users/:id", get(handler))
   .layer(cm.layer())
```

This matters because with raw paths every unique user ID appears as a distinct
endpoint, blowing up the metric cardinality and producing nonsense projections.

---

## Dashboard API

The embedded dashboard exposes a small JSON API on the configured port:

| Endpoint | Description |
|---|---|
| `GET /api/projections` | `{ projections: [...], stats: [...] }` — cost projections and percentile stats |
| `GET /api/recording/status` | `{ recording: bool, warmupSeconds: 30, metricsCount: N }` |
| `POST /api/recording/start` | Begin a fresh recording window |
| `POST /api/recording/stop` | Stop accumulating metrics |

All responses include `Access-Control-Allow-Origin: *`.

---

## Accessing the store programmatically

```rust
let cm = CloudMeter::new(CloudMeterOptions::default());
let store = cm.store();

// start / stop recording
store.start_recording();
store.stop_recording();

// read raw metrics
let metrics: Vec<RequestMetrics> = store.get_all();

// run projections yourself
use cloudmeter::projector::{compute_stats, project};
let opts = CloudMeterOptions::default();
let projs = project(&metrics, &opts);
let stats = compute_stats(&metrics, &projs, &opts);
```

---

## CPU accuracy note

The Rust client uses `duration_ms / 1000` as a proxy for `cpu_core_seconds`
(the actual CPU consumed by the handler). This gives cost projections accurate to
**±40%** — useful for ranking endpoints by relative cost and catching expensive
outliers, but less precise than the Java agent (±20%) which samples real CPU time
via `ThreadMXBean`.

---

## Tests

```bash
cargo test            # unit + integration (24 tests)
cargo clippy -- -D warnings
cargo fmt --check
```

Integration tests spin up real Axum servers on loopback ports and assert that
route templates, methods, status codes, and accumulated metrics are captured
correctly.
