#!/usr/bin/env bash
# smoke-rust.sh — end-to-end smoke test for the CloudMeter Rust sidecar.
#
# Builds the debug binary, starts it on non-standard ports to avoid conflicts,
# POSTs a batch of metrics, then asserts that /api/projections returns at least
# one route with a non-zero projected cost.
#
# Exit codes:
#   0 — all assertions passed
#   1 — build, start-up, or assertion failure

set -euo pipefail

INGEST_PORT=17778
DASHBOARD_PORT=17777
INGEST_BASE="http://127.0.0.1:${INGEST_PORT}"
DASHBOARD_BASE="http://127.0.0.1:${DASHBOARD_PORT}"
TIMEOUT_SECS=10
BINARY="./target/debug/cloudmeter-sidecar"
PID=""

# ── cleanup ──────────────────────────────────────────────────────────────────
cleanup() {
  if [[ -n "${PID}" ]]; then
    kill "${PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ── helpers ──────────────────────────────────────────────────────────────────
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok()   { echo "[ OK ] $*"; }

# ── 1. build ─────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIDECAR_DIR="$(cd "${SCRIPT_DIR}/../sidecar-rs" && pwd)"
cd "${SIDECAR_DIR}"

echo "--- Building cloudmeter-sidecar (debug) ---"
cargo build 2>&1 | grep -E "^(error|warning\[|Compiling|Finished)" || true

[[ -x "${BINARY}" ]] || fail "binary not found at ${BINARY}"
ok "Binary built: ${BINARY}"

# ── 2. start sidecar ─────────────────────────────────────────────────────────
echo "--- Starting sidecar on ingest=:${INGEST_PORT} dashboard=:${DASHBOARD_PORT} ---"
"${BINARY}" \
  --ingest-port "${INGEST_PORT}" \
  --dashboard-port "${DASHBOARD_PORT}" \
  --target-users 500 \
  --provider AWS \
  --region us-east-1 \
  > /tmp/cloudmeter-smoke.log 2>&1 &
PID=$!

# ── 3. wait for readiness ─────────────────────────────────────────────────────
echo "--- Waiting for /api/status to respond (up to ${TIMEOUT_SECS}s) ---"
for i in $(seq 1 "${TIMEOUT_SECS}"); do
  STATUS_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${INGEST_BASE}/api/status" 2>/dev/null || echo "000")
  if [[ "${STATUS_CODE}" == "200" ]]; then
    ok "/api/status is up after ${i}s"
    break
  fi
  if [[ "${i}" -eq "${TIMEOUT_SECS}" ]]; then
    echo "--- sidecar log ---"
    cat /tmp/cloudmeter-smoke.log >&2
    fail "sidecar did not start within ${TIMEOUT_SECS}s (last HTTP status: ${STATUS_CODE})"
  fi
  sleep 1
done

# Verify recording is active
RECORDING=$(curl -s "${INGEST_BASE}/api/status" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['recording'])" 2>/dev/null || echo "False")
[[ "${RECORDING}" == "True" ]] || fail "/api/status.recording is not true"
ok "Recording is active"

# ── 4. POST metrics ───────────────────────────────────────────────────────────
echo "--- POSTing 30 metrics (3 routes × 10 each) ---"
post_metric() {
  local route="$1" method="$2" duration="$3" egress="$4"
  curl -s -X POST "${INGEST_BASE}/api/metrics" \
    -H "Content-Type: application/json" \
    -d "{\"route\":\"${route}\",\"method\":\"${method}\",\"status\":200,\"durationMs\":${duration},\"egressBytes\":${egress}}" \
    -o /dev/null -w "%{http_code}"
}

for i in $(seq 1 10); do
  CODE=$(post_metric "GET /api/users"       "GET"  50   512)
  [[ "${CODE}" == "202" ]] || fail "POST /api/metrics returned ${CODE} on iteration ${i}"

  CODE=$(post_metric "POST /api/orders"     "POST" 80   2048)
  [[ "${CODE}" == "202" ]] || fail "POST /api/metrics returned ${CODE} on iteration ${i}"

  CODE=$(post_metric "GET /api/export/pdf"  "GET"  350  102400)
  [[ "${CODE}" == "202" ]] || fail "POST /api/metrics returned ${CODE} on iteration ${i}"
done
ok "30 metrics accepted"

# Verify store count
TOTAL=$(curl -s "${INGEST_BASE}/api/status" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['totalMetrics'])" 2>/dev/null || echo "0")
[[ "${TOTAL}" -eq 30 ]] || fail "Expected totalMetrics=30, got ${TOTAL}"
ok "Store count: ${TOTAL}"

# ── 5. assert projections ─────────────────────────────────────────────────────
echo "--- Checking /api/projections ---"
PROJ_JSON=$(curl -s "${DASHBOARD_BASE}/api/projections")

PROJ_COUNT=$(echo "${PROJ_JSON}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d['projections']))" 2>/dev/null || echo "0")
[[ "${PROJ_COUNT}" -ge 1 ]] || fail "Expected ≥1 projection, got ${PROJ_COUNT}"
ok "Projection count: ${PROJ_COUNT}"

# All routes must have non-zero projected cost
PROJ_TMP=$(mktemp /tmp/cloudmeter-proj.XXXXXX.json)
echo "${PROJ_JSON}" > "${PROJ_TMP}"
python3 - "${PROJ_TMP}" <<'EOF'
import sys, json
with open(sys.argv[1]) as f:
    d = json.load(f)
for p in d["projections"]:
    cost = p["projected_monthly_cost_usd"]
    route = p["route"]
    if cost <= 0:
        print(f"FAIL: {route} has zero projected cost", file=sys.stderr)
        sys.exit(1)
    print(f"[ OK ] {route}: ${cost:.4f}/month projected")
EOF
rm -f "${PROJ_TMP}"

TOTAL_COST=$(echo "${PROJ_JSON}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['summary']['totalProjectedMonthlyCostUsd'])" 2>/dev/null || echo "0")
ok "Total projected monthly cost: \$${TOTAL_COST}"

# Assert CORS header on dashboard
CORS=$(curl -s -I "${DASHBOARD_BASE}/api/projections" | grep -i "access-control-allow-origin" | tr -d '\r')
[[ -n "${CORS}" ]] || fail "CORS header missing from /api/projections"
ok "CORS header present: ${CORS}"

# ── 6. done ───────────────────────────────────────────────────────────────────
echo ""
echo "==================================="
echo "  Smoke test PASSED — all checks OK"
echo "==================================="
