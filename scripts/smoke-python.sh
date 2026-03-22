#!/usr/bin/env bash
# smoke-python.sh — end-to-end smoke test for the Python CloudMeter client.
#
# What this tests (the full chain your friend uses):
#   Flask app with CloudMeterFlask middleware
#     → reporter fires HTTP POST to sidecar
#     → sidecar stores metrics
#     → /api/projections returns non-zero costs
#
# Requires: the Rust sidecar binary already built (run scripts/smoke-rust.sh first,
# or: cd sidecar-rs && cargo build)

set -euo pipefail

INGEST_PORT=17778
DASHBOARD_PORT=17777
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SIDECAR="${REPO_ROOT}/sidecar-rs/target/debug/cloudmeter-sidecar"
PID=""

fail()    { echo "[FAIL] $*" >&2; exit 1; }
ok()      { echo "[ OK ] $*"; }
cleanup() { [[ -n "${PID}" ]] && kill "${PID}" 2>/dev/null || true; }
trap cleanup EXIT

# ── 1. check prerequisites ────────────────────────────────────────────────────
[[ -x "${SIDECAR}" ]] || fail "Sidecar binary not found at ${SIDECAR}. Run: cd sidecar-rs && cargo build"
python3 -c "import flask" 2>/dev/null || fail "Flask not installed. Run: pip install flask"
python3 -c "import cloudmeter" 2>/dev/null || fail "cloudmeter not installed. Run: pip install -e clients/python"

# ── 2. start sidecar ─────────────────────────────────────────────────────────
echo "--- Starting sidecar ---"
"${SIDECAR}" \
  --ingest-port "${INGEST_PORT}" \
  --dashboard-port "${DASHBOARD_PORT}" \
  --target-users 500 \
  > /tmp/cloudmeter-sidecar-smoke.log 2>&1 &
PID=$!

for i in $(seq 1 10); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${INGEST_PORT}/api/status" 2>/dev/null || echo "000")
  [[ "${STATUS}" == "200" ]] && break
  [[ "${i}" -eq 10 ]] && fail "Sidecar did not start"
  sleep 0.5
done
ok "Sidecar ready"

# ── 3. run Flask app + make requests ─────────────────────────────────────────
echo "--- Running Flask smoke app ---"
python3 - <<PYEOF
import sys, time, urllib.request, json
sys.path.insert(0, '${REPO_ROOT}/clients/python')

# Patch sidecar so it doesn't try to download/spawn — we already started it
import cloudmeter._sidecar as _sidecar
_sidecar.start = lambda **kw: None
_sidecar._ingest_port = ${INGEST_PORT}
_sidecar.get_ingest_port = lambda: ${INGEST_PORT}

from flask import Flask, jsonify
from cloudmeter.flask import CloudMeterFlask

app = Flask(__name__)
CloudMeterFlask(app, ingest_port=${INGEST_PORT})

@app.route('/api/users/<int:user_id>')
def get_user(user_id):
    return jsonify({'id': user_id})

@app.route('/api/products')
def list_products():
    return jsonify([1, 2, 3])

@app.route('/api/reports/pdf')
def generate_pdf():
    return jsonify({'url': 'report.pdf'})

client = app.test_client()

# Simulate 30 real requests across 3 routes
for _ in range(10):
    assert client.get('/api/users/1').status_code == 200
    assert client.get('/api/products').status_code == 200
    assert client.get('/api/reports/pdf').status_code == 200

# Give reporter threads time to fire
time.sleep(0.5)

# Assert sidecar received all metrics
with urllib.request.urlopen('http://127.0.0.1:${INGEST_PORT}/api/status') as r:
    status = json.loads(r.read())

total = status['totalMetrics']
if total < 30:
    print(f'FAIL: expected >=30 metrics, got {total}', file=sys.stderr)
    sys.exit(1)
print(f'[ OK ] Flask reporter: {total} metrics received by sidecar')

# Assert projections exist
with urllib.request.urlopen('http://127.0.0.1:${DASHBOARD_PORT}/api/projections') as r:
    proj = json.loads(r.read())

routes = proj['projections']
if len(routes) < 3:
    print(f'FAIL: expected 3 routes in projections, got {len(routes)}', file=sys.stderr)
    sys.exit(1)

for p in routes:
    cost = p['projected_monthly_cost_usd']
    route = p['route']
    if cost <= 0:
        print(f'FAIL: {route} has zero projected cost', file=sys.stderr)
        sys.exit(1)
    print(f'[ OK ] {route}: \${cost:.4f}/month')

# Critical: route templates must be normalised
route_names = [p['route'] for p in routes]
assert any('user_id' in r or '<int:user_id>' in r for r in route_names), \
    f'Route template not normalised: {route_names}'
print('[ OK ] Route templates correctly normalised (not raw paths)')

print('')
print('Flask smoke test PASSED')
PYEOF

ok "Python smoke test complete"
echo ""
echo "=================================="
echo "  Python smoke test PASSED"
echo "=================================="
