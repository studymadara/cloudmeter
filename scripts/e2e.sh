#!/usr/bin/env bash
# scripts/e2e.sh — End-to-end smoke test
#
# Starts the Spring Boot 3 sample app with the CloudMeter agent attached,
# exercises every endpoint, then asserts the dashboard API returns real cost
# projections. Exits 1 on any failure.
#
# Usage:
#   ./scripts/e2e.sh
#
# Requires: Java 17+, the fat JAR and the smoke app JAR must be pre-built:
#   ./gradlew :agent:shadowJar :test-apps:spring3:bootJar

set -euo pipefail

AGENT_JAR=$(ls agent/build/libs/agent-*.jar 2>/dev/null | head -1)
SMOKE_JAR="test-apps/spring3/build/libs/smoke-app.jar"
APP_PORT=8080
DASHBOARD_PORT=7777
STARTUP_TIMEOUT=30   # seconds to wait for the app to come up
APP_PID=""

# ── Cleanup ────────────────────────────────────────────────────────────────────
cleanup() {
    if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
        echo "[e2e] Stopping app (PID $APP_PID)"
        kill "$APP_PID"
        wait "$APP_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ── Pre-flight ─────────────────────────────────────────────────────────────────
if [[ -z "$AGENT_JAR" ]]; then
    echo "[e2e] ERROR: agent JAR not found. Run: ./gradlew :agent:shadowJar" >&2
    exit 1
fi
if [[ ! -f "$SMOKE_JAR" ]]; then
    echo "[e2e] ERROR: smoke app JAR not found. Run: ./gradlew :test-apps:spring3:bootJar" >&2
    exit 1
fi

echo "[e2e] Agent JAR : $AGENT_JAR"
echo "[e2e] Smoke app : $SMOKE_JAR"

# ── Start app with agent ───────────────────────────────────────────────────────
java \
    -javaagent:"$AGENT_JAR"=provider=AWS,region=us-east-1,targetUsers=1000,budget=50,port=$DASHBOARD_PORT \
    -jar "$SMOKE_JAR" \
    --server.port=$APP_PORT \
    > /tmp/cloudmeter-e2e-app.log 2>&1 &
APP_PID=$!
echo "[e2e] App started (PID $APP_PID)"

# ── Wait for startup ───────────────────────────────────────────────────────────
echo -n "[e2e] Waiting for app on :$APP_PORT "
for i in $(seq 1 $STARTUP_TIMEOUT); do
    if curl -sf "http://localhost:$APP_PORT/api/health" -o /dev/null 2>/dev/null; then
        echo " ready (${i}s)"
        break
    fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo ""
        echo "[e2e] ERROR: app process died. Logs:" >&2
        cat /tmp/cloudmeter-e2e-app.log >&2
        exit 1
    fi
    echo -n "."
    sleep 1
done

if ! curl -sf "http://localhost:$APP_PORT/api/health" -o /dev/null 2>/dev/null; then
    echo "[e2e] ERROR: app did not start within ${STARTUP_TIMEOUT}s" >&2
    cat /tmp/cloudmeter-e2e-app.log >&2
    exit 1
fi

# ── Exercise endpoints ─────────────────────────────────────────────────────────
echo "[e2e] Warming up endpoints (30 requests per route)..."

for i in $(seq 1 30); do
    curl -sf "http://localhost:$APP_PORT/api/health"        -o /dev/null
    curl -sf "http://localhost:$APP_PORT/api/users/$i"      -o /dev/null
    curl -sf "http://localhost:$APP_PORT/api/process" \
         -X POST -H "Content-Type: application/json" \
         -d '{"input":"e2e"}' -o /dev/null
done

echo "[e2e] Waiting 3s for metrics to settle..."
sleep 3

# ── Assert dashboard API ───────────────────────────────────────────────────────
echo "[e2e] Querying dashboard at :$DASHBOARD_PORT ..."
PROJECTIONS=$(curl -sf "http://localhost:$DASHBOARD_PORT/api/projections")

if [[ -z "$PROJECTIONS" ]]; then
    echo "[e2e] ERROR: dashboard returned empty response" >&2
    exit 1
fi

echo "[e2e] Response: $PROJECTIONS" | head -c 300

# Must contain at least 2 routes
ROUTE_COUNT=$(echo "$PROJECTIONS" | grep -o '"route"' | wc -l | tr -d ' ')
if [[ "$ROUTE_COUNT" -lt 2 ]]; then
    echo ""
    echo "[e2e] ERROR: expected ≥2 routes in projections, got $ROUTE_COUNT" >&2
    echo "Full response:" >&2
    echo "$PROJECTIONS" >&2
    exit 1
fi

# Must contain a positive monthly cost
if echo "$PROJECTIONS" | grep -q '"monthlyCostUsd":0'; then
    echo ""
    echo "[e2e] ERROR: all endpoints show \$0 cost — agent instrumentation may not be working" >&2
    exit 1
fi

echo ""
echo "[e2e] PASS — $ROUTE_COUNT routes with non-zero costs reported"
