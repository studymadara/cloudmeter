#!/usr/bin/env bash
# smoke-axum.sh — end-to-end smoke test for the CloudMeter Rust (Axum) client.
#
# What this tests:
#   Axum app with CloudMeterLayer applied via route_layer()
#     -> middleware captures route template (not raw path)
#     -> metrics are stored in the in-process buffer
#     -> /__cloudmeter/metrics returns the captured data
#
# No sidecar required — the client is fully native.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEMO_DIR="${REPO_ROOT}/test-apps/axum"
APP_PORT=18081
DASHBOARD_PORT=17778
APP_PID=""

fail()    { echo "[FAIL] $*" >&2; exit 1; }
ok()      { echo "[ OK ] $*"; }
cleanup() { [[ -n "${APP_PID}" ]] && kill "${APP_PID}" 2>/dev/null || true; }
trap cleanup EXIT

# ── 1. build ──────────────────────────────────────────────────────────────────
echo "--- Building cloudmeter-axum-demo ---"
cd "${DEMO_DIR}"
CARGO="${HOME}/.cargo/bin/cargo"
[[ -x "${CARGO}" ]] || CARGO="cargo"

"${CARGO}" build 2>&1 | grep -E "^(error|warning\[|Compiling|Finished)" || true

BINARY="${DEMO_DIR}/target/debug/cloudmeter-axum-demo"
[[ -x "${BINARY}" ]] || fail "binary not found at ${BINARY}"
ok "Binary built: ${BINARY}"

# ── 2. start demo app ─────────────────────────────────────────────────────────
echo "--- Starting Axum demo on :${APP_PORT} (dashboard :${DASHBOARD_PORT}) ---"
APP_PORT=${APP_PORT} DASHBOARD_PORT=${DASHBOARD_PORT} "${BINARY}" \
  > /tmp/cloudmeter-axum-smoke.log 2>&1 &
APP_PID=$!

# ── 3. wait for readiness ─────────────────────────────────────────────────────
echo "--- Waiting for app to respond (up to 10s) ---"
for i in $(seq 1 20); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/products" 2>/dev/null || echo "000")
  if [[ "${STATUS}" == "200" ]]; then
    ok "App ready after ${i} attempts"
    break
  fi
  if [[ "${i}" -eq 20 ]]; then
    echo "--- app log ---"
    cat /tmp/cloudmeter-axum-smoke.log >&2
    fail "App did not start within 10s (last HTTP status: ${STATUS})"
  fi
  sleep 0.5
done

# ── 4. send 30 requests across 3 routes ──────────────────────────────────────
echo "--- Making 30 requests across 3 routes ---"
for i in $(seq 1 10); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/users/${i}")
  [[ "${CODE}" == "200" ]] || fail "GET /api/users/${i} returned ${CODE}"

  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/products")
  [[ "${CODE}" == "200" ]] || fail "GET /api/products returned ${CODE}"

  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/reports/pdf")
  [[ "${CODE}" == "200" ]] || fail "GET /api/reports/pdf returned ${CODE}"
done
ok "30 requests sent"

# ── 5. assert buffer contents ─────────────────────────────────────────────────
echo "--- Checking /__cloudmeter/metrics ---"
METRICS_JSON=$(curl -s "http://127.0.0.1:${APP_PORT}/__cloudmeter/metrics")

TOTAL=$(echo "${METRICS_JSON}" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
[[ "${TOTAL}" -ge 30 ]] || fail "Expected >=30 metrics in buffer, got ${TOTAL}"
ok "Buffer captured ${TOTAL} metrics"

# Verify route templates (not raw paths)
echo "${METRICS_JSON}" | python3 -c "
import sys, json
metrics = json.load(sys.stdin)
routes  = {m['route'] for m in metrics}
print('Routes captured:', routes)

# route_layer() gives us the matched template, not /api/users/1
if not any('/api/users/:user_id' in r for r in routes):
    print('FAIL: expected route template /api/users/:user_id, got: ' + str(routes), file=sys.stderr)
    sys.exit(1)
print('[ OK ] route template captured: /api/users/:user_id')

for expected in ['/api/products', '/api/reports/pdf']:
    if not any(expected in r for r in routes):
        print('FAIL: route ' + expected + ' not found. Got: ' + str(routes), file=sys.stderr)
        sys.exit(1)
    print('[ OK ] route captured: ' + expected)

for m in metrics:
    if m['status'] != 200:
        print('FAIL: unexpected status ' + str(m['status']) + ' for ' + m['route'], file=sys.stderr)
        sys.exit(1)
    if m['durationMs'] < 0:
        print('FAIL: negative durationMs for ' + m['route'], file=sys.stderr)
        sys.exit(1)
print('[ OK ] all metrics valid (status=200, durationMs>=0)')
"

# ── 6. assert dashboard responds ──────────────────────────────────────────────
echo "--- Checking dashboard /api/projections ---"
sleep 1  # Give dashboard thread a moment to start

PROJ_JSON=$(curl -s "http://127.0.0.1:${DASHBOARD_PORT}/api/projections" 2>/dev/null || echo "{}")
PROJ_COUNT=$(echo "${PROJ_JSON}" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(len(d.get('projections', [])))
except Exception:
    print(0)
" 2>/dev/null || echo "0")

# Warmup window is 30s — projections may be empty if all metrics are warmup
if [[ "${PROJ_COUNT}" -gt 0 ]]; then
  ok "Dashboard returned ${PROJ_COUNT} projections"
else
  ok "Dashboard reachable (all metrics still in 30s warmup window)"
fi

# Verify CORS header
CORS=$(curl -s -I "http://127.0.0.1:${DASHBOARD_PORT}/api/projections" 2>/dev/null | grep -i "access-control-allow-origin" | tr -d '\r' || echo "")
[[ -n "${CORS}" ]] || fail "CORS header missing from /api/projections"
ok "CORS header: ${CORS}"

# ── 7. done ───────────────────────────────────────────────────────────────────
echo ""
echo "==================================="
echo "  Axum smoke test PASSED"
echo "==================================="
