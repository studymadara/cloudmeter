# cloudmeter (Node.js)

Per-endpoint cloud cost monitoring for Node.js web apps.
Supports Express and Fastify — one import, one line.

```
[Your Express/Fastify app]
        │
        │  fire-and-forget HTTP request (non-blocking)
        ▼
[cloudmeter-sidecar]  →  http://localhost:7777
                     "GET /api/export costs $340/month"
```

---

## Install

```bash
npm install cloudmeter
```

The sidecar binary (~1.4 MB, no runtime required) is downloaded automatically
on first run and cached at `~/.cloudmeter/bin/`. Nothing else to install.

---

## Quick start

### Express

```js
const express   = require('express')
const { cloudMeter } = require('cloudmeter')

const app = express()
app.use(cloudMeter({ provider: 'AWS', targetUsers: 1000 }))

app.get('/api/users/:userId', (req, res) => {
  res.json({ id: req.params.userId })
})

app.listen(3000)
```

Open **http://localhost:7777** — costs appear as soon as requests hit your app.

### Fastify

```js
const Fastify = require('fastify')
const { cloudMeterPlugin } = require('cloudmeter')

const fastify = Fastify()
await fastify.register(cloudMeterPlugin, { provider: 'AWS', targetUsers: 1000 })

fastify.get('/api/users/:userId', async (req) => ({ id: req.params.userId }))

await fastify.listen({ port: 3000 })
```

---

## Configuration options

All options are optional — defaults work out of the box.

| Option | Default | Description |
|---|---|---|
| `provider` | `'AWS'` | Cloud provider: `'AWS'`, `'GCP'`, or `'AZURE'` |
| `region` | `'us-east-1'` | Cloud region (e.g. `'us-central1'`, `'eastus'`) |
| `targetUsers` | `1000` | Concurrent users to project cost at |
| `requestsPerUserPerSecond` | `1.0` | Scaling factor for the projection |
| `budgetUsd` | `0` | Monthly budget alert per endpoint (0 = off) |
| `ingestPort` | `7778` | Port the sidecar listens on for metrics |
| `dashboardPort` | `7777` | Port for the live cost dashboard |

### Full options example

```js
// Express
app.use(cloudMeter({
  provider:                   'GCP',
  region:                     'us-central1',
  targetUsers:                50_000,
  requestsPerUserPerSecond:   2.0,
  budgetUsd:                  200,
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

## How route templates work

CloudMeter groups all traffic to `/api/users/1`, `/api/users/42`, and
`/api/users/999` under a single route — so each endpoint has one cost
projection instead of one per unique URL.

| Framework | Template source | Example |
|---|---|---|
| Express | `req.route.path` | `GET /api/users/:userId` |
| Fastify | `request.routeOptions.url` | `GET /api/users/:userId` |

> **Note:** `cloudMeter()` must be registered **before** your routes so that
> `req.route` is populated by the time the `finish` event fires.

---

## TypeScript

The package ships CommonJS only (`require`). For TypeScript projects:

```ts
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { cloudMeter } = require('cloudmeter')
```

Or add a simple declaration:
```ts
// cloudmeter.d.ts
declare module 'cloudmeter' {
  export function cloudMeter(opts?: Record<string, unknown>): import('express').RequestHandler
  export function cloudMeterPlugin(fastify: unknown, opts: Record<string, unknown>): Promise<void>
}
```

---

## What's measured

For each request:
- **Route template** — not the raw URL
- **HTTP method** — GET, POST, etc.
- **HTTP status code** — 200, 404, 500, etc.
- **Wall-clock duration** — in milliseconds
- **Response size** — egress bytes (from `Content-Length` header)

> **CPU accuracy note:** Node.js runs on a single event loop — per-request CPU
> time is not measurable. Wall-clock time is used as a proxy. Cost accuracy is
> ±40% vs the Java agent's ±20%. Egress and instance selection are not affected.

---

## Privacy and security

- Request and response **bodies are never captured** — only route, method, status, duration, and byte counts
- The sidecar binds to `127.0.0.1` only — not reachable from outside the host
- No authentication on the dashboard — do not expose port 7777 publicly
- No cloud credentials ever required — pricing uses static embedded tables

---

## Running tests

```bash
cd clients/node
npm install
node --test src/__tests__/reporter.test.js src/__tests__/express.test.js src/__tests__/fastify.test.js
```

21 tests covering the reporter, Express middleware, and Fastify plugin.

---

## Troubleshooting

**First run is slow (a few seconds):**
Normal — the sidecar binary is being downloaded once. Subsequent runs are instant.

**Dashboard shows no data:**
Make sure your app is receiving real HTTP requests. The sidecar only shows
endpoints that have been hit since the last recording started.

**"No binary found for your platform":**
See the [releases page](https://github.com/studymadara/cloudmeter/releases) or
[build from source](https://github.com/studymadara/cloudmeter/tree/main/sidecar-rs).

**Port conflict (`listen EADDRINUSE`):**
```js
app.use(cloudMeter({ ingestPort: 17778, dashboardPort: 17777 }))
```

**Sidecar process not cleaning up on Ctrl+C:**
This is handled automatically via `process.on('SIGINT')`. If you have custom
signal handlers, call `require('cloudmeter').sidecar.stop()` in your cleanup.

---

## License

Apache 2.0 — see [LICENSE](../../LICENSE) at the repository root.
