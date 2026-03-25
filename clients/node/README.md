# cloudmeter (Node.js)

Per-endpoint cloud cost monitoring for Node.js web apps.
Supports Express and Fastify — one import, one line, zero dependencies.

```
[Your Express/Fastify app]
        │
        │  middleware intercepts every request
        ▼
[in-process cost projector]  →  http://localhost:7777
                             "GET /api/export costs $340/month"
```

---

## Install

```bash
npm install cloudmeter
```

No binary download. No subprocess. No sidecar. Pure Node.js.

---

## Quick start

### Express

```js
const express        = require('express')
const { cloudMeter } = require('cloudmeter')

const app = express()
app.use(cloudMeter({ provider: 'AWS', targetUsers: 1000 }))

app.get('/api/users/:userId', (req, res) => {
  res.json({ id: req.params.userId })
})

app.listen(3000)
```

### Fastify

```js
const Fastify              = require('fastify')
const { cloudMeterPlugin } = require('cloudmeter')

const fastify = Fastify()
await fastify.register(cloudMeterPlugin, { provider: 'AWS', targetUsers: 1000 })

fastify.get('/api/users/:userId', async (req) => ({ id: req.params.userId }))

await fastify.listen({ port: 3000 })
```

Open **http://localhost:7777** — costs appear as soon as requests hit your app.

---

## Configuration options

All options are optional — defaults work out of the box.

| Option | Default | Description |
|---|---|---|
| `provider` | `'AWS'` | Cloud provider: `'AWS'`, `'GCP'`, or `'AZURE'` |
| `region` | `'us-east-1'` | Cloud region (informational — used in dashboard display) |
| `targetUsers` | `1000` | Concurrent users to project cost at |
| `requestsPerUserPerSecond` | `1.0` | Scaling factor for the cost projection |
| `budgetUsd` | `0` | Monthly budget alert per endpoint (`0` = disabled) |
| `port` | `7777` | Port for the live cost dashboard |

### Full example

```js
// Express
app.use(cloudMeter({
  provider:                  'GCP',
  region:                    'us-central1',
  targetUsers:               50_000,
  requestsPerUserPerSecond:  2.0,
  budgetUsd:                 200,
  port:                      7777,
}))

// Fastify
await fastify.register(cloudMeterPlugin, {
  provider:     'AZURE',
  region:       'eastus',
  targetUsers:  10_000,
  budgetUsd:    500,
})
```

---

## How it works

On first middleware call:

1. `reporter.startRecording()` begins buffering metrics in memory
2. The dashboard server starts at `http://127.0.0.1:<port>`

For every request:

1. Middleware records route, method, status, duration, and egress bytes
2. The in-process cost projector scales observed traffic to your `targetUsers`
3. The dashboard polls `/api/projections` every 5 seconds and renders live costs

No data ever leaves your process. No network calls. No cloud credentials.

---

## How route templates work

CloudMeter groups `/api/users/1`, `/api/users/42`, and `/api/users/999` under
one route so you get one cost projection per endpoint, not one per unique URL.

| Framework | Template source | Example |
|---|---|---|
| Express | `req.route.path` | `GET /api/users/:userId` |
| Fastify | `request.routeOptions.url` | `GET /api/users/:userId` |

> `cloudMeter()` must be registered **before** your routes.

---

## Dashboard

The dashboard at `http://localhost:7777` shows:

- **Cost projection table** — route, projected $/month, $/user, obs RPS, proj RPS, recommended instance
- **Cost curve explorer** — drag a slider to see cost at any user count (100 → 1M)
- **Variance panel** — p50/p95/p99 cost per request; flags routes where p95 > 1.5× p50 (missing index, N+1 query signal)
- **Recording controls** — Start / Stop recording buttons; only live traffic is projected

---

## What's measured

For each request:

- Route template (not raw URL)
- HTTP method
- HTTP status code
- Wall-clock duration (milliseconds)
- Response size (egress bytes, from `Content-Length` header)

> **CPU proxy:** Node.js has no per-request CPU measurement API. Wall-clock
> duration is used as a proxy for CPU core-seconds. Cost accuracy is ±40% vs
> the Java agent's ±20%. Egress costs and instance selection are not affected.

---

## Privacy and security

- Request and response **bodies are never captured** — only route, method, status, duration, and byte counts
- Dashboard binds to `127.0.0.1` only — not reachable from outside the host
- Dashboard has no authentication — do not expose port 7777 publicly
- No cloud credentials ever required — pricing uses static embedded tables (AWS/GCP/Azure on-demand, updated 2025-01-01)

---

## TypeScript

The package ships CommonJS only. For TypeScript projects add a declaration file:

```ts
// cloudmeter.d.ts
declare module 'cloudmeter' {
  interface CloudMeterOptions {
    provider?: 'AWS' | 'GCP' | 'AZURE'
    region?: string
    targetUsers?: number
    requestsPerUserPerSecond?: number
    budgetUsd?: number
    port?: number
  }
  export function cloudMeter(opts?: CloudMeterOptions): import('express').RequestHandler
  export function cloudMeterPlugin(fastify: unknown, opts: CloudMeterOptions): Promise<void>
}
```

---

## Running tests

```bash
cd clients/node
npm install
npm test           # 56 tests
npm run test:coverage  # coverage report (>99% statements)
```

---

## Troubleshooting

**Dashboard shows no data:**
Make sure requests are hitting your app after the middleware is registered. The
projector only uses traffic recorded since the last `startRecording()` call.

**Port conflict (`EADDRINUSE`):**
```js
app.use(cloudMeter({ port: 17777 }))
```

**Costs look very low:**
The projector uses a 5-minute rolling window. Send more traffic or wait for the
window to fill — `observedRps` in the dashboard table shows the current rate.

**`req.route` is null in Express:**
Middleware was registered after routes. Move `app.use(cloudMeter())` to before
your first `app.get()`/`app.post()`.

---

## License

Apache 2.0 — see [LICENSE](../../LICENSE) at the repository root.
