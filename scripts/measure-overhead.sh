#!/usr/bin/env bash
# measure-overhead.sh — measures CloudMeter agent overhead vs. a baseline run.
#
# Usage:
#   ./scripts/measure-overhead.sh [--agent-jar <path>] [--app-jar <path>]
#
# Outputs a table: p50 / p95 / p99 latency for each endpoint, with and without
# the agent, plus the delta.  Exits non-zero if p99 overhead exceeds 10 ms.
#
# Requires: ab (Apache Bench), java, curl

set -euo pipefail

# ── defaults ────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$SCRIPT_DIR/.."
APP_JAR="${APP_JAR:-$ROOT/smoke-test-sb2/build/libs/smoke-test-sb2-app.jar}"
AGENT_JAR="${AGENT_JAR:-$ROOT/agent/build/libs/agent-0.2.0.jar}"
APP_PORT=8099
REQUESTS=500       # ab -n
CONCURRENCY=10     # ab -c
WARMUP_REQS=100    # thrown away before measurement

# ── parse args ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --agent-jar) AGENT_JAR="$2"; shift 2;;
    --app-jar)   APP_JAR="$2";   shift 2;;
    *) echo "Unknown arg: $1"; exit 1;;
  esac
done

# ── helpers ──────────────────────────────────────────────────────────────────
die() { echo "ERROR: $*" >&2; exit 1; }

require() {
  command -v "$1" &>/dev/null || die "'$1' not found on PATH"
}

wait_for_app() {
  local port="$1" deadline=$(( $(date +%s) + 20 ))
  while (( $(date +%s) < deadline )); do
    curl -sf "http://localhost:${port}/api/health" &>/dev/null && return 0
    sleep 0.5
  done
  die "App on :${port} did not start within 20 s"
}

kill_app() {
  local pid="$1"
  kill "$pid" 2>/dev/null || true
  # wait up to 5 s
  local i=0
  while kill -0 "$pid" 2>/dev/null && (( i++ < 10 )); do sleep 0.5; done
}

# Extract a named field from ab output: "fieldname:   value ms" → value
ab_extract() {
  local field="$1" abfile="$2"
  grep -E "^${field}:" "$abfile" | awk '{print $NF}' | tr -d '[ms]'
}

# Run ab against one endpoint, return "p50 p95 p99" in ms
benchmark_endpoint() {
  local url="$1" label="$2"
  local tmpfile csvfile
  tmpfile=$(mktemp /tmp/ab-XXXXXX.txt)
  csvfile=$(mktemp /tmp/ab-XXXXXX.csv)

  # warmup (suppress all output)
  ab -q -n "$WARMUP_REQS" -c "$CONCURRENCY" "$url" &>/dev/null || true

  # measure — write CSV percentile file for precise extraction
  ab -n "$REQUESTS" -c "$CONCURRENCY" -e "$csvfile" "$url" > "$tmpfile" 2>&1

  local p50 p95 p99
  # CSV format: "Percentage served,Time in ms"  e.g.  "50,3.210"
  p50=$(awk -F, '$1==50 {printf "%.1f", $2}' "$csvfile" 2>/dev/null)
  p95=$(awk -F, '$1==95 {printf "%.1f", $2}' "$csvfile" 2>/dev/null)
  p99=$(awk -F, '$1==99 {printf "%.1f", $2}' "$csvfile" 2>/dev/null)

  rm -f "$tmpfile" "$csvfile"
  echo "${p50:-0} ${p95:-0} ${p99:-0}"
}

# ── preflight ────────────────────────────────────────────────────────────────
require java
require ab
require curl

[[ -f "$APP_JAR" ]]   || die "App JAR not found: $APP_JAR"
[[ -f "$AGENT_JAR" ]] || die "Agent JAR not found: $AGENT_JAR"

ENDPOINTS=(
  "http://localhost:${APP_PORT}/api/health"
  "http://localhost:${APP_PORT}/api/users/42"
  "http://localhost:${APP_PORT}/api/async-pool"
)
ENDPOINT_LABELS=("GET /api/health" "GET /api/users/{id}" "GET /api/async-pool")

# ── run baseline (no agent) ───────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  CloudMeter overhead measurement"
echo "  App JAR:   $(basename "$APP_JAR")"
echo "  Agent JAR: $(basename "$AGENT_JAR")"
echo "  Requests per endpoint: $REQUESTS  (concurrency: $CONCURRENCY)"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "[ 1/2 ] Starting app WITHOUT agent …"

java -jar "$APP_JAR" \
     --server.port="$APP_PORT" \
     --logging.level.root=WARN \
     > /tmp/cm-baseline.log 2>&1 &
