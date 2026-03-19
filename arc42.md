# CloudMeter — Architecture Documentation (arc42)

> **Living document.** Update this file alongside the codebase. Every significant architectural decision should either reinforce or amend what is written here.

---

## Table of Contents

1. [Introduction and Goals](#1-introduction-and-goals)
2. [Architecture Constraints](#2-architecture-constraints)
3. [System Scope and Context](#3-system-scope-and-context)
4. [Solution Strategy](#4-solution-strategy)
5. [Building Block View](#5-building-block-view)
6. [Runtime View](#6-runtime-view)
7. [Deployment View](#7-deployment-view)
8. [Cross-cutting Concepts](#8-cross-cutting-concepts)
9. [Architecture Decisions](#9-architecture-decisions)
10. [Quality Requirements](#10-quality-requirements)
11. [Risks and Technical Debt](#11-risks-and-technical-debt)
12. [Glossary](#12-glossary)

---

## 1. Introduction and Goals

### Purpose

APM tools (Datadog, New Relic) show per-endpoint performance but have zero concept of cloud cost. Cost tools (Infracost, Kubecost, AWS Cost Explorer) show cloud bills but are completely blind to which part of the application is driving that cost. Nobody connects these two worlds.

CloudMeter fills that gap. It attaches to a running JVM and tells developers exactly what each of their API endpoints costs to run on the cloud.

> **The core insight**: "Your `/api/export/pdf` endpoint is responsible for $340 of your monthly AWS bill." No tool says this today.

### Top Quality Goals

| Priority | Quality Goal | Scenario |
|---|---|---|
| 1 | **Zero instrumentation burden** | Developer adds one JVM flag (`-javaagent`). No code changes, no annotations, no libraries to import. |
| 2 | **Non-intrusiveness** | The agent must not measurably degrade app behavior, response time, or reliability. |
| 3 | **Cost accuracy** | Projections must be within ±20% of an actual cloud bill for a representative workload. |
| 4 | **Actionability** | Output must tell developers *what to fix*, not just what it costs. Cost variance across parameter values is surfaced as a diagnostic signal. |

### Stakeholders

| Stakeholder | Interest |
|---|---|
| Java developers | Understand which endpoints drive cloud cost; optimize before scaling |
| SRE / DevOps | Cost visibility during load testing and capacity planning |
| Engineering managers | Cost forecasting at scale; budget threshold alerts |
| CI/CD pipelines | Automated cost gates via JSON output and non-zero exit codes |
| Open source contributors | Clear architecture to extend with new language agents and cloud providers |

---

## 2. Architecture Constraints

### Technical Constraints

| Constraint | Rationale |
|---|---|
| Must operate as `-javaagent` (bytecode instrumentation) | Zero developer code changes; sees every request automatically |
| Also supports dynamic attach (`agentmain`) | Allows attaching to an already-running JVM without restart |
| Fat JAR with all dependencies shaded | No classpath conflicts with the user's application dependencies |
| **Agent bytecode targets Java 8** (`--release 8`) | Maximum JVM compatibility — supports Spring Boot 1.x through 3.x, Java 8 through 21. Agent implementation code must not use APIs unavailable in Java 8 (no records, no `List.of()`, etc.). Code examples in this document use modern syntax for readability only. |
| No cloud credentials required from user | Reduces friction to zero; static pricing tables are sufficient for v1 |
| Dashboard on a separate port (`:7777`) | Zero interference with user app; mirrors Spring Boot Actuator / Webpack dev server pattern |
| Dashboard is local-only; port `:7777` must not be exposed publicly | No authentication mechanism in v1; security relies on network-level isolation |
| Metric naming must align with OpenTelemetry semantic conventions | Interoperability with existing observability stacks; future export to OTel collectors |
| Build: Gradle | Standard in the Java ecosystem; supports fat JAR shading and multi-module projects |
| Tests: JUnit 5 + Mockito | Standard Java testing stack |
| License: Apache 2.0 | Standard for open source Java tooling; permits commercial use; compatible with enterprise adoption |

### Organisational Constraints

- Fully open source from day one under Apache 2.0
- v1 scope is deliberately narrow: JVM compute cost attribution only, manual recording mode only
- No cloud account access — the tool is entirely passive and local

---

## 3. System Scope and Context

### What CloudMeter Does (v1)

```
┌─────────────────────────────────────────────────────────────┐
│                    Developer's Machine / Server              │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         JVM Process (user's application)              │  │
│  │                                                       │  │
│  │   ┌─────────────┐         ┌────────────────────────┐ │  │
│  │   │  User App   │◄────────│   CloudMeter Agent     │ │  │
│  │   │ (Spring MVC │         │   (bytecode layer)     │ │  │
│  │   │  JAX-RS     │         │                        │ │  │
│  │   │  Servlet)   │         │  - HttpInstrumentation │ │  │
│  │   │             │         │  - AsyncPropagator     │ │  │
│  │   └─────────────┘         │  - ThreadSampler       │ │  │
│  │                           │  - MetricsStore        │ │  │
│  │                           └───────────┬────────────┘ │  │
│  └───────────────────────────────────────┼──────────────┘  │
│                                          │                   │
│                                ┌─────────▼──────────┐       │
│                                │    Cost Engine      │       │
│                                │  AWS / GCP / Azure  │       │
│                                │  (static pricing)   │       │
│                                └─────────┬───────────┘       │
│                                          │                   │
│                     ┌────────────────────┼──────────────┐    │
│                     │                    │              │    │
│              ┌──────▼──────┐   ┌────────▼──────┐  ┌───▼──┐ │
│              │  Dashboard  │   │ Terminal/JSON  │  │ CLI  │ │
│              │   :7777     │   │   Reporter     │  │      │ │
│              └─────────────┘   └───────────────┘  └──────┘ │
└─────────────────────────────────────────────────────────────┘
          ▲                                          ▲
          │ browser (local only)                     │ CI/CD pipeline
```

### In Scope (v1)

- JVM compute cost: CPU core-seconds, memory, thread states (RUNNABLE vs WAITING), response time, egress bytes
- Both synchronous and asynchronous request handling (`@Async`, `CompletableFuture`, `DeferredResult`, `Callable`)
- Cost variance tracking per route: p50, p95, p99 cost distribution to surface data skew and missing index signals
- Supported frameworks: **Spring MVC** (Boot 1.x, 2.x, 3.x — primary), JAX-RS, raw Servlet API
- Cloud providers: **AWS** (EC2 + egress), **GCP** (Compute Engine + egress), **Azure** (VM + egress)
- Recording mode: **manual** only — developer starts/stops recording from dashboard or CLI
- Output: live dashboard (`:7777`), terminal report, JSON report
- Deployment: bare JVM, Docker, Kubernetes (via `JAVA_TOOL_OPTIONS`)
- Attach modes: static (`premain` via `-javaagent`) and dynamic (`agentmain` via `cloudmeter attach <pid>`)

### Out of Scope (v1)

- Downstream service costs: DynamoDB, RDS, SQS, SNS, Lambda, S3 — acknowledged but not modelled
- Infra topology: no Terraform, CloudFormation, or Kubernetes parsing
- Live cloud pricing API calls: no credentials, no network calls to cloud providers
- Non-JVM languages: Node.js, Python, Go — separate agents, separate projects (future)
- Reactive / non-servlet frameworks: Spring WebFlux, Vert.x — future v2
- GraalVM native image: `-javaagent` does not work on native-compiled images; explicitly unsupported
- Automated load generation: v1 records real traffic only; synthetic load is out of scope

### The Honest v1 Promise

> "Start recording. Use your app. We tell you what each endpoint costs in compute — and which ones have suspicious cost variance."

---

## 4. Solution Strategy

| Concern | Decision | Rationale |
|---|---|---|
| Instrumentation mechanism | **Byte Buddy** bytecode interception | Higher-level API than raw ASM; type-safe; less brittle across JVM versions; same approach as OTel Java agent |
| HTTP framework interception | Intercept `javax.servlet.Filter` / `jakarta.servlet.Filter` | Common ancestor for Spring MVC, JAX-RS, and raw Servlet; single interception point captures all frameworks |
| Synchronous request correlation | **`ThreadLocal<RequestContext>`** | Available in any JVM app; no dependency on logging frameworks (avoids MDC coupling) |
| Async request correlation | **`ContextPropagatingRunnable` / `ContextPropagatingCallable`** wrapping submitted tasks | Captures RequestContext at submit time, restores it on worker thread; same pattern as OTel and Spring's `TaskDecorator` |
| Async executor interception | Byte Buddy intercepts `Executor.execute()` and `ExecutorService.submit()` | Wraps tasks transparently; works for Spring `@Async`, `CompletableFuture`, `TaskExecutor` |
| CPU attribution | **`ThreadMXBean.getThreadCpuTime(threadId)`** delta (start→end), accumulated across all threads serving a request; fall back to state sampling if CPU time unsupported | Direct CPU nanosecond measurement — exact, not probabilistic; works for sub-10ms requests that sampling misses entirely |
| Thread wait ratio | **Thread state sampling** at 10ms intervals via `ThreadMXBean.getThreadInfo()` | `getThreadCpuTime()` only counts RUNNABLE time; sampling is still needed to measure what fraction of wall time was spent WAITING/BLOCKED |
| Route normalization | Extract template from `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (Spring MVC); heuristic fallback: numbers→`{id}`, UUID regex→`{uuid}`, long alphanumeric (>12 chars)→`{slug}`; explicit override in `cloudmeter.yaml` | Prevents path variants appearing as distinct endpoints; heuristics cover the common cases beyond simple numeric IDs |
| Cost variance tracking | Track cost distribution (p50, p95, p99, max) per route + record actual path for outlier drill-down | Surfaces data skew and missing index signals: p95 >> p50 means some parameter values are disproportionately expensive |
| Egress measurement | Intercept `HttpServletResponse` output stream byte count; labelled "approximate" in all output | gzip/chunked/streaming responses make exact measurement unreliable; egress is a secondary cost signal in v1 — CPU is primary |
| Cost projection | **Linear scaling model** | Honest, auditable, explainable; avoids ML black box for v1 |
| Cost attribution model | **Standalone cost** per endpoint | What this endpoint would cost if the server handled only this route. Value is relative ranking and order of magnitude, not a summing-to-total budget split. Documented explicitly in output. |
| Pricing data | **Static tables** from public cloud on-demand pricing | Zero credentials; embed update date so users know table age |
| Dashboard | Embedded **Jetty** or `com.sun.net.httpserver`, **vanilla JS** | No framework dependency; lightweight; served from fat JAR |
| Fat JAR isolation | **Gradle Shadow plugin** with package relocation | Shades all dependencies under `io.cloudmeter.shaded.*` prefix to prevent classpath conflicts |

---

## 5. Building Block View

### Level 1 — System Decomposition

```
cloudmeter/
├── agent/                                ← Fat JAR entry point (-javaagent / attach)
│   ├── AgentMain.java                    ← premain() + agentmain(); parses CliArgs, starts
│   │                                        DashboardServer, registers shutdown hook
│   └── instrumentation/
│       ├── HttpInstrumentation           ← Byte Buddy: intercept HttpServlet.service()
│       │                                    (javax + jakarta)
│       ├── HttpServletAdvice             ← @Advice enter/exit: create RequestContext,
│       │                                    commit RequestMetrics on completion
│       ├── AsyncContextPropagator        ← Byte Buddy: wrap Executor/ExecutorService submissions
│       ├── RouteNormalizer               ← extract route template; Spring MVC + heuristic fallback
│       └── ThreadStateCollector          ← background daemon sampler: polls ThreadMXBean at 10ms
│
├── collector/
│   ├── MetricsStore                      ← thread-safe in-memory store; startRecording() / getAll()
│   ├── RequestMetrics.java               ← immutable builder-pattern POJO (Java 8 compatible)
│   ├── RequestContext.java               ← ThreadLocal context: route, timestamps, thread IDs
│   ├── RouteStatsCalculator.java         ← p50/p95/p99 cost distribution per route
│   └── ContextPropagatingRunnable.java   ← preserves RequestContext across async thread hand-offs
│
├── cost-engine/
│   ├── CostProjector.java                ← static project() — 6-step formula; returns
│   │                                        EndpointCostProjection list sorted by monthly cost
│   ├── EndpointCostProjection.java       ← immutable projection result: cost at scale, curve, budget flag
│   ├── ProjectionConfig.java             ← builder: provider, region, targetUsers, rpu, budget
│   ├── PricingCatalog.java               ← EnumMap-based static pricing tables (AWS/GCP/Azure)
│   ├── InstanceType.java                 ← name, provider, vCPU, memoryGiB, hourlyUsd
│   ├── ScalePoint.java                   ← (concurrentUsers, monthlyCostUsd) curve point
│   └── CloudProvider.java                ← enum: AWS, GCP, AZURE
│
├── reporter/
│   ├── TerminalReporter.java             ← fixed-width table to PrintStream; budget rows marked !!
│   ├── JsonReporter.java                 ← zero-dependency JSON serialiser; returns boolean for CI
│   ├── DashboardServer.java              ← com.sun.net.httpserver.HttpServer on 127.0.0.1:7777
│   │                                        GET /  GET /api/projections
│   │                                        POST /api/recording/start|stop
│   └── resources/dashboard.html          ← single-page app: Chart.js cost curves, 5s auto-refresh,
│                                            recording controls, summary cards
│
├── cli/
│   ├── CloudMeterCli.java                ← run(args,out,err,cmd) → int exit code (testable)
│   ├── CloudMeterMain.java               ← main() → System.exit(CloudMeterCli.run(...))
│   ├── ReportCommand.java                ← fetches /api/projections, renders via reporters
│   ├── CliArgs.java                      ← parses key=value agent arg string; toProjectionConfig()
│   └── JsonProjectionParser.java         ← regex-based projections JSON parser (zero dependencies)
│                                            re-evaluates exceedsBudget against CLI budget when set
│
└── integration-test/
    └── PipelineIntegrationTest.java      ← end-to-end: MetricsStore → CostProjector → all reporters
                                             → DashboardServer HTTP → ReportCommand → CloudMeterCli
```

### Key Data Structures

> **Note**: Code shown uses modern Java syntax for readability. Agent implementation uses Java 8 compatible classes (no records, no `List.of()`, etc.).

```java
// Core data per request — Java 8 compatible POJO in implementation
// Shown as record for readability
public record RequestMetrics(
    String routeTemplate,     // "GET /api/users/{id}" — normalised
    String actualPath,        // "/api/users/8823" — for outlier drill-down
    int httpStatusCode,       // 200, 404, 500 — for error filtering
    String httpMethod,        // GET, POST, etc.
    long durationMs,
    double cpuCoreSeconds,    // summed across all threads serving this request
    long peakMemoryBytes,
    long egressBytes,
    double threadWaitRatio,   // 0.0–1.0; fraction of duration in WAITING/BLOCKED
    Instant timestamp
) {}

// Per-route aggregation for dashboard and reporting
public record RouteStats(
    String routeTemplate,
    long requestCount,
    double p50CostUsd,
    double p95CostUsd,
    double p99CostUsd,
    double maxCostUsd,
    double varianceRatio,          // p95 / p50; > 1.5 triggers variance warning
    List<RequestMetrics> outliers  // top 10 most expensive individual calls
) {}

// Cost Engine output
public record CostEstimate(
    String routeTemplate,
    double monthlyUsdAtCurrentLoad,   // standalone cost — see ADR-010
    double costPerUser,
    List<ScalePoint> costCurve        // 12 points from 100 to 1M users
) {}

public record ScalePoint(int concurrentUsers, double monthlyUsd) {}
```

---

## 6. Runtime View

### Scenario A — Static Agent Attach (JVM startup)

```
java -javaagent:cloudmeter-agent.jar -jar myapp.jar

1. JVM loads cloudmeter-agent.jar
2. AgentMain.premain() executes BEFORE main()
3. Byte Buddy installs Servlet Filter interceptor
4. Byte Buddy installs Executor/ExecutorService wrapper interceptor
5. ThreadStateCollector starts background daemon sampler (10ms interval)
6. DashboardServer starts on :7777
7. User app main() executes — app starts normally
8. [Warmup period] First 30 seconds of metrics are flagged; dashboard shows
   "Warmup — JIT/class-loading may inflate cost. Data stabilises after 30s."
```

### Scenario B — Dynamic Attach (already-running JVM)

```
cloudmeter attach <pid>

1. CloudMeterCli calls VirtualMachine.attach(pid) via JVM Attach API
2. Loads cloudmeter-agent.jar into target JVM
3. AgentMain.agentmain() executes in target JVM
4. Byte Buddy uses retransformation to instrument already-loaded classes
5. Steps 3–8 from Scenario A continue as normal
6. No application restart required
```

### Scenario C — Per-Request Flow (synchronous)

```
For each HTTP request:
1. Servlet Filter interceptor fires at request entry
2. RouteNormalizer extracts route template ("GET /api/users/{id}")
3. RequestContext created: {routeTemplate, actualPath, startNanos, threadId}
4. RequestContext stored in ThreadLocal
5. ThreadStateCollector samples this thread at 10ms intervals
   - RUNNABLE samples → contribute to cpuCoreSeconds
   - WAITING/BLOCKED samples → contribute to threadWaitRatio
6. Response returned → Filter interceptor fires again
7. MetricsCollector assembles RequestMetrics from ThreadLocal + samples
8. RequestMetrics pushed to MetricsStore (ring buffer)
9. ThreadLocal cleared (in finally block — guaranteed even on exception)
```

### Scenario D — Per-Request Flow (asynchronous)

```
Spring @Async / CompletableFuture / DeferredResult:

1–4. Same as Scenario C (context created on request thread)
5. Handler submits work to ExecutorService (e.g. @Async method)
6. AsyncContextPropagator interceptor fires at Executor.execute() / submit()
7. Current RequestContext is captured from ThreadLocal
8. Task is wrapped in ContextPropagatingRunnable:
   - Before run(): restore captured RequestContext to worker thread's ThreadLocal
   - After run() / on exception: clear worker thread's ThreadLocal
9. Worker thread executes — ThreadStateCollector samples it as part of same request
10. CPU time from worker thread is attributed to the same RequestContext
11. When original request thread receives result and sends response,
    MetricsCollector assembles RequestMetrics with combined CPU from all threads
12. ThreadLocal cleared on both request thread and worker thread
```

### Scenario E — Manual Recording Session

```
1. Developer opens dashboard at http://localhost:7777
2. Clicks "Start Recording" → POST /api/recording/start
3. MetricsStore sets recording=true, clears buffer, notes start time
4. Developer uses their app normally (or runs their own test suite)
5. Requests are captured per Scenarios C and D above
6. Developer clicks "Stop Recording" → POST /api/recording/stop
7. CostProjector runs over accumulated RequestMetrics
8. Dashboard renders:
   - Cost table: routeTemplate, p50 cost, p95 cost, variance ratio, request count
   - Cost curve chart per endpoint
   - Variance warnings where p95/p50 > 1.5
   - Outlier drill-down: most expensive individual calls with actual paths
9. Optional: GET /api/report?format=json → download JSON report
```

### Scenario F — CI/CD Cost Gate

```bash
# In CI pipeline after integration tests run:
cloudmeter report --format json --budget 500 > cost-report.json
# Exits 1 if any endpoint's p95 projected monthly cost exceeds $500
# cost-report.json included as CI artifact
```

---

## 7. Deployment View

### Bare JVM

Agent configuration is passed as a comma-separated `key=value` string in the `-javaagent` argument (all keys case-insensitive):

```bash
java -javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=10000,rpu=1.0,budget=500,port=7777 \
     -jar myapp.jar
```

| Key | Default | Description |
|---|---|---|
| `provider` | `AWS` | `AWS`, `GCP`, or `AZURE` |
| `region` | `us-east-1` | Cloud region for pricing |
| `targetUsers` | `1000` | Concurrent users to project cost at |
| `rpu` | `1.0` | Requests per user per second |
| `budget` | `0` | Monthly USD budget threshold (0 = disabled) |
| `port` | `7777` | Dashboard port |

### Docker

Use `JAVA_TOOL_OPTIONS` to inject the agent without modifying the Dockerfile or the application image:

```bash
docker run \
  -v /path/to/cloudmeter-agent.jar:/cloudmeter/agent.jar \
  -e JAVA_TOOL_OPTIONS="-javaagent:/cloudmeter/agent.jar=provider=AWS,region=us-east-1,targetUsers=5000" \
  -p 7777:7777 \
  myapp:latest
```

Or in `docker-compose.yml`:

```yaml
services:
  myapp:
    image: myapp:latest
    volumes:
      - ./cloudmeter-agent.jar:/cloudmeter/agent.jar
    environment:
      JAVA_TOOL_OPTIONS: "-javaagent:/cloudmeter/agent.jar=provider=AWS,region=us-east-1,targetUsers=5000"
    ports:
      - "7777:7777"   # dashboard — bind to localhost only in production
```

> **Security note**: Port 7777 must not be exposed to public networks. In Docker, bind to `127.0.0.1:7777:7777` unless you have network-level access controls in place.

### Kubernetes

Use an init container to deliver the agent JAR into a shared volume:

```yaml
initContainers:
  - name: cloudmeter-installer
    image: busybox
    command: ["wget", "-O", "/agent/cloudmeter-agent.jar",
              "https://github.com/cloudmeter/releases/latest/cloudmeter-agent.jar"]
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

> **Note**: In Kubernetes, each pod runs its own agent instance. Dashboard access requires `kubectl port-forward pod/<name> 7777:7777`. Multi-replica cost aggregation is out of scope for v1.

### Dynamic Attach (already-running process)

```bash
# Attach to an already-running JVM without restart
cloudmeter attach <pid>

# Find the PID first if needed
jps -l
# or: ps aux | grep java
```

### Classpath Isolation

All CloudMeter dependencies are shaded under `io.cloudmeter.shaded.*`:

```
io.cloudmeter.shaded.net.bytebuddy.*         ← Byte Buddy
io.cloudmeter.shaded.org.eclipse.jetty.*     ← Jetty (if used)
io.cloudmeter.shaded.com.fasterxml.jackson.* ← JSON
```

---

## 8. Cross-cutting Concepts

### 8.1 Request Context Propagation (Synchronous)

Every HTTP request gets a `RequestContext` stored in a `ThreadLocal`. This is the backbone of per-endpoint attribution.

```java
// Java 8 compatible — no records
public final class RequestContext {
    private final String routeTemplate;  // "GET /api/users/{id}"
    private final String actualPath;     // "/api/users/8823"
    private final long startNanos;
    private final long originThreadId;
    // mutable: accumulated by ThreadStateCollector across all threads
    private final List<Long> cpuNanoSamples = new ArrayList<>();
    private final List<Thread.State> stateSamples = new ArrayList<>();
    private final Set<Long> activeThreadIds = ConcurrentHashMap.newKeySet();
}

public final class RequestContextHolder {
    private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();

    public static void set(RequestContext ctx)  { CONTEXT.set(ctx); }
    public static RequestContext get()          { return CONTEXT.get(); }
    public static void clear()                  { CONTEXT.remove(); }
}
```

**Critical**: `clear()` must be called in a `finally` block on all response paths including exceptions. Thread pool reuse means a leaked context will pollute the next request on the same thread.

### 8.2 Async Context Propagation

When a request dispatches work to another thread, the `RequestContext` must travel with it. Byte Buddy wraps submitted tasks:

```java
// Conceptual — Byte Buddy generates this wrapping at runtime
class ContextPropagatingRunnable implements Runnable {
    private final Runnable delegate;
    private final RequestContext capturedContext; // captured at submit() time

    @Override
    public void run() {
        RequestContext previous = RequestContextHolder.get();
        RequestContextHolder.set(capturedContext); // restore on worker thread
        capturedContext.getActiveThreadIds().add(Thread.currentThread().getId());
        try {
            delegate.run();
        } finally {
            capturedContext.getActiveThreadIds().remove(Thread.currentThread().getId());
            if (previous != null) RequestContextHolder.set(previous);
            else RequestContextHolder.clear();
        }
    }
}
```

**CPU accounting**: All threads registered in `activeThreadIds` are sampled by `ThreadStateCollector` and their CPU time is attributed to the same `RequestContext`. This sums total compute cost across async work — which is what the cloud is billing.

**Covered cases**: Spring `@Async`, `CompletableFuture.supplyAsync()`, `ThreadPoolTaskExecutor`, `ScheduledExecutorService`. Covered via `Executor.execute()` and `ExecutorService.submit()` interception.

**Explicitly not covered (v1)**: Spring WebFlux / Project Reactor (requires a different model; v2 scope).

### 8.3 Route Normalization and Cost Variance

**Problem**: Without normalization, `/api/users/1`, `/api/users/2`, ... each appear as a distinct endpoint. The MetricsStore fills with noise.

**Solution**: Extract the route template at interception time.

- **Spring MVC**: read `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` from the `HttpServletRequest` — Spring already computed the template. Exact.
- **JAX-RS**: read from `UriRoutingContext` or equivalent. Exact.
- **Explicit override**: route map in `cloudmeter.yaml` — always wins over heuristics.
- **Heuristic fallback** (raw Servlet or unrecognised frameworks): apply segment-by-segment rules:
  - Pure numeric: `/api/users/123` → `/api/users/{id}`
  - UUID pattern (`[0-9a-f]{8}-...-[0-9a-f]{12}`): → `{uuid}`
  - Long alphanumeric (> 12 chars, mixed case/digits): → `{slug}`
  - Short alphabetic or known keyword: keep as-is (e.g. `/api/users/active` stays `/api/users/active`)
- **Last resort**: if no rule matches, keep the raw path and log a hint suggesting the user add a route map entry.

**Cost variance — the key insight**: Two calls to the same route can have very different costs. `GET /api/users/8823` might take 380ms (table scan) while `GET /api/users/4` takes 12ms (index hit). Aggregate cost hides this. CloudMeter tracks cost distribution per route:

| Route | p50 cost | p95 cost | Variance ratio | Signal |
|---|---|---|---|---|
| `GET /api/users/{id}` | $0.10/mo | $0.38/mo | 3.8× | ⚠️ Missing index or data skew |
| `GET /api/products` | $0.05/mo | $0.06/mo | 1.2× | ✓ Consistent |

When `varianceRatio > 1.5`, the dashboard and terminal report surface a warning:
> *"Cost variance detected for `GET /api/users/{id}` — p95 is 3.8× median. Likely cause: data skew or unindexed key values. Drill down: `/api/users/8823` (380ms, $0.38), `/api/users/9104` (290ms, $0.29)."*

This turns CloudMeter into a diagnostic tool, not just a cost meter.

### 8.4 CPU Measurement and Thread State Sampling

**Primary: `getThreadCpuTime()` — exact CPU nanoseconds**

CPU cost is measured via `ThreadMXBean.getThreadCpuTime(threadId)`, not sampling. At request entry and exit, the agent snapshots CPU nanoseconds for every thread involved:

```java
// At request start (and when each async thread begins work):
long cpuNanosStart = threadMXBean.getThreadCpuTime(threadId);

// At request end (and when each async thread finishes):
long cpuNanosEnd = threadMXBean.getThreadCpuTime(threadId);
long cpuNanosDelta = cpuNanosEnd - cpuNanosStart;

// Accumulate across all threads serving this request:
requestContext.addCpuNanos(cpuNanosDelta);
```

This is exact — not probabilistic — and works correctly for sub-10ms requests that a 10ms sampler would miss entirely.

**Fallback**: If `threadMXBean.isThreadCpuTimeSupported()` returns false (rare; some JVMs or containers restrict this), fall back to treating RUNNABLE samples as a CPU proxy.

**Secondary: State sampling for thread wait ratio**

`getThreadCpuTime()` only counts RUNNABLE time. It cannot tell you what *fraction of wall time* was spent waiting. For the thread wait ratio signal, a background daemon thread still samples at 10ms intervals:

```java
for (Long threadId : requestContext.getActiveThreadIds()) {
    ThreadInfo info = threadMXBean.getThreadInfo(threadId);
    if (info != null) {
        Thread.State state = info.getThreadState();
        // WAITING, TIMED_WAITING, BLOCKED → contributes to threadWaitRatio
        // RUNNABLE samples used only for fallback CPU estimation
    }
}
```

**Virtual threads (Java 21 + Spring Boot 3.2+)**: Both `getThreadCpuTime()` and `getThreadInfo()` have known limitations with virtual threads. Virtual thread support is explicitly **not supported in v1**. The agent logs a warning if virtual threads are detected and disables CPU attribution for affected requests. See Section 11 (Risks).

### 8.5 Cost Projection Formula

```
Configuration (cloudmeter.yaml):
  requests_per_user_per_second: 0.5   # default: 1.0 if not specified
  target_users: 10000
  provider: aws
  region: us-east-1

For each route r, using p50 RequestMetrics:

1. cpu_core_seconds_per_request  = avg(cpuCoreSeconds for r)
2. memory_gb_seconds_per_request = avg(peakMemoryBytes for r) / 1e9
3. egress_gb_per_request         = avg(egressBytes for r) / 1e9

For each of 12 scale points S in [100, 500, 1k, 5k, 10k, 50k, 100k, 250k, 500k, 750k, 1M]:
4. rps_at_S           = S × requests_per_user_per_second
5. cpu_cores_needed   = cpu_core_seconds_per_request × rps_at_S
6. memory_gb_needed   = memory_gb_seconds_per_request × rps_at_S
7. instance_type      = smallest instance where vcpus >= cpu_cores_needed
                        AND ram_gb >= memory_gb_needed
8. instance_count     = ceil(cpu_cores_needed / instance_type.vcpus)
9. monthly_instance_cost = instance_count × instance_type.hourly_rate × 730
10. monthly_egress_cost  = egress_gb_per_request × rps_at_S × 730 × 3600
                           × egress_rate_per_gb
11. monthly_cost_at_S    = monthly_instance_cost + monthly_egress_cost
```

**Cost attribution model**: This is *standalone cost* — what this endpoint would cost if the server handled only this route. The sum of per-endpoint costs will exceed the total bill because all endpoints share the same compute instances. The value is the *relative ranking* (which endpoint costs most) and *order of magnitude* (is this $5/month or $5,000/month?). This is stated explicitly in all output. See ADR-010.

**Standalone cost UX requirements** (to prevent user confusion):
- Column header: `Monthly Cost (standalone) ⓘ`
- Tooltip: *"Each endpoint is priced as if it ran alone. Use for ranking — not summation."*
- No total/sum row in the cost table — summing standalone costs is meaningless
- Show a relative % bar per endpoint (each endpoint's standalone cost as % of the largest endpoint's cost) so ranking is visually obvious regardless of absolute numbers

**Egress in output**:
- Egress cost shown as a separate supplementary line: `Egress cost (approx.)`
- Not included in the primary cost figure — primary cost is compute only
- Tooltip: *"Egress is measured from response byte count and may undercount compressed or streaming responses."*
- For most API endpoints, egress is < 10% of compute cost; the dashboard makes this proportion visible

### 8.6 Agent Failure Isolation

The agent must not crash or degrade the user's application under any failure condition. Three layers of isolation:

1. **Instrumentation callbacks**: every Byte Buddy advice method wraps its body in `try/catch(Throwable)`. On exception, log a warning internally and return — never rethrow into user code.
2. **Background threads** (`ThreadStateCollector`, `DashboardServer`): all are daemon threads so they cannot prevent JVM shutdown. All are wrapped with uncaught exception handlers that log and restart the thread (max 3 retries, then silently stop).
3. **`premain()` / `agentmain()` failures**: if agent initialization fails entirely, it catches the exception, logs it to stderr prefixed with `[CloudMeter]`, and returns without installing any instrumentation. The user's application starts and runs normally.

```java
// Pattern used in all instrumentation advice
public static void onEnter(...) {
    try {
        // instrumentation logic
    } catch (Throwable t) {
        CloudMeterLogger.warn("Instrumentation error (skipping): " + t.getMessage());
        // never rethrow
    }
}
```

### 8.7 Metric Warmup Period

The first requests to any JVM suffer inflated cost due to JIT compilation and class loading. These samples are not representative of steady-state cost.

**Strategy**: Mark the first 30 seconds of metrics after agent attach as "warmup." These samples are:
- Stored but tagged with `warmup=true`
- Excluded from cost projection by default
- Shown in the dashboard with a visual indicator: *"Warmup period — data may not be representative"*

The warmup duration is configurable: `-Dcloudmeter.warmup.seconds=30` (default 30).

### 8.8 OpenTelemetry Alignment

Metric and span attribute names follow OTel semantic conventions:

| CloudMeter concept | OTel name |
|---|---|
| Route template | `http.route` |
| HTTP method | `http.request.method` |
| Response status | `http.response.status_code` |
| Duration | `http.server.request.duration` |
| Process CPU | `process.cpu.time` |
| Process memory | `process.memory.usage` |

### 8.9 MetricsStore Ring Buffer

Fixed-capacity in-memory ring buffer caps memory usage regardless of runtime duration:

```
capacity = 10,000 RequestMetrics entries (configurable via -Dcloudmeter.store.capacity)
~10,000 × ~300 bytes ≈ 3MB max memory footprint
oldest entries evicted when full
```

Optional periodic flush to `~/.cloudmeter/metrics.ndjson` for persistence across restarts.

### 8.10 Data Privacy

CloudMeter captures only the metadata needed for cost attribution. It never captures:
- Request or response body content
- Path parameter values in aggregate metrics (only stored temporarily in `actualPath` for outlier drill-down; never sent externally)
- Query string values
- Request or response headers (except `Content-Length` for egress estimation)
- Authentication tokens or cookies

All data remains local to the JVM process. No external network calls are made. See ADR-011.

---

## 9. Architecture Decisions

### ADR-001: Byte Buddy over Raw ASM

**Status**: Accepted

**Context**: Bytecode instrumentation requires a library to manipulate JVM class files at runtime.

**Decision**: Use Byte Buddy.

**Rationale**:
- Type-safe fluent API vs raw bytecode manipulation with ASM
- Significantly less brittle across JVM versions
- Same library used by Mockito, OTel Java agent, Hibernate — well-proven at scale
- Supports both transformation (premain) and retransformation (agentmain / dynamic attach)

**Consequences**: Byte Buddy adds ~3MB to the fat JAR; must be shaded.

---

### ADR-002: Java Agent over Maven/npm Library

**Status**: Accepted

**Context**: CloudMeter could be distributed as a library developers import and call explicitly.

**Decision**: Java agent (`-javaagent` + dynamic attach) only for v1.

**Rationale**:
- A library only sees what the developer explicitly instruments — opt-in and inevitably incomplete
- A Java agent intercepts at the bytecode level — sees *every* HTTP request automatically
- Zero developer code changes is a core quality goal
- This is the same model used by Spring Sleuth, OTel Java agent, AppDynamics, YourKit

**Consequences**: Slightly more complex setup than a Maven dependency, but dramatically better data completeness.

---

### ADR-003: Static Pricing Tables over Live Cloud API

**Status**: Accepted

**Context**: Cloud providers have public pricing APIs (AWS Price List API, GCP Cloud Billing Catalog).

**Decision**: Embed static pricing tables in the JAR for v1.

**Rationale**:
- No credentials required — zero friction
- No network dependency — works offline and in air-gapped environments
- Pricing changes are infrequent; ±20% accuracy is achievable with tables updated per release

**Consequences**: Embed a `pricing_table_date` field; add a staleness warning if table is > 90 days old.

---

### ADR-004: In-Process Dashboard over Separate Process

**Status**: Accepted

**Context**: The dashboard could run as a separate process communicating over a socket.

**Decision**: Dashboard runs in-process on `:7777`.

**Rationale**:
- Simpler deployment: one JAR, one command
- Agent already holds all the data — no inter-process serialisation needed
- Mirrors Spring Boot Actuator pattern

**Consequences**: Port `:7777` must be free. Configurable via `-Dcloudmeter.port`.

---

### ADR-005: ThreadLocal for Request Correlation over MDC

**Status**: Accepted

**Context**: Logging frameworks provide MDC as a ThreadLocal-backed context store.

**Decision**: Use our own `RequestContextHolder` with a plain `ThreadLocal`.

**Rationale**:
- MDC couples CloudMeter to a specific logging framework
- We control the lifecycle — no risk of another framework clearing our context

**Consequences**: We must be rigorous about `ThreadLocal.remove()` in all response paths.

---

### ADR-006: Linear Scaling Model over ML-Based Projection

**Status**: Accepted

**Context**: A machine learning model could potentially make more accurate cost projections.

**Decision**: Linear scaling from observed load to target user count.

**Rationale**:
- Honest and auditable — developers can verify the math
- Explainable — no black box
- Accurate enough for ±20% goal at v1

**Consequences**: Projections may be pessimistic for cache-heavy apps. Document in output.

---

### ADR-007: Agent Bytecode Targets Java 8

**Status**: Accepted

**Context**: We want to support the widest possible range of Spring applications.

**Decision**: Agent JAR compiled with `--release 8`. Agent implementation code must not use Java APIs unavailable in Java 8.

**Rationale**:
- Spring Boot 1.x and 2.x require Java 8+; many production apps still run on Java 8 or 11
- The agent should not restrict which JVMs it can instrument
- Byte Buddy itself supports Java 8+

**Consequences**: No `record`, no `List.of()`, no `var`, no text blocks in agent production code. Code examples in this document use modern syntax for readability only.

---

### ADR-008: Async Context Propagation via Executor Interception

**Status**: Accepted

**Context**: Async request handling (`@Async`, `CompletableFuture`) moves work to different threads, breaking `ThreadLocal` correlation.

**Decision**: Intercept `Executor.execute()` and `ExecutorService.submit()` to wrap tasks with `ContextPropagatingRunnable` / `ContextPropagatingCallable`.

**Rationale**:
- Transparent to the application — no API changes
- Covers the most common async patterns in Spring MVC apps
- Same pattern used by OTel Java agent and Spring's `TaskDecorator`
- CPU time from all threads serving a request is summed — this is what the cloud bills

**Consequences**: Adds overhead per async task submission (~microseconds). Does not cover reactive frameworks (WebFlux / Reactor) — explicitly v2 scope.

---

### ADR-009: Standalone Cost Attribution Model

**Status**: Accepted

**Context**: Multiple attribution models exist: standalone cost (what this endpoint costs in isolation) vs. proportional share (each endpoint's slice of the shared bill).

**Decision**: Standalone cost.

**Rationale**:
- Simple and explainable — the formula is transparent
- Proportional attribution requires knowing the total traffic mix at all times, making projections unstable
- The goal is relative ranking and order of magnitude, not exact budget allocation
- Standalone cost is conservative (higher than proportional), which is safe for budget planning

**Consequences**: The sum of all per-endpoint standalone costs will exceed the actual cloud bill. This must be clearly stated in every output format (dashboard, terminal, JSON).

---

### ADR-010: Agent Failure Isolation via Universal Try-Catch

**Status**: Accepted

**Context**: Quality requirement states the agent must not cause zero crashes to the user app.

**Decision**: All instrumentation advice methods, background threads, and initialization code wrap their logic in `try/catch(Throwable)`. Failures are logged internally and silently skipped.

**Rationale**:
- The user's application is the primary concern — CloudMeter is observability tooling
- A crash in cost tracking must never propagate to production traffic
- If the agent fails to initialize, it must fail open (app starts normally, agent is silently disabled)

**Consequences**: Some errors may be silent. Agent provides a `-Dcloudmeter.debug=true` flag that enables verbose error output to stderr.

---

### ADR-011: Data Privacy — Metadata Only

**Status**: Accepted

**Context**: The agent intercepts every HTTP request and response. It could technically capture bodies, headers, and query strings.

**Decision**: CloudMeter captures only: HTTP method, route template, HTTP status code, duration, CPU time, memory, egress byte count. It never captures request/response body, query string values, header values, or path parameter values beyond temporary outlier storage.

**Rationale**:
- Request bodies frequently contain PII (names, emails, financial data)
- Capturing bodies would make CloudMeter non-deployable in regulated environments (GDPR, HIPAA, PCI)
- Cost attribution does not require body content — only metadata
- Trust is foundational for an open source agent that runs inside production JVMs

**Consequences**: CloudMeter cannot detect cost differences caused by payload size variation (only response size via egress). This is a documented limitation.

---

### ADR-012: Dashboard Is Local-Only, No Authentication in v1

**Status**: Accepted

**Context**: The dashboard accepts POST requests (`/api/recording/start`, `/api/recording/stop`) and serves internal cost data.

**Decision**: No authentication in v1. Dashboard binds to all interfaces by default but must not be exposed to public networks. Documentation prominently states this.

**Rationale**:
- Adding auth (even token-based) adds friction for local dev use
- The realistic threat model for a local dev tool is low
- Docker and Kubernetes users are expected to manage port exposure at the infrastructure level

**Consequences**: If `:7777` is accidentally exposed (e.g., misconfigured Kubernetes service), cost data and recording controls are accessible to anyone. Future v2 should add optional token auth. Warning is printed to stdout on startup if `cloudmeter.expose.warning=false` is not set.

---

### ADR-013: CLI Budget Re-evaluation Overrides Server-Computed Flag

**Status**: Accepted

**Context**: The `/api/projections` JSON response includes `exceedsBudget` flags computed by the server using its configured budget. When the CLI is invoked with a different `--budget` value, there is a mismatch.

**Decision**: `JsonProjectionParser.parse()` re-evaluates `exceedsBudget` using `projectedMonthlyCostUsd > config.getBudgetUsd()` when `config.getBudgetUsd() > 0`. When budget is `0` (disabled), the server-computed flag is used.

**Rationale**:
- Allows running multiple cost gates with different thresholds against the same dashboard instance
- The CLI's `--budget` parameter has clearer intent than the server's configured budget for exit-code purposes
- Avoids requiring the server to restart with a new config to use a different budget threshold in CI

**Consequences**: The `exceedsBudget` field in JSON output reflects the CLI-supplied budget, not the agent-configured budget. This is the expected behaviour for CI usage.

---

## 10. Quality Requirements

### Performance (Agent Overhead)

| Metric | Target | How Measured |
|---|---|---|
| Additional CPU overhead | < 1% of total process CPU | Compare CPU% with and without agent under same load |
| Added p99 latency per request | < 10ms | Compare p99 with and without agent |
| Agent memory footprint | < 20MB heap | JVM heap dump analysis |
| Dashboard response time | < 100ms | Browser DevTools network panel |

### Accuracy

| Metric | Target | How Measured |
|---|---|---|
| Cost projection vs actual bill | ±20% for representative workload | Manual comparison against AWS Cost Explorer for test app |
| Thread wait ratio accuracy | ±10 percentage points | Compare against Java Flight Recorder ground truth |
| Cost variance ratio accuracy | Relative ranking correct (p95 > p50) | Validate against actual slow query patterns in test app |

### Usability

| Metric | Target |
|---|---|
| Time to first cost insight | < 5 minutes from zero to seeing cost on dashboard |
| Supported JVM versions | Java 8, 11, 17, 21 |
| Supported Spring Boot versions | 1.x, 2.x, 3.x (servlet-based) |
| Supported frameworks | Spring MVC, JAX-RS, raw Servlet API |
| Deployment models | Bare JVM, Docker, Kubernetes |

### Reliability

| Metric | Target |
|---|---|
| Agent crashes affecting user app | Zero — all agent failures silent or logged only |
| Agent-induced request failures | Zero — instrumentation never throws into user code |
| Dashboard availability | Best-effort; not a production dependency |

---

## 11. Risks and Technical Debt

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Thread state sampling is probabilistic — may miss short transitions | High | Medium | Document as estimation; 10ms interval is industry standard for sampling profilers |
| Virtual threads (Java 21 + Spring Boot 3.2+): `ThreadMXBean` unreliable for virtual threads | High | Medium | Detect virtual thread usage at startup; log warning "Virtual thread support not available in v1 — CPU attribution may be inaccurate"; explicit out-of-scope note |
| Byte Buddy version conflict with OTel Java agent if both are attached | Medium | High | Full package shading under `io.cloudmeter.shaded.*`; document known agent interaction matrix |
| Static pricing tables go stale between releases | Medium | Low | Embed `pricing_table_date`; add staleness warning if table > 90 days old |
| Spring WebFlux / reactive endpoints not covered | High | Medium | Explicitly documented out of scope; reactive support is v2 |
| Egress byte count inaccurate for compressed/chunked responses | Medium | Low | Use `Content-Length` header where available; fallback to output stream counting; document limitation |
| Route normalization fails for raw Servlet apps without a route map | Medium | Medium | Heuristic fallback (replace numeric path segments); prompt user to define routes in `cloudmeter.yaml` |
| Async task wrapping adds unexpected overhead at very high concurrency | Low | Medium | Benchmark at 10k+ RPS; wrapping overhead is ~microseconds per task submission |
| Linear scaling model overestimates cost for cache-heavy apps | Medium | Medium | Document in every output: "Projection assumes no caching benefit at scale." Add cache-awareness flag in v2 |
| Dashboard port `:7777` conflicts with another service | Low | Low | Configurable via `-Dcloudmeter.port`; fail fast with clear error message |
| Warmup period too short for slow JIT compilation (e.g. GraalVM JIT) | Low | Low | Make warmup duration configurable; document default |

---

## 12. Glossary

| Term | Definition |
|---|---|
| **Java agent** | A JAR loaded via the `-javaagent` JVM flag that executes `premain()` before the application's `main()`. Can rewrite class bytecode at load time. |
| **agentmain()** | Entry point for dynamic agent attach. Called when an agent is loaded into an already-running JVM via the JVM Attach API. Contrast with `premain()`. |
| **Byte Buddy** | A Java library for runtime bytecode manipulation. Used to intercept method calls without touching source code. |
| **premain()** | The entry point method of a Java agent loaded at JVM startup. Signature: `public static void premain(String args, Instrumentation inst)`. |
| **Dynamic attach** | Attaching a Java agent to an already-running JVM process using the JVM Attach API (`VirtualMachine.attach(pid)`). No application restart required. |
| **RUNNABLE** | JVM thread state: the thread is actively executing code on a CPU core. Contributes to CPU cost. |
| **WAITING / BLOCKED** | JVM thread state: the thread is idle, waiting on an external call (DB, HTTP, lock). You are paying for compute doing nothing. |
| **Core-seconds** | A unit of CPU time: one CPU core running for one second. The basis for cloud compute cost calculations. |
| **Egress** | Outbound network data from your cloud instance to the internet. Billed per GB on all major cloud providers. |
| **ThreadLocal** | A JVM mechanism to store per-thread data without locking. Used here to track which request a thread is currently serving. |
| **Context propagation** | The act of copying request context from one thread to another during async hand-off. CloudMeter does this by wrapping submitted tasks. |
| **Route normalization** | Converting a specific path like `/api/users/123` into its template `GET /api/users/{id}` so all requests to the same route are grouped. |
| **Cost variance** | The ratio of p95 to p50 cost for a given route. A high ratio (>1.5×) indicates that some requests to the same endpoint are significantly more expensive — a diagnostic signal for missing indexes, data skew, or conditional code paths. |
| **Standalone cost** | The projected monthly cost of an endpoint if the server handled only that route. Sum of all standalone costs exceeds the actual bill because endpoints share compute. |
| **OTel / OpenTelemetry** | An open standard and SDK for observability data (traces, metrics, logs). CloudMeter aligns its metric field names with OTel conventions. |
| **Ring buffer** | A fixed-capacity circular data structure. When full, oldest entries are overwritten. Used in MetricsStore to cap memory usage. |
| **Fat JAR / Uber JAR** | A JAR file containing all dependencies bundled inside. CloudMeter shades dependency packages to prevent classpath conflicts. |
| **Shading** | Renaming dependency packages inside a fat JAR (e.g., `net.bytebuddy` → `io.cloudmeter.shaded.net.bytebuddy`) to prevent version conflicts with the user app. |
| **Cost curve** | A graph with concurrent users on the X axis and estimated monthly cost on the Y axis. Core output visualisation. |
| **Budget threshold** | A user-configurable monthly cost limit. The dashboard marks the point on the cost curve where the app would exceed this budget. |
| **Thread wait ratio** | The fraction of a request's total duration spent in WAITING or BLOCKED state. A ratio of 0.6 means 60% of compute time is idle. |
| **Warmup period** | The first N seconds after agent attach during which metrics are flagged as potentially inflated due to JIT compilation and class loading. Excluded from cost projections by default. |
| **Virtual threads** | Lightweight threads introduced in Java 21 (Project Loom). `ThreadMXBean` has reduced accuracy for virtual threads; not supported by CloudMeter v1. |
| **Servlet Filter** | A Java EE / Jakarta EE interception point wrapping every HTTP request. The lowest-common-denominator hook for Spring MVC, JAX-RS, and raw Servlet apps. |
| **JAVA_TOOL_OPTIONS** | A JVM environment variable that injects JVM flags (including `-javaagent`) without modifying the application launch command. Standard method for Docker/Kubernetes agent injection. |

---

---

## Appendix A — Example JSON Report Output

```json
{
  "cloudmeter_version": "0.1.0",
  "generated_at": "2026-03-19T14:32:00Z",
  "pricing_table_date": "2026-02-01",
  "provider": "aws",
  "region": "us-east-1",
  "recording_duration_seconds": 120,
  "warmup_excluded": true,
  "cost_model": "standalone",
  "cost_model_note": "Each endpoint priced in isolation. Use for ranking, not summation.",
  "target_users": 10000,
  "requests_per_user_per_second": 0.5,
  "endpoints": [
    {
      "route": "POST /api/export/pdf",
      "http_method": "POST",
      "request_count": 47,
      "avg_duration_ms": 842,
      "cpu_core_seconds_per_request": 0.31,
      "peak_memory_bytes": 52428800,
      "thread_wait_ratio": 0.18,
      "egress_bytes_per_request": 204800,
      "cost": {
        "monthly_standalone_usd": 312.40,
        "cost_per_user_usd": 0.031,
        "p50_usd": 298.10,
        "p95_usd": 341.80,
        "p99_usd": 389.20,
        "variance_ratio": 1.15,
        "egress_monthly_usd_approx": 18.40,
        "variance_warning": null
      },
      "cost_curve": [
        { "concurrent_users": 100,    "monthly_usd": 3.12 },
        { "concurrent_users": 1000,   "monthly_usd": 31.24 },
        { "concurrent_users": 10000,  "monthly_usd": 312.40 },
        { "concurrent_users": 100000, "monthly_usd": 3124.00 }
      ]
    },
    {
      "route": "GET /api/users/{id}",
      "http_method": "GET",
      "request_count": 312,
      "avg_duration_ms": 94,
      "cpu_core_seconds_per_request": 0.021,
      "peak_memory_bytes": 2097152,
      "thread_wait_ratio": 0.61,
      "egress_bytes_per_request": 1240,
      "cost": {
        "monthly_standalone_usd": 28.70,
        "cost_per_user_usd": 0.0029,
        "p50_usd": 11.20,
        "p95_usd": 42.80,
        "p99_usd": 68.90,
        "variance_ratio": 3.82,
        "egress_monthly_usd_approx": 0.40,
        "variance_warning": "Cost variance detected — p95 is 3.8× median. Likely cause: data skew or unindexed key values. Outliers: /api/users/8823 (380ms), /api/users/9104 (290ms)."
      },
      "cost_curve": [
        { "concurrent_users": 100,    "monthly_usd": 0.29 },
        { "concurrent_users": 1000,   "monthly_usd": 2.87 },
        { "concurrent_users": 10000,  "monthly_usd": 28.70 },
        { "concurrent_users": 100000, "monthly_usd": 287.00 }
      ]
    }
  ]
}
```

---

## Appendix B — Dashboard Layout (Wireframe)

```
╔══════════════════════════════════════════════════════════════════════════════╗
║  ☁ CloudMeter  v0.1.0          aws / us-east-1          ● Recording  [Stop] ║
╠══════════════════════════════════════════════════════════════════════════════╣
║                                                                              ║
║  ENDPOINT COSTS  (standalone ⓘ)              120s recorded  •  359 requests ║
║  ┌──────────────────────────────┬──────────┬───────┬───────┬──────────────┐ ║
║  │ Endpoint                     │ p50/mo   │ p95   │ Ratio │ Relative     │ ║
║  ├──────────────────────────────┼──────────┼───────┼───────┼──────────────┤ ║
║  │ POST /api/export/pdf         │ $298     │ $342  │ 1.1×  │ ████████████ │ ║
║  │ GET  /api/users/{id}       ⚠ │ $ 11     │ $ 43  │ 3.8×  │ ██           │ ║
║  │ GET  /api/products           │ $  4     │ $  5  │ 1.2×  │ ▌            │ ║
║  └──────────────────────────────┴──────────┴───────┴───────┴──────────────┘ ║
║  ⚠ GET /api/users/{id} — p95 is 3.8× median. Check indexes for key values. ║
║    Outliers: /api/users/8823 (380ms)  /api/users/9104 (290ms)  [show all]   ║
║                                                                              ║
║  COST CURVE — POST /api/export/pdf                                           ║
║                                                                              ║
║  $10k ┤                                                          ╭───        ║
║   $5k ┤                                              ╭──────────╯           ║
║   $1k ┤                              ╭──────────────╯                       ║
║  $500 ┤              ╭──────────────╯                                        ║
║  $100 ┤╭────────────╯                                                        ║
║       └──────────────────────────────────────────────────────────────────── ║
║        100   500   1k    5k   10k   50k  100k  250k  500k  750k  1M  users  ║
║                            ▲ current (312 users)   ─ ─ budget limit ($500)  ║
║                                                                              ║
║  [Export JSON]  [Terminal Report]                                            ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

**Key UX notes**:
- "standalone ⓘ" tooltip: *"Each endpoint priced in isolation. Use for ranking — not summation."*
- ⚠ variance indicator appears when `p95/p50 > 1.5`; click to expand outlier list
- Relative bar width = endpoint's standalone cost as % of the most expensive endpoint
- No total/sum row — summing standalone costs is meaningless
- Egress shown in JSON output only; not a primary column in the dashboard table
- Budget threshold dashed line shows at what user count the selected endpoint exceeds the configured limit

---

*arc42 template © 2023 arc42.org. Content © CloudMeter contributors. Licensed under Apache 2.0.*
