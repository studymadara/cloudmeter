# CloudMeter Wiki

> **"Your `/api/export/pdf` endpoint costs $340/month."**
> No APM tool tells you this. CloudMeter does.

CloudMeter attributes cloud cost to each API endpoint — without any code changes. Works with Java (JVM agent), Python (Flask, FastAPI, Django middleware), and Node.js (Express, Fastify middleware).

---

## Pages

| Page | What it covers |
|---|---|
| [Getting Started](Getting-Started.md) | Install and record your first session — Java, Python, and Node.js |
| [Python & Node.js Clients](Python-Node-Clients.md) | Full middleware docs for Flask, FastAPI, Django, Express, Fastify |
| [Agent Configuration](Agent-Configuration.md) | All Java agent configuration options and their defaults |
| [Dashboard](Dashboard.md) | Using the live cost dashboard — recording, charts, budget alerts |
| [CLI Usage](CLI-Usage.md) | `cloudmeter report` command, CI/CD cost gates, exit codes |
| [Cost Projection Model](Cost-Projection-Model.md) | How costs are measured, scaled, and projected |
| [Architecture](Architecture.md) | Module structure, ADRs, design decisions |
| [Contributing](Contributing.md) | Build setup, test suite, how to add pricing data or framework support |

---

## At a glance

```
java -javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=10000,budget=500 \
     -jar myapp.jar
```

1. Open [http://localhost:7777](http://localhost:7777)
2. Click **Start Recording** — use your app normally
3. Click **Stop Recording** — see cost per endpoint, ranked by impact

No cloud credentials. No code changes. No sidecar process.

---

## Key concepts

**Standalone cost attribution** — each endpoint's cost is computed as if a dedicated server served only that endpoint. The sum of all projections may exceed the actual server bill; this is intentional and clearly marked in all output.

**Cost curves** — projections are computed at 12 scale points (100 → 1M concurrent users). The dashboard renders these as a stacked area chart so you can see which endpoints blow up at scale.

**Budget threshold** — set `budget=500` (USD/month) and CloudMeter marks every endpoint that exceeds it on the dashboard, in terminal output (`!!`), and via a non-zero CLI exit code.
