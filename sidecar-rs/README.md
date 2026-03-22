# cloudmeter-sidecar

A lightweight native sidecar that collects per-endpoint metrics from Python and
Node.js applications and projects their cloud infrastructure cost in real time.

```
[Your Python/Node app]  →  POST /api/metrics  →  [cloudmeter-sidecar]
                                                         │
                                                         ▼
                                            Dashboard on :7777
                                      "GET /api/export costs $340/month"
```

## Why Rust?

The CloudMeter Java agent handles JVM apps with zero code changes. Python and
Node.js apps need something different — a separate sidecar process their
instrumentation client can POST metrics to. Rust was chosen because:

- **No runtime dependency** — 1.4 MB stripped binary, nothing to install besides the binary itself.
- **Fast startup** — ready in milliseconds, zero JVM warmup.
- **Low overhead** — the sidecar has no measurable effect on your app's latency.
- **Cross-platform** — ships as a single static binary for Linux, macOS, and Windows.

---

## Quick start

```bash
# Download and run (Linux x86-64)
./cloudmeter-sidecar --provider AWS --region us-east-1 --target-users 1000

# Sidecar is now ready:
#   Ingest    → http://127.0.0.1:7778/api/metrics
#   Dashboard → http://localhost:7777
```

Open `http://localhost:7777` to see live cost projections as your app receives traffic.

---

## CLI flags

| Flag | Default | Description |
|---|---|---|
| `--provider` | `AWS` | Cloud provider: `AWS`, `GCP`, or `AZURE` |
| `--region` | `us-east-1` | Cloud region (e.g. `us-central1`, `eastus`) |
| `--target-users` | `1000` | Concurrent users to project costs at |
| `--requests-per-user-per-second` | `1.0` | Scaling denominator for the projection formula |
| `--budget-usd` | `0.0` | Monthly budget per endpoint (0 = no threshold) |
| `--dashboard-port` | `7777` | Port for the live cost dashboard |
| `--ingest-port` | `7778` | Port that accepts `POST /api/metrics` from your app |

---

## API reference

### Ingest server (`:7778`)

#### `POST /api/metrics`

Accept a single request metric from your application. Fire-and-forget — your
client should not wait on this response.

**Request body:**
```json
{
  "route":      "GET /api/users/{id}",
  "method":     "GET",
  "status":     200,
  "durationMs": 45,
  "egressBytes": 1024
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `route` | string | yes | Normalised route template (e.g. `GET /api/users/{id}`) |
| `method` | string | yes | HTTP method, upper-cased |
| `status` | number | no | HTTP response status code (default 200) |
| `durationMs` | number | yes | Wall-clock request duration in milliseconds |
| `egressBytes` | number | no | Response body size in bytes (default 0) |

**Response:** `202 Accepted`
```json
{"accepted": true}
```

#### `GET /api/status`

```json
{
  "status": "ok",
  "recording": true,
  "totalMetrics": 1024
}
```

---

### Dashboard server (`:7777`)

#### `GET /`

Serves the live cost dashboard HTML page.

#### `GET /api/projections`

Returns cost projections for all observed routes.

```json
{
  "meta": {
    "provider": "AWS",
    "region": "us-east-1",
    "targetUsers": 1000,
    "requestsPerUserPerSecond": 1.0,
    "budgetUsd": 0.0,
    "pricingDate": "2025-01-01",
    "pricingSource": "static"
  },
  "projections": [
    {
      "route": "GET /api/export/pdf",
      "observed_rps": 0.0333,
      "projected_rps": 33.3,
      "projected_monthly_cost_usd": 340.12,
      "projected_cost_per_user_usd": 0.34012,
      "recommended_instance": "c5.2xlarge",
      "median_duration_ms": 350.0,
      "median_cpu_ms": 0.0,
      "exceeds_budget": false,
      "cost_curve": [
        {"users": 100,  "monthly_cost_usd": 34.01},
        {"users": 1000, "monthly_cost_usd": 340.12}
      ]
    }
  ],
  "warmupSummary": {
    "has_data": false,
    "request_count": 0,
    "warmup_window_seconds": 30,
    "total_cpu_core_seconds": 0.0,
    "total_egress_bytes": 0,
    "estimated_cold_start_cost_usd": 0.0
  },
  "summary": {
    "totalProjectedMonthlyCostUsd": 340.12,
    "anyExceedsBudget": false
  }
}
```

#### `POST /api/recording/start`

Clears buffered metrics and begins a new recording window.
```json
{"status": "recording"}
```

#### `POST /api/recording/stop`

Pauses metric ingestion (metrics are silently dropped while stopped).
```json
{"status": "stopped"}
```

---

## Integrating from Python

```python
import threading, requests

