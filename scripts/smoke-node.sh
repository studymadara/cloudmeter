#!/usr/bin/env bash
# smoke-node.sh — end-to-end smoke test for the Node.js CloudMeter client.
#
# What this tests (the full chain your friend uses):
#   Express app with cloudMeter middleware
#     → reporter fires HTTP request to sidecar
#     → sidecar stores metrics
#     → /api/projections returns non-zero costs
#
# Requires: the Rust sidecar binary already built.

set -euo pipefail

INGEST_PORT=17778
DASHBOARD_PORT=17777
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SIDECAR="${REPO_ROOT}/sidecar-rs/target/debug/cloudmeter-sidecar"
SIDECAR_PID=""
APP_PID=""

fail()    { echo "[FAIL] $*" >&2; exit 1; }
ok()      { echo "[ OK ] $*"; }
cleanup() {
  [[ -n "${APP_PID}" ]]     && kill "${APP_PID}" 2>/dev/null || true
  [[ -n "${SIDECAR_PID}" ]] && kill "${SIDECAR_PID}" 2>/dev/null || true
}
trap cleanup EXIT

# ── 1. check prerequisites ────────────────────────────────────────────────────
[[ -x "${SIDECAR}" ]] || fail "Sidecar binary not found at ${SIDECAR}. Run: cd sidecar-rs && cargo build"
node --version > /dev/null 2>&1 || fail "node not found"
[[ -d "${REPO_ROOT}/clients/node/node_modules/express" ]] \
  || fail "express not installed. Run: cd clients/node && npm install"

# ── 2. start sidecar ─────────────────────────────────────────────────────────
echo "--- Starting sidecar ---"
"${SIDECAR}" \
  --ingest-port "${INGEST_PORT}" \
  --dashboard-port "${DASHBOARD_PORT}" \
  --target-users 500 \
  > /tmp/cloudmeter-sidecar-smoke-node.log 2>&1 &
SIDECAR_PID=$!

for i in $(seq 1 10); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${INGEST_PORT}/api/status" 2>/dev/null || echo "000")
  [[ "${STATUS}" == "200" ]] && break
  [[ "${i}" -eq 10 ]] && fail "Sidecar did not start"
  sleep 0.5
done
ok "Sidecar ready"

# ── 3. start Express app ──────────────────────────────────────────────────────
echo "--- Starting Express smoke app ---"
APP_PORT=18080

node - <<JSEOF &
const express  = require('${REPO_ROOT}/clients/node/node_modules/express')
const sidecar  = require('${REPO_ROOT}/clients/node/src/sidecar')
const reporter = require('${REPO_ROOT}/clients/node/src/reporter')
const { cloudMeter } = require('${REPO_ROOT}/clients/node/src/express')

// Sidecar already running — patch start() to no-op
sidecar.start = async () => {}
reporter.setPort(${INGEST_PORT})

const app = express()
app.use(cloudMeter({ ingestPort: ${INGEST_PORT} }))

app.get('/api/users/:userId',  (req, res) => res.json({ id: req.params.userId }))
app.get('/api/products',       (req, res) => res.json([1, 2, 3]))
app.get('/api/reports/pdf',    (req, res) => res.json({ url: 'report.pdf' }))

app.listen(${APP_PORT}, '127.0.0.1', () => {
  process.stderr.write('[smoke] Express app listening on :${APP_PORT}\n')
})
JSEOF
APP_PID=$!

# Wait for app to be ready
for i in $(seq 1 10); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/products" 2>/dev/null || echo "000")
  [[ "${STATUS}" == "200" ]] && break
  [[ "${i}" -eq 10 ]] && fail "Express app did not start"
  sleep 0.5
done
ok "Express app ready on :${APP_PORT}"

# ── 4. make 30 requests ───────────────────────────────────────────────────────
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

# Give reporter time to POST all metrics
sleep 0.5

# ── 5. assert sidecar received metrics ───────────────────────────────────────
TOTAL=$(curl -s "http://127.0.0.1:${INGEST_PORT}/api/status" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['totalMetrics'])")
[[ "${TOTAL}" -ge 30 ]] || fail "Expected >=30 metrics, got ${TOTAL}"
ok "Sidecar received ${TOTAL} metrics"

# ── 6. assert projections ─────────────────────────────────────────────────────
PROJ_JSON=$(curl -s "http://127.0.0.1:${DASHBOARD_PORT}/api/projections")

PROJ_COUNT=$(echo "${PROJ_JSON}" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['projections']))")
[[ "${PROJ_COUNT}" -ge 1 ]] || fail "Expected >=1 projection, got ${PROJ_COUNT}"
ok "Projection count: ${PROJ_COUNT}"

PROJ_TMP=$(mktemp /tmp/cloudmeter-proj-node.XXXXXX.json)
echo "${PROJ_JSON}" > "${PROJ_TMP}"
python3 - "${PROJ_TMP}" <<'EOF'
import sys, json
with open(sys.argv[1]) as f:
    d = json.load(f)
for p in d["projections"]:
    cost  = p["projected_monthly_cost_usd"]
    route = p["route"]
    if cost <= 0:
        print(f"FAIL: {route} has zero projected cost", file=sys.stderr)
        sys.exit(1)
    print(f"[ OK ] {route}: ${cost:.4f}/month")
EOF
rm -f "${PROJ_TMP}"

echo ""
echo "=================================="
echo "  Node.js smoke test PASSED"
echo "=================================="
