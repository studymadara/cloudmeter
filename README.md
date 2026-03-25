# CloudMeter

> **"Your `/api/export/pdf` endpoint costs $340/month."**
> No APM tool tells you this. CloudMeter does.

[![CI](https://github.com/studymadara/cloudmeter/actions/workflows/ci.yml/badge.svg)](https://github.com/studymadara/cloudmeter/actions/workflows/ci.yml)
[![CI — Rust](https://github.com/studymadara/cloudmeter/actions/workflows/ci-rust.yml/badge.svg)](https://github.com/studymadara/cloudmeter/actions/workflows/ci-rust.yml)
[![codecov](https://codecov.io/gh/studymadara/cloudmeter/branch/develop/graph/badge.svg)](https://codecov.io/gh/studymadara/cloudmeter)
[![GitHub release](https://img.shields.io/github/v/release/studymadara/cloudmeter)](https://github.com/studymadara/cloudmeter/releases/latest)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Per-endpoint cloud cost attribution for Java, Python, Node.js, and Rust apps. No cloud credentials, no SaaS subscription.

---

## Java

One flag. Costs in under 5 minutes.

```bash
java -javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=1000 \
     -jar myapp.jar
```

Open **http://localhost:7777**, click **Start Recording**, exercise your app, see costs per endpoint.

## Python

```bash
pip install cloudmeter
```

```python
# Flask
from cloudmeter.flask import CloudMeterFlask
CloudMeterFlask(app, provider="AWS", target_users=1000)

# FastAPI
from cloudmeter.fastapi import CloudMeterMiddleware
app.add_middleware(CloudMeterMiddleware, provider="AWS", target_users=1000)

# Django — settings.py
MIDDLEWARE = ['cloudmeter.django.CloudMeterMiddleware', ...]
CLOUDMETER  = {"provider": "AWS", "target_users": 1000}
```

## Node.js

```bash
npm install cloudmeter
```

```js
// Express
const { cloudMeter } = require('cloudmeter')
app.use(cloudMeter({ provider: 'AWS', targetUsers: 1000 }))

// Fastify
const { cloudMeterPlugin } = require('cloudmeter')
await fastify.register(cloudMeterPlugin, { provider: 'AWS', targetUsers: 1000 })
```

## Rust

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
        .route("/api/users/:id", get(handler))
        .route_layer(cm.layer()); // one line
    axum::serve(tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap(), app).await.unwrap();
}
```

---

## Docs

Everything else is in the **[wiki](https://github.com/studymadara/cloudmeter/wiki)**:
configuration options, Docker/Kubernetes setup, CI cost gates, architecture decisions, contributing guide.

## License

Apache 2.0 — see [LICENSE](LICENSE).
