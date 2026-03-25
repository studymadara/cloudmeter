#!/usr/bin/env bash
# smoke-node.sh — end-to-end smoke test for the Node.js CloudMeter client.
#
# What this tests:
#   Express app with cloudMeter middleware
#     -> middleware intercepts requests
#     -> metrics are stored in the in-process buffer
#     -> buffer contains the correct route/method/status entries
#
# No sidecar required — the client is now fully native.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_PID=""

fail()    { echo "[FAIL] $*" >&2; exit 1; }
ok()      { echo "[ OK ] $*"; }
cleanup() { [[ -n "${APP_PID}" ]] && kill "${APP_PID}" 2>/dev/null || true; }
trap cleanup EXIT

# ── 1. check prerequisites ────────────────────────────────────────────────────
node --version > /dev/null 2>&1 || fail "node not found"
[[ -d "${REPO_ROOT}/clients/node/node_modules/express" ]] \
  || fail "express not installed. Run: cd clients/node && npm install"

# ── 2. start Express app ──────────────────────────────────────────────────────
echo "--- Starting Express smoke app ---"
APP_PORT=18080

node -e "
const express  = require('${REPO_ROOT}/clients/node/node_modules/express');
const reporter = require('${REPO_ROOT}/clients/node/src/reporter');
const { cloudMeter } = require('${REPO_ROOT}/clients/node/src/express');
const app = express();
app.use(cloudMeter());
app.get('/api/users/:userId',  (req, res) => res.json({ id: req.params.userId }));
app.get('/api/products',       (req, res) => res.json([1, 2, 3]));
app.get('/api/reports/pdf',    (req, res) => res.json({ url: 'report.pdf' }));
app.get('/__cloudmeter/metrics', (req, res) => res.json(reporter.getMetrics()));
app.listen(${APP_PORT}, '127.0.0.1');
" &
APP_PID=$!

for i in $(seq 1 10); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/products" 2>/dev/null || echo "000")
  [[ "${STATUS}" == "200" ]] && break
  [[ "${i}" -eq 10 ]] && fail "Express app did not start"
  sleep 0.5
done
ok "Express app ready on :${APP_PORT}"

# ── 3. make 30 requests ───────────────────────────────────────────────────────
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

# ── 4. assert buffer captured metrics ────────────────────────────────────────
METRICS_JSON=$(curl -s "http://127.0.0.1:${APP_PORT}/__cloudmeter/metrics")

TOTAL=$(echo "${METRICS_JSON}" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
[[ "${TOTAL}" -ge 30 ]] || fail "Expected >=30 metrics in buffer, got ${TOTAL}"
ok "Buffer captured ${TOTAL} metrics"

echo "${METRICS_JSON}" | python3 -c "
import sys, json
metrics = json.load(sys.stdin)
routes  = {m['route'] for m in metrics}
for expected in ['/api/users/:userId', '/api/products', '/api/reports/pdf']:
    if not any(expected in r for r in routes):
        print('FAIL: route ' + expected + ' not found in buffer. Got: ' + str(routes))
        sys.exit(1)
    print('[ OK ] route captured: ' + expected)
for m in metrics:
    if m['status'] != 200:
        print('FAIL: unexpected status ' + str(m['status']) + ' for ' + m['route'])
        sys.exit(1)
    if m['durationMs'] < 0:
        print('FAIL: negative durationMs for ' + m['route'])
        sys.exit(1)
print('[ OK ] all metrics have status=200 and non-negative durationMs')
"

echo ""
echo "=================================="
echo "  Node.js smoke test PASSED"
echo "=================================="
