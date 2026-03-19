# Contributing

CloudMeter is early-stage and welcomes contributors. This page covers the development setup, test suite, and good first areas to contribute.

## Development setup

```bash
git clone https://github.com/studymadara/cloudmeter.git
cd cloudmeter

# Build all modules
./gradlew build

# Run tests with coverage
./gradlew test jacocoTestReport jacocoTestCoverageVerification

# Run integration tests
./gradlew :integration-test:test

# Build the agent fat JAR
./gradlew :agent:shadowJar
# Output: agent/build/libs/agent-0.1.0.jar
```

**Java version:** Build tooling requires Java 17+. The agent module targets Java 8 bytecode (`--release 8`).

**IDE:** IntelliJ IDEA with Gradle import works out of the box. VSCode with the Java Extension Pack also works.

## Test suite

Every module has unit tests with a 99% JaCoCo branch coverage requirement (except `agent` at 80% and `integration-test` at 0%).

```bash
# Run all unit tests
./gradlew test

# Run tests for a specific module
./gradlew :cost-engine:test

# Run integration tests (end-to-end pipeline)
./gradlew :integration-test:test

# Run stress tests (concurrency)
./gradlew :collector:test --tests "*StressTest"

# View coverage report
open collector/build/reports/jacoco/test/html/index.html
```

**Coverage policy:** PRs must not reduce module coverage below the threshold. New public methods need tests. The 99% threshold is strict — check the JaCoCo HTML report for uncovered branches before submitting.

## Code conventions

- **Java 8 in agent module only.** All other modules can use Java 17+ features.
- **No external dependencies in collector, cost-engine, cli.** These modules must stay dependency-free. Only `reporter` may use JDK built-ins beyond standard library.
- **Fail-safe in agent callbacks.** All instrumentation code wraps in `try-catch(Throwable)` — never let CloudMeter crash the user app.
- **Static methods for reporters.** `TerminalReporter`, `JsonReporter` are stateless — all `static` methods, no instances.
- **Immutable value objects.** `RequestMetrics`, `EndpointCostProjection`, `ScalePoint` use builders and have no setters.

## Good first contributions

**Add cloud provider pricing data**

Edit `cost-engine/src/main/java/io/cloudmeter/costengine/PricingCatalog.java` to add:
- New instance types for existing providers
- Updated pricing (check the public on-demand pricing pages for AWS/GCP/Azure)
- Additional regions with correct pricing multipliers

Update `pricingDate` when you refresh pricing.

**Add framework support**

Add a new `@Advice` class in `agent/src/main/java/io/cloudmeter/agent/instrumentation/` targeting a new framework (Micronaut, Quarkus, raw `javax.ws.rs`). Register it in `HttpInstrumentation.install()`. Add unit tests for route normalization.

**Improve the dashboard UI**

Edit `reporter/src/main/resources/io/cloudmeter/reporter/dashboard.html`. The dashboard is vanilla JS with Chart.js. Good areas: variance warnings, a per-endpoint drilldown panel, theming.

**Language agents**

A Node.js or Python sidecar that speaks the same wire protocol (JSON over HTTP to `:7777`) and emits the same `RequestMetrics` fields would extend CloudMeter to non-JVM languages. See the `collector` module for the data model.

## Architecture decisions

Read [`arc42.md`](../../arc42.md) and the [Architecture wiki page](Architecture.md) before making structural changes. Every module boundary, dependency direction, and cross-cutting choice is documented and intentional. If you want to propose a change that touches an ADR, open an issue first to discuss.

## Pull request checklist

- [ ] Tests added for new public API
- [ ] Coverage thresholds still pass (`./gradlew jacocoTestCoverageVerification`)
- [ ] Integration tests still pass (`./gradlew :integration-test:test`)
- [ ] No new external dependencies added to collector, cost-engine, or cli
- [ ] Agent module code compiles with `--release 8` (no `var`, no records, no text blocks)
- [ ] `arc42.md` updated if you changed an ADR or added a new architectural concept
- [ ] PR description explains *why*, not just *what*