BASELINE_PID=$!

wait_for_app "$APP_PORT"
echo "        App PID $BASELINE_PID — running"

declare -a BASE_P50 BASE_P95 BASE_P99
for i in "${!ENDPOINTS[@]}"; do
  url="${ENDPOINTS[$i]}"
  label="${ENDPOINT_LABELS[$i]}"
  printf "        Benchmarking %-30s …" "$label"
  read -r p50 p95 p99 <<< "$(benchmark_endpoint "$url" "$label")"
  BASE_P50[$i]="$p50"
  BASE_P95[$i]="$p95"
  BASE_P99[$i]="$p99"
  echo " p50=${p50}ms  p95=${p95}ms  p99=${p99}ms"
done

kill_app "$BASELINE_PID"
sleep 1

# ── run with agent ────────────────────────────────────────────────────────────
echo ""
echo "[ 2/2 ] Starting app WITH agent …"

java -javaagent:"$AGENT_JAR=provider=AWS,region=us-east-1,targetUsers=1000,port=7778" \
     -jar "$APP_JAR" \
     --server.port="$APP_PORT" \
     --logging.level.root=WARN \
     > /tmp/cm-agent.log 2>&1 &
AGENT_PID=$!

wait_for_app "$APP_PORT"
echo "        App PID $AGENT_PID — running"

# Let the agent warm up its own internals for 3 s before measuring
sleep 3

declare -a AGNT_P50 AGNT_P95 AGNT_P99
for i in "${!ENDPOINTS[@]}"; do
  url="${ENDPOINTS[$i]}"
  label="${ENDPOINT_LABELS[$i]}"
  printf "        Benchmarking %-30s …" "$label"
  read -r p50 p95 p99 <<< "$(benchmark_endpoint "$url" "$label")"
  AGNT_P50[$i]="$p50"
  AGNT_P95[$i]="$p95"
  AGNT_P99[$i]="$p99"
  echo " p50=${p50}ms  p95=${p95}ms  p99=${p99}ms"
done

kill_app "$AGENT_PID"

# ── results table ─────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════════════════════"
echo "  OVERHEAD REPORT"
printf "  %-30s  %8s  %8s  %8s  %8s  %8s  %8s  %s\n" \
  "Endpoint" "BL p50" "AG p50" "BL p95" "AG p95" "BL p99" "AG p99" "Δp99"
echo "  ──────────────────────────────────────────────────────────────────────────"

MAX_P99_OVERHEAD=0
THRESHOLD_MS=10
FAILED=0

for i in "${!ENDPOINT_LABELS[@]}"; do
  label="${ENDPOINT_LABELS[$i]}"
  bp50="${BASE_P50[$i]:-0}"
  ap50="${AGNT_P50[$i]:-0}"
  bp95="${BASE_P95[$i]:-0}"
  ap95="${AGNT_P95[$i]:-0}"
  bp99="${BASE_P99[$i]:-0}"
  ap99="${AGNT_P99[$i]:-0}"

  delta_p99=$(echo "$ap99 - $bp99" | bc -l 2>/dev/null || echo "0")
  # compare as floats
  exceeds=$(echo "$delta_p99 > $THRESHOLD_MS" | bc -l 2>/dev/null || echo "0")
  # track max
  is_bigger=$(echo "$delta_p99 > $MAX_P99_OVERHEAD" | bc -l 2>/dev/null || echo "0")
  [[ "$is_bigger" == "1" ]] && MAX_P99_OVERHEAD="$delta_p99"

  flag=""
  if [[ "$exceeds" == "1" ]]; then
    flag="  ⚠ EXCEEDS ${THRESHOLD_MS}ms"
    FAILED=1
  fi

  printf "  %-30s  %8s  %8s  %8s  %8s  %8s  %8s  %+.1fms%s\n" \
    "$label" "${bp50}ms" "${ap50}ms" "${bp95}ms" "${ap95}ms" "${bp99}ms" "${ap99}ms" \
    "$delta_p99" "$flag"
done

echo "  ──────────────────────────────────────────────────────────────────────────"
printf "  Max p99 overhead: %s ms  (threshold: %d ms)\n" \
  "$MAX_P99_OVERHEAD" "$THRESHOLD_MS"
echo "═══════════════════════════════════════════════════════════════════════════════"
echo ""

if (( FAILED )); then
  echo "RESULT: FAIL — p99 overhead exceeds ${THRESHOLD_MS} ms threshold"
  exit 1
else
  echo "RESULT: PASS — p99 overhead is within ${THRESHOLD_MS} ms threshold"
  exit 0
fi
