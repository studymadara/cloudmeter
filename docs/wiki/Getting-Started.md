# Getting Started

## Prerequisites

- Java 8 or later (Java 17+ recommended)
- A running JVM-based web application (Spring MVC, JAX-RS, or raw Servlet)

## 1. Get the agent JAR

**Option A — Build from source:**

```bash
git clone https://github.com/studymadara/cloudmeter.git
cd cloudmeter
./gradlew :agent:shadowJar
# JAR is at: agent/build/libs/agent-0.1.0.jar
```

**Option B — Download release (when available):**

```bash
wget https://github.com/studymadara/cloudmeter/releases/latest/download/cloudmeter-agent.jar
```

## 2. Add the agent flag

Append `-javaagent:cloudmeter-agent.jar=<args>` to your Java command:

```bash
java -javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=5000,budget=200 \
     -jar myapp.jar
```

That's it — no code changes, no config files required.

## 3. Open the dashboard

Navigate to [http://localhost:7777](http://localhost:7777).

You'll see the live dashboard. Metrics start accumulating immediately as your app handles traffic.

## 4. Record a session

1. Click **Start Recording** — this resets the metrics store and begins accumulating fresh data
2. Exercise your app: run integration tests, click through the UI, replay a traffic capture
3. Click **Stop Recording** — projections are computed and the cost table is populated

Aim for at least **30–60 seconds** of representative traffic per endpoint. The warmup period (first 30s after agent attach) is excluded automatically.

## 5. Read the results

The dashboard shows:

- **Cost per endpoint** — projected monthly USD at your target user count
- **Cost curve** — how cost scales from 100 to 1M concurrent users
- **Budget alerts** — endpoints above your configured budget threshold are highlighted

## Next steps

- [Agent Configuration](Agent-Configuration.md) — tune target users, budget, port, and provider
- [CLI Usage](CLI-Usage.md) — export reports, add cost gates to CI/CD
- [Cost Projection Model](Cost-Projection-Model.md) — understand what is being measured and why

## Spring Boot quick start

```bash
# Build your app
./mvnw package

# Start with CloudMeter attached
java -javaagent:cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=10000,budget=500 \
     -jar target/myapp-1.0.jar

# Dashboard is live at :7777
```

## Docker quick start

```bash
docker run \
  -v $(pwd)/cloudmeter-agent.jar:/agent/cloudmeter-agent.jar \
  -e JAVA_TOOL_OPTIONS="-javaagent:/agent/cloudmeter-agent.jar=provider=AWS,region=us-east-1,targetUsers=5000" \
  -p 8080:8080 \
  -p 7777:7777 \
  myapp:latest
```

> **Security note:** Port 7777 binds to `127.0.0.1` only and has no authentication. Do not expose it publicly — add a reverse proxy with auth if you need remote access.

## Kubernetes quick start

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
    ports:
      - containerPort: 8080
      - containerPort: 7777   # forward only if you want remote dashboard access
    volumeMounts:
      - name: cloudmeter-agent
        mountPath: /agent

volumes:
  - name: cloudmeter-agent
    emptyDir: {}
```
