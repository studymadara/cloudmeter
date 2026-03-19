# CLI Usage

The CloudMeter CLI fetches projections from a running dashboard and renders them as a terminal report or JSON output. It is designed for CI/CD cost gates.

## Installation

The CLI is bundled in the agent fat JAR. If you're using it standalone, build with:

```bash
./gradlew :agent:shadowJar
```

## `report` command

```
cloudmeter report [options]
```

Fetches projections from the running dashboard and prints a report.

### Options

| Option | Default | Description |
|---|---|---|
| `--host HOST` | `127.0.0.1` | Dashboard host |
| `--port PORT` | `7777` | Dashboard port |
| `--format terminal\|json` | `terminal` | Output format |
| `--provider AWS\|GCP\|AZURE` | `AWS` | Cloud provider |
| `--region REGION` | `us-east-1` | Cloud region |
| `--users N` | `1000` | Target concurrent user count |
| `--rpu N` | `1.0` | Requests per user per second |
| `--budget N` | `0` | Monthly budget threshold in USD |

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Success — no budget thresholds exceeded |
| `1` | One or more endpoints exceed the budget threshold |
| `2` | Usage error or connection failure |

## Terminal report

```bash
cloudmeter report --port 7777 --budget 500
```

Example output:

```
CloudMeter Cost Report  —  AWS / us-east-1  —  1,000 target users
─────────────────────────────────────────────────────────────────────────────────────────────────────
Route                                       Obs RPS   Proj RPS  Monthly ($) Per User ($) Instance
─────────────────────────────────────────────────────────────────────────────────────────────────────
POST /api/export/pdf                            0.08      80.00      842.00       0.8420 m5.large  !!
GET  /api/users/{id}                            0.33     333.33       11.20       0.0112 t3.nano
GET  /api/health                                0.83     833.33        0.42       0.0004 t3.nano
─────────────────────────────────────────────────────────────────────────────────────────────────────
Total projected monthly cost: $853.62
```

`!!` marks endpoints that exceed the configured budget.

## JSON report

```bash
cloudmeter report --format json --port 7777 --budget 500
```

Example output:

```json
{
  "meta": {
    "provider": "AWS",
    "region": "us-east-1",
    "targetUsers": 1000,
    "requestsPerUserPerSecond": 1.0,
    "budgetUsd": 500.0,
    "pricingDate": "2025-01-01"
  },
  "projections": [
    {
      "route": "POST /api/export/pdf",
      "observedRps": 0.08,
      "projectedRps": 80.0,
      "projectedMonthlyCostUsd": 842.0,
      "projectedCostPerUserUsd": 0.842,
      "recommendedInstance": "m5.large",
      "exceedsBudget": true,
      "costCurve": [
        {"users": 100, "monthlyCostUsd": 8.42},
        {"users": 1000, "monthlyCostUsd": 84.2},
        ...
      ]
    }
  ],
  "summary": {
    "totalProjectedMonthlyCostUsd": 853.62,
    "anyExceedsBudget": true
  }
}
```

## CI/CD cost gate

Add a cost gate to your CI pipeline that fails the build if any endpoint breaches the budget:

**GitHub Actions:**

```yaml
- name: Start app with CloudMeter
  run: |
    java -javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=5000,budget=500 \
         -jar target/myapp.jar &
    sleep 10  # wait for app to start

- name: Run integration tests (populates metrics)
  run: ./mvnw verify -Pintegration

- name: CloudMeter cost gate
  run: |
    java -jar cloudmeter-agent.jar report --format json --budget 500 > cost-report.json
    # exits 1 if any endpoint exceeds $500/month

- name: Upload cost report
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: cost-report
    path: cost-report.json
```

**Shell script:**

```bash
#!/bin/bash
set -e

# Run report — exit 1 if budget exceeded
java -jar cloudmeter-agent.jar report \
  --format json \
  --port 7777 \
  --budget 500 \
  --users 10000 \
  > cost-report.json

echo "Cost gate passed"
```

## Budget re-evaluation

When `--budget` is specified on the CLI, it **overrides** the budget the agent was configured with. The `exceedsBudget` flag for each endpoint is re-evaluated against the CLI budget, not the dashboard's configured budget. This lets you run multiple cost gates with different thresholds from the same dashboard instance.

```bash
# Development budget: $1,000/month per endpoint
cloudmeter report --budget 1000 --format json

# Production budget: $200/month per endpoint
cloudmeter report --budget 200 --format json
```
