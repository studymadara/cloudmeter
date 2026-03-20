# Contributing to CloudMeter

Thanks for your interest. This doc covers how to build, test, and extend CloudMeter locally.

---

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| JDK  | 17 (build toolchain); agent bytecode targets Java 8 (`--release 8`) |
| Gradle | Included via `./gradlew` (Gradle 8.x) |
| Git  | Any recent version |

No IDE is required. IntelliJ IDEA and VS Code with the Java extension both work well.

---

## Build

```bash
# Build all modules, run all tests, check coverage thresholds
./gradlew build

# Build only the agent fat JAR
./gradlew :agent:shadowJar
# Output: agent/build/libs/agent-0.2.0.jar

# Build only the CLI fat JAR
./gradlew :cli:shadowJar
# Output: cli/build/libs/cli-0.2.0.jar
```

---

## Test

```bash
# All tests
./gradlew test

# Single module
./gradlew :agent:test
./gradlew :cli:test

# With coverage report (opens in browser)
./gradlew :agent:jacocoTestReport
open agent/build/reports/jacoco/test/html/index.html
```

Coverage thresholds are enforced by `jacocoTestCoverageVerification`:
- `agent` module: ≥ 90% instruction coverage
- `cli` module: ≥ 99% instruction coverage

---

## Smoke tests

The smoke-test modules are standalone apps, not part of the regular test suite. Run them manually:

```bash
# Build the agent fat JAR first
./gradlew :agent:shadowJar

# Spring Boot 3.x on port 8080
./gradlew :smoke-test:bootRun &
curl http://localhost:8080/api/health

# Spring Boot 2.x on port 8081
./gradlew :smoke-test-sb2:bootRun &

# Raw Servlet (Tomcat 9, no Spring) on port 8082
./gradlew :smoke-test-servlet:shadowJar
java -javaagent:agent/build/libs/agent-0.2.0.jar=provider=AWS,targetUsers=1000 \
     -jar smoke-test-servlet/build/libs/smoke-test-servlet-app.jar &

# JAX-RS (Jersey 2.x + Tomcat 9) on port 8083
./gradlew :smoke-test-jaxrs:shadowJar
java -javaagent:agent/build/libs/agent-0.2.0.jar=provider=GCP,targetUsers=500 \
     -jar smoke-test-jaxrs/build/libs/smoke-test-jaxrs-app.jar &
```

Wait 30 seconds (warmup period), then send traffic:

```bash
for i in $(seq 1 20); do
  curl -s http://localhost:8080/api/users/$i > /dev/null
  curl -s -X POST http://localhost:8080/api/process > /dev/null
done
```

The dashboard is at `http://127.0.0.1:7777`. The terminal cost report prints on JVM exit (`Ctrl-C`).

---

## Project structure

```
cloudmeter/
├── agent/          ← Fat JAR; premain() + agentmain() + all Byte Buddy instrumentation
├── collector/      ← RequestContext, RequestContextHolder, MetricsStore
├── cost-engine/    ← CostProjector, PricingCatalog, ProjectionConfig
├── reporter/       ← TerminalReporter, JsonReporter, DashboardServer
├── cli/            ← CloudMeterCli, AttachCommand, CliArgs, CloudMeterConfig
├── smoke-test/     ← Spring Boot 3.x demo app (port 8080)
├── smoke-test-sb2/ ← Spring Boot 2.x demo app (port 8081)
├── smoke-test-servlet/ ← Raw Servlet demo app (port 8082)
└── smoke-test-jaxrs/   ← JAX-RS (Jersey 2.x) demo app (port 8083)
```

---

## Adding new framework instrumentation

1. Create `MyFrameworkInstrumentation.java` in `agent/src/main/java/io/cloudmeter/agent/`.
2. Follow the pattern of `HttpInstrumentation` (Byte Buddy `AgentBuilder` + `@Advice`).
3. Call `MyFrameworkInstrumentation.install(inst)` from `AgentMain.doInitialize()`.
4. Add a smoke-test module if the framework requires a separate app setup.
5. Write unit tests targeting ≥ 90% coverage. Tests that require a real `Instrumentation` should use Mockito mocks and wrap Byte Buddy calls in `try/catch` (Byte Buddy rejects mock `Instrumentation` for retransformation — this is expected and tested).

---

## Adding a new cloud provider

1. Add a constant to `CloudProvider` enum in the `cost-engine` module.
2. Add price entries to `PricingCatalog` (instance types, compute rates, egress rates).
3. Add regional multipliers if applicable.
4. Update `PRICING_DATE` in `PricingCatalog` to today's date.
5. Update the README supported providers table.
6. Run the smoke tests against the new provider and verify projections look reasonable.

---

## Code conventions

- Java 8 syntax only inside `agent/` (the `--release 8` flag enforces this at compile time).
- Other modules may use Java 17 features freely.
- No external logging frameworks — use `CloudMeterLogger` (which writes to `System.err`).
- Agent callbacks must never throw — wrap with `try/catch (Throwable)` (ADR-010).
- Static pricing tables only — no HTTP calls to cloud APIs (ADR-003).
- All new public methods need a test. Coverage gates will fail the build otherwise.

---

## Submitting a pull request

1. Fork, create a branch (`feat/my-feature` or `fix/my-bug`).
2. Run `./gradlew build` — must pass cleanly.
3. Open a PR against `main` with a clear description of what changed and why.
4. Link any relevant issue.

---

## Reporting issues

File a GitHub issue with:
- JVM version (`java -version`)
- Framework and version
- Agent args used
- The full `System.err` output from the agent (set `CLOUDMETER_DEBUG=true` for verbose output)
