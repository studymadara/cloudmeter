# Agent Configuration

Configuration is passed as a comma-separated `key=value` string in the `-javaagent` argument. All keys are case-insensitive.

```bash
java -javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=10000,rpu=0.5,budget=500,port=7777 \
     -jar myapp.jar
```

## All options

| Key | Default | Description |
|---|---|---|
| `provider` | `AWS` | Cloud provider for cost projection. Values: `AWS`, `GCP`, `AZURE` |
| `region` | `us-east-1` | Cloud region. Used to apply regional pricing multipliers. |
| `targetUsers` | `1000` | The concurrent user count to project monthly cost at. |
| `rpu` | `1.0` | Requests per user per second. Multiplied by `targetUsers` to get target RPS. |
| `budget` | `0` | Monthly cost budget in USD. `0` means no threshold. Endpoints exceeding this are flagged. |
| `port` | `7777` | Port for the embedded dashboard HTTP server. |
| `duration` | `60` | Expected recording duration in seconds. Used as denominator in RPS calculation. |

## Provider values

| Value | Provider |
|---|---|
| `AWS` | Amazon Web Services (EC2 + data transfer) |
| `GCP` | Google Cloud Platform (Compute Engine + egress) |
| `AZURE` | Microsoft Azure (Virtual Machines + egress) |

## Region examples

**AWS:** `us-east-1`, `us-west-2`, `eu-west-1`, `ap-southeast-1`

**GCP:** `us-central1`, `us-east1`, `europe-west1`, `asia-east1`

**Azure:** `eastus`, `westus2`, `westeurope`, `southeastasia`

> Pricing tables are static and embedded in the JAR. No cloud credentials are required.

## Examples

**Minimal (AWS defaults):**
```bash
-javaagent:cloudmeter-agent.jar
```

**AWS production-scale with budget gate:**
```bash
-javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=50000,rpu=2.0,budget=1000
```

**GCP with custom port:**
```bash
-javaagent:cloudmeter-agent.jar=provider=GCP,region=us-central1,targetUsers=5000,port=8888
```

**Azure EU with strict budget:**
```bash
-javaagent:cloudmeter-agent.jar=provider=AZURE,region=westeurope,targetUsers=10000,budget=200
```

## Choosing targetUsers

`targetUsers` is the most important parameter — it determines the scale point for the cost projection.

Use the concurrent user count you expect at peak:
- Small SaaS: `1000`
- Mid-size product: `10000`–`50000`
- Large platform: `100000`+

If you're not sure, start with `1000` and use the cost curve chart to see how cost changes as you scale.

## Warmup period

The first 30 seconds after agent attach are excluded from projections. This eliminates JIT compilation noise, class-loading overhead, and connection pool warmup from skewing your cost estimates. Warmup requests are marked in the metrics store but not included in `CostProjector` output.
