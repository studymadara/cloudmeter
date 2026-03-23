# cloudmeter (Python)

Per-endpoint cloud cost monitoring for Python web apps.
Supports Flask, FastAPI, and Django — one import, one line.

```
[Your Flask/FastAPI/Django app]
        │
        │  fire-and-forget POST (daemon thread, ~0ms overhead)
        ▼
[cloudmeter-sidecar]  →  http://localhost:7777
                     "GET /api/export costs $340/month"
```

---

## Install

```bash
pip install cloudmeter
```

The sidecar binary (~1.4 MB, no runtime required) is downloaded automatically
on first run and cached at `~/.cloudmeter/bin/`. Nothing else to install.

---

## Quick start

### Flask

```python
from flask import Flask
from cloudmeter.flask import CloudMeterFlask

app = Flask(__name__)
CloudMeterFlask(app, provider="AWS", target_users=1000)

@app.route("/api/users/<int:user_id>")
def get_user(user_id):
    return {"id": user_id}
```

Open **http://localhost:7777** — costs appear as soon as requests hit your app.

### FastAPI

```python
from fastapi import FastAPI
from cloudmeter.fastapi import CloudMeterMiddleware

app = FastAPI()
app.add_middleware(CloudMeterMiddleware, provider="AWS", target_users=1000)

@app.get("/api/users/{user_id}")
def get_user(user_id: int):
    return {"id": user_id}
```

### Django — `settings.py`

```python
MIDDLEWARE = [
    'cloudmeter.django.CloudMeterMiddleware',
    # ... your other middleware
]

CLOUDMETER = {
    "provider":     "AWS",
    "region":       "us-east-1",
    "target_users": 1000,
}
```

---

## Configuration options

All options are optional — defaults work out of the box.

| Option | Default | Description |
|---|---|---|
| `provider` | `"AWS"` | Cloud provider: `"AWS"`, `"GCP"`, or `"AZURE"` |
| `region` | `"us-east-1"` | Cloud region (e.g. `"us-central1"`, `"eastus"`) |
| `target_users` | `1000` | Concurrent users to project cost at |
| `requests_per_user_per_second` | `1.0` | Scaling factor for the projection |
| `budget_usd` | `0.0` | Monthly budget alert per endpoint (0 = off) |
| `ingest_port` | `7778` | Port the sidecar listens on for metrics |
| `dashboard_port` | `7777` | Port for the live cost dashboard |

### Flask — app factory pattern

```python
from cloudmeter.flask import CloudMeterFlask

cm = CloudMeterFlask()

def create_app():
    app = Flask(__name__)
    cm.init_app(app, provider="GCP", region="us-central1", target_users=5000)
    return app
```

### FastAPI — full options example

```python
app.add_middleware(
    CloudMeterMiddleware,
    provider="AWS",
    region="eu-west-1",
    target_users=50_000,
    budget_usd=200.0,
)
```

### Django — full options in `settings.py`

```python
CLOUDMETER = {
    "provider":                     "AZURE",
    "region":                       "eastus",
    "target_users":                 2000,
    "requests_per_user_per_second": 2.0,
    "budget_usd":                   500.0,
}
```

---

## How route templates work

CloudMeter reports route templates, not raw paths — so all traffic to
`/api/users/1`, `/api/users/42`, and `/api/users/999` is grouped as a single
endpoint with a single cost projection.

| Framework | Template source | Example |
|---|---|---|
| Flask | `request.url_rule` | `GET /api/users/<int:user_id>` |
| FastAPI | `scope["route"].path` | `GET /api/users/{user_id}` |
| Django | `request.resolver_match.route` | `GET api/users/<int:pk>/` |

---

## What's measured

For each request:
- **Route template** — not the raw URL
- **HTTP method** — GET, POST, etc.
- **HTTP status code** — 200, 404, 500, etc.
- **Wall-clock duration** — in milliseconds
- **Response size** — egress bytes (from `Content-Length` header)

> **CPU accuracy note:** Python cannot attribute per-request CPU time because
> of the GIL. Wall-clock time is used as a proxy. Cost accuracy is ±40% vs
> the Java agent's ±20%. Egress and instance selection are not affected.

---

## Privacy and security

- Request and response **bodies are never captured** — only route, method, status, duration, and byte counts
- The sidecar binds to `127.0.0.1` only — not reachable from outside the host
- No authentication on the dashboard — do not expose port 7777 publicly
- No cloud credentials ever required — pricing uses static embedded tables

---

## Running tests

```bash
pip install pytest pytest-asyncio httpx flask "fastapi[standard]"
cd clients/python
pytest tests/ -v
```

34 tests covering Flask, FastAPI, Django middleware and the reporter.

---

## Troubleshooting

**First run is slow (a few seconds):**
Normal — the sidecar binary is being downloaded once. Subsequent runs are instant.

**Dashboard shows no data:**
Make sure your app is receiving real HTTP requests (or use its test client). The
sidecar only shows endpoints that have been hit.

**"No binary found for your platform":**
Your platform may not have a pre-built binary yet. See the
[releases page](https://github.com/studymadara/cloudmeter/releases) or
[build from source](https://github.com/studymadara/cloudmeter/tree/main/sidecar-rs).

**Port conflict (`Address already in use`):**
Change the ports:
```python
CloudMeterFlask(app, ingest_port=17778, dashboard_port=17777)
```

---

## License

Apache 2.0 — see [LICENSE](../../LICENSE) at the repository root.
