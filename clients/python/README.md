# cloudmeter (Python)

Per-endpoint cloud cost monitoring for Python web apps.
Supports Flask, FastAPI, and Django — one import, one line.

```
[Your Flask/FastAPI/Django app]
        │
        │  in-process (zero overhead, no sidecar)
        ▼
[cloudmeter buffer]  →  http://localhost:7777
                     "GET /api/export costs $340/month"
```

---

## Install

```bash
pip install cloudmeter
```

Zero required dependencies. No sidecar binary. No external process.
The cost projector and dashboard run entirely in-process.

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
| `port` | `7777` | Port for the live cost dashboard |

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

## Dashboard panels

Open **http://localhost:7777** after starting your app:

| Panel | What it shows |
|---|---|
| **Cost table** | Each route with projected monthly cost, cost per user, recommended instance |
| **Cost curve** | Cost vs concurrent-user count chart (100 → 1M users) for any selected route |
| **Variance panel** | p50/p95/p99 per-request cost; p95/p50 > 1.5× flags N+1 queries or missing indexes |
| **Recording controls** | Start/stop metric collection; first 30 s is warmup (excluded from projections) |

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
- The dashboard binds to `127.0.0.1` only — not reachable from outside the host
- No authentication on the dashboard — do not expose port 7777 publicly
- No cloud credentials ever required — pricing uses static embedded tables

---

## Running tests

```bash
pip install pytest pytest-asyncio httpx flask "fastapi[standard]" django
cd clients/python
pytest tests/ -v
```

86 tests covering Flask, FastAPI, Django middleware, the reporter buffer,
cost projector, and dashboard server.

---

## Troubleshooting

**Dashboard shows no data:**
Make sure your app is receiving real HTTP requests (or use its test client).
Endpoints only appear after they have been hit.

**First 30 seconds shows "warmup excluded":**
Normal — the first 30 s after recording starts are excluded to avoid JIT/cold-start
noise inflating cost projections. Data will appear automatically after warmup.

**Port conflict (`Address already in use`):**
Change the dashboard port:
```python
CloudMeterFlask(app, port=17777)
```

---

## License

Apache 2.0 — see [LICENSE](../../LICENSE) at the repository root.