_SIDECAR = "http://127.0.0.1:7778"

def report(route: str, method: str, status: int, duration_ms: int, egress_bytes: int = 0):
    """Fire-and-forget: send metric to CloudMeter sidecar."""
    def _send():
        try:
            requests.post(f"{_SIDECAR}/api/metrics", json={
                "route": route, "method": method,
                "status": status, "durationMs": duration_ms,
                "egressBytes": egress_bytes,
            }, timeout=0.1)
        except Exception:
            pass
    threading.Thread(target=_send, daemon=True).start()
```

**Flask middleware example:**
```python
import time
from flask import Flask, request, g

app = Flask(__name__)

@app.before_request
def before():
    g.start = time.monotonic()

@app.after_request
def after(response):
    duration_ms = int((time.monotonic() - g.start) * 1000)
    route = request.url_rule.rule if request.url_rule else request.path
    report(f"{request.method} {route}", request.method,
           response.status_code, duration_ms,
           int(response.headers.get("Content-Length", 0)))
    return response
```

---

## Integrating from Node.js

```js
const http = require('http');

function reportMetric({ route, method, status, durationMs, egressBytes = 0 }) {
  const body = JSON.stringify({ route, method, status, durationMs, egressBytes });
  const req = http.request({
    hostname: '127.0.0.1', port: 7778,
    path: '/api/metrics', method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
  });
  req.on('error', () => {}); // fire-and-forget
  req.end(body);
}
```

**Express middleware example:**
```js
const express = require('express');
const app = express();

app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    reportMetric({
      route: `${req.method} ${req.route?.path ?? req.path}`,
      method: req.method,
      status: res.statusCode,
      durationMs: Date.now() - start,
      egressBytes: parseInt(res.getHeader('content-length') ?? '0', 10),
    });
  });
  next();
});
```

> **CPU accuracy note:** Python and Node.js cannot attribute CPU time per
> request (GIL / single event loop). The sidecar uses wall-clock duration as a
> proxy, so cost accuracy is ±40% vs the Java agent's ±20%. Egress and
> instance selection remain accurate.

---

## Building from source

**Prerequisites:** Rust 1.75+ (`rustup` or `curl https://sh.rustup.rs | sh`)

```bash
git clone https://github.com/studymadara/cloudmeter
cd cloudmeter/sidecar-rs

# Debug build (fast)
cargo build

# Optimised release build (~1.4 MB stripped binary)
cargo build --release

# Run
./target/release/cloudmeter-sidecar --provider AWS --target-users 1000
```

---

## Running tests

```bash
# All tests (unit + integration)
cargo test

# With verbose output
cargo test -- --nocapture
```

The test suite includes:

- **17 unit tests** — `store`, `projector`, `pricing`, `model` modules
- **12 integration tests** — real axum servers on ephemeral ports, covering all
  HTTP endpoints including the full ingest → projection flow

---

## Code coverage

```bash
# Prerequisites (one-time)
rustup component add llvm-tools-preview
cargo install cargo-llvm-cov

# Text summary
make coverage

# HTML report (opens at target/llvm-cov/html/index.html)
make coverage-html
```

Current coverage (library code, `main.rs` excluded as binary entry point):

| Module | Line coverage |
|---|---|
| `server.rs` | 100% |
| `store.rs` | 100% |
| `model.rs` | 100% |
| `projector.rs` | ~100% |
| `pricing.rs` | ~98% |

---

## Smoke test

```bash
# Build, start, send 30 metrics, assert projections — all in one script
scripts/smoke-rust.sh
```

Requires `curl` and `python3` on `PATH`.

---

## Security notes

- The sidecar binds to `127.0.0.1` only — it is not reachable from outside the host.
- No authentication on dashboard endpoints. Do **not** expose `:7777` or `:7778` publicly.
- Only route templates, HTTP methods, status codes, durations, and byte counts are
  ever collected. Request/response bodies and headers are never captured.

---

## License

Apache 2.0 — see [LICENSE](../LICENSE) at the repository root.
