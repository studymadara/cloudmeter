# CloudMeter

> **"Your `/api/export/pdf` endpoint costs $340/month."**
> No APM tool tells you this. CloudMeter does.

[![CI](https://github.com/studymadara/cloudmeter/actions/workflows/ci.yml/badge.svg)](https://github.com/studymadara/cloudmeter/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://openjdk.org/)

CloudMeter is an open source Java agent that attaches to your running JVM and tells you exactly what each of your API endpoints costs to run on AWS, GCP, or Azure — without any code changes.

---

## The problem

APM tools (Datadog, New Relic) show you performance. Cost tools (Infracost, AWS Cost Explorer) show you bills. Nobody connects the two. You have no idea which endpoint is eating your cloud budget.

CloudMeter fills that gap.

---

## Getting started

**One flag. No code changes.**

```bash
java -javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=10000,budget=500 \
     -jar myapp.jar
```

Agent args are passed as a comma-separated `key=value` string after the JAR path. All keys are case-insensitive.

| Arg | Default | Description |
|---|---|---|
| `provider` | `AWS` | Cloud provider: `AWS`, `GCP`, or `AZURE` |
| `region` | `us-east-1` | Cloud region for pricing lookup |
| `targetUsers` | `1000` | Concurrent users to project cost at |
| `rpu` | `1.0` | Requests per user per second |
| `budget` | `0` | Monthly USD budget (0 = no threshold) |
| `port` | `7777` | Dashboard port |

Open [http://localhost:7777](http://localhost:7777) — your cost dashboard is live.

1. Click **Start Recording**
2. Use your app normally (or run your test suite)
3. Click **Stop Recording**
4. See cost per endpoint, ranked by impact

---

## What you get

```
╔══════════════════════════════════════════════════════════════════════════════╗
║  ☁ CloudMeter  v0.1.0          aws / us-east-1          ● Recording  [Stop] ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  ENDPOINT COSTS  (standalone ⓘ)              120s recorded  •  359 requests ║
║  ┌──────────────────────────────┬──────────┬───────┬───────┬──────────────┐ ║
║  │ Endpoint                     │ p50/mo   │ p95   │ Ratio │ Relative     │ ║
║  ├──────────────────────────────┼──────────┼───────┼───────┼──────────────┤ ║
║  │ POST /api/export/pdf         │ $298     │ $342  │ 1.1×  │ ████████████ │ ║
║  │ GET  /api/users/{id}       ⚠ │ $ 11     │ $ 43  │ 3.8×  │ ██           │ ║
║  │ GET  /api/products           │ $  4     │ $  5  │ 1.2×  │ ▌            │ ║
║  └──────────────────────────────┴──────────┴───────┴───────┴──────────────┘ ║
║  ⚠ GET /api/users/{id} — p95 is 3.8× median. Check indexes for key values. ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

**Cost variance detection** is the killer feature: when some requests to the same endpoint cost 3× more than others, CloudMeter surfaces it. That's a missing DB index, not a traffic spike.

---

## How it works

CloudMeter uses a Java agent (`-javaagent`) to instrument your JVM at the bytecode level — the same technique used by the OpenTelemetry Java agent, Spring Sleuth, and AppDynamics. It intercepts every HTTP request without touching your source code.

For each request it measures:

| Signal | How | Why it matters |
|---|---|---|
| **CPU core-seconds** | `ThreadMXBean.getThreadCpuTime()` — exact, not sampled | Direct compute cost |
| **Thread wait ratio** | State sampling at 10ms intervals | `0.6` means 60% idle — you're paying for nothing |
| **Peak memory** | Sampled via JVM heap metrics | Memory-bound instance sizing |
| **Egress bytes** | Response output stream | Network cost (approximate) |

Cost is projected using public cloud on-demand pricing — no credentials, no cloud API calls.

---

## Cost variance — the diagnostic signal

CloudMeter tracks cost distribution per endpoint (p50 / p95 / p99), not just averages.

```
GET /api/users/{id}
  p50: $11/mo   p95: $43/mo   ratio: 3.8×  ⚠ variance warning

Outliers:
  /api/users/8823  380ms  $0.38
  /api/users/9104  290ms  $0.29
  /api/users/42    12ms   $0.01
```

A p95/p50 ratio above 1.5× means some parameter values are disproportionately expensive. Usually a missing index, a full table scan, or an N+1 query on specific data shapes.

---

## Docker

```bash
docker run \
  -v /path/to/cloudmeter-agent.jar:/cloudmeter/agent.jar \
  -e JAVA_TOOL_OPTIONS="-javaagent:/cloudmeter/agent.jar=provider=AWS,region=us-east-1,targetUsers=5000" \
  -p 7777:7777 \
  myapp:latest
```

> **Security**: Port 7777 is local-only. Do not expose it publicly — the dashboard has no authentication in v1.

---

## Kubernetes

```yaml
initContainers:
  - name: cloudmeter-installer
    image: busybox
    command: ["wget", "-O", "/agent/cloudmeter-agent.jar",
              "https://github.com/studymadara/cloudmeter/releases/latest/download/cloudmeter-agent.jar"]
    volumeMounts:
      - name: cloudmeter-agent
        mountPath: /agent
containers:
  - name: myapp
    env:
      - name: JAVA_TOOL_OPTIONS
        value: "-javaagent:/agent/cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=5000"
    volumeMounts:
      - name: cloudmeter-agent
        mountPath: /agent
volumes:
  - name: cloudmeter-agent
    emptyDir: {}
```

---

## Dynamic attach (no restart required)

```bash
# Attach to an already-running JVM
cloudmeter attach <pid>

# Find the PID
jps -l
```

---

## Configuration

```yaml
# cloudmeter.yaml — place in working directory
provider: aws          # aws | gcp | azure
region: us-east-1
target_users: 10000    # scale projections to this user count
requests_per_user_per_second: 0.5
budget: 500            # monthly USD — dashboard marks the threshold on the cost curve
port: 7777

# Optional: explicit route map for raw Servlet or unrecognised frameworks
routes:
  - GET /api/users/*
  - POST /api/orders/*
```

---

## CI/CD cost gate

```bash
# Fail the build if any endpoint exceeds $500/month at projected load
cloudmeter report --format json --budget 500 > cost-report.json
```

Exit code `1` if any endpoint breaches the budget. Attach `cost-report.json` as a CI artifact.

---

## Supported environments

| | Supported |
|---|---|
| Java 8, 11, 17, 21 | ✅ |
| Spring Boot 1.x, 2.x, 3.x | ✅ |
| JAX-RS | ✅ |
| Raw Servlet API | ✅ |
| AWS / GCP / Azure cost projection | ✅ |
| Spring WebFlux / Project Reactor | ❌ v2 |
| GraalVM native image | ❌ not supported |
| Virtual threads (Java 21) | ❌ v1 limitation |

---

## What CloudMeter does NOT do

- Read your AWS/GCP/Azure account (no credentials ever required)
- Capture request or response body content (metadata only — GDPR safe)
- Model downstream costs: DynamoDB, RDS, SQS, Lambda (v1 scope is compute only)
- Replace your APM tool (it complements it)

---

## Building from source

```bash
git clone https://github.com/studymadara/cloudmeter.git
cd cloudmeter
./gradlew build
```

Build the agent fat JAR (includes all dependencies, shaded):

```bash
./gradlew :agent:shadowJar
# Output: agent/build/libs/agent-0.1.0.jar
```

Run tests with coverage:

```bash
./gradlew test jacocoTestReport jacocoTestCoverageVerification
```

Run the end-to-end integration tests:

```bash
./gradlew :integration-test:test
```

Coverage reports are written to `*/build/reports/jacoco/test/html/index.html`.

---

## Project structure

```
cloudmeter/
├── collector/         RequestContext, MetricsStore (ring buffer), RouteStatsCalculator
│                      Route normalization · p50/p95/p99 variance tracking
│
├── cost-engine/       CostProjector, PricingCatalog (AWS/GCP/Azure), EndpointCostProjection
│                      Linear scaling model · instance selection · cost curve generation
│
├── reporter/          TerminalReporter — human-readable table with budget markers
│                      JsonReporter     — CI/CD JSON output (exits 1 on budget breach)
│                      DashboardServer  — embedded HTTP server on :7777 (127.0.0.1 only)
│                      dashboard.html   — SPA with Chart.js cost curves, 5 s auto-refresh
│
├── cli/               CloudMeterCli / CloudMeterMain — CLI entry point
│                      ReportCommand    — fetches projections from live dashboard
│                      CliArgs          — agent args parser (key=value format)
│                      JsonProjectionParser — zero-dependency JSON parser for projections API
│
├── agent/             AgentMain — premain() + agentmain() entry points (fat JAR)
│                      HttpInstrumentation  — Byte Buddy intercept of HttpServlet.service()
│                      HttpServletAdvice    — @Advice enter/exit hooks (javax + jakarta)
│                      ThreadStateCollector — 10 ms sampler daemon (wait ratio, peak memory)
│                      ContextPropagatingRunnable — async context hand-off (@Async, CompletableFuture)
│
└── integration-test/  End-to-end pipeline tests: MetricsStore → CostProjector → all reporters
                       → DashboardServer HTTP → ReportCommand → CloudMeterCli exit codes
```

Architecture decisions, design rationale, and the full system design live in [`arc42.md`](arc42.md).

---

## Contributing

CloudMeter is early-stage and welcomes contributors. Good first areas:

- New cloud provider pricing tables (cost-engine)
- Additional framework support (JAX-RS, Micronaut, Quarkus)
- Dashboard UI improvements
- Language agents (Node.js, Python) following the same wire protocol

Please read [`arc42.md`](arc42.md) before contributing — it explains every architectural decision and why it was made.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

*Built with [Claude Code](https://claude.ai/claude-code).*
