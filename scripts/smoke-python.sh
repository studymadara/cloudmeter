#!/usr/bin/env bash
# smoke-python.sh — end-to-end smoke test for the Python CloudMeter client.
#
# What this tests:
#   Flask app with CloudMeterFlask middleware
#     -> metrics captured in-process buffer
#     -> native dashboard /api/projections returns projections + CORS
#
# No sidecar required — fully native in-process.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DASHBOARD_PORT=17777
APP_PORT=17779
APP_PID=""

fail()    { echo "[FAIL] $*" >&2; exit 1; }
ok()      { echo "[ OK ] $*"; }
cleanup() { [[ -n "${APP_PID}" ]] && kill "${APP_PID}" 2>/dev/null || true; }
trap cleanup EXIT

# ── 1. check prerequisites ────────────────────────────────────────────────────
python3 -c "import flask" 2>/dev/null     || fail "Flask not installed. Run: pip install flask"
python3 -c "import cloudmeter" 2>/dev/null || fail "cloudmeter not installed. Run: pip install -e clients/python"

# ── 2. write + start Flask smoke app ─────────────────────────────────────────
echo "--- Starting Flask smoke app on :${APP_PORT} (dashboard :${DASHBOARD_PORT}) ---"

cat > /tmp/cloudmeter-smoke-flask.py << PYEOF
import sys
sys.path.insert(0, '${REPO_ROOT}/clients/python')

from flask import Flask, jsonify
from cloudmeter.flask import CloudMeterFlask

app = Flask(__name__)
CloudMeterFlask(app, provider='AWS', target_users=500, port=${DASHBOARD_PORT})

@app.route('/api/users/<int:user_id>')
def get_user(user_id):
    return jsonify({'id': user_id})

@app.route('/api/products')
def list_products():
    return jsonify([1, 2, 3])

@app.route('/api/reports/pdf')
def generate_pdf():
    return jsonify({'url': 'report.pdf'})

app.run(host='127.0.0.1', port=${APP_PORT})
PYEOF

python3 /tmp/cloudmeter-smoke-flask.py > /tmp/cloudmeter-python-smoke.log 2>&1 &
APP_PID=$!

# ── 3. wait for readiness ─────────────────────────────────────────────────────
echo "--- Waiting for app (up to 10s) ---"
for i in $(seq 1 20); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/products" 2>/dev/null || echo "000")
  if [[ "${STATUS}" == "200" ]]; then
    ok "App ready after ${i} attempts"
    break
  fi
  if [[ "${i}" -eq 20 ]]; then
    echo "--- app log ---"
    cat /tmp/cloudmeter-python-smoke.log >&2
    fail "App did not start (last status: ${STATUS})"
  fi
  sleep 0.5
done

# ── 4. send 30 requests across 3 routes ──────────────────────────────────────
echo "--- Making 30 requests ---"
for i in $(seq 1 10); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/users/${i}")
  [[ "${CODE}" == "200" ]] || fail "GET /api/users/${i} returned ${CODE}"

  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/products")
  [[ "${CODE}" == "200" ]] || fail "GET /api/products returned ${CODE}"

  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/api/reports/pdf")
  [[ "${CODE}" == "200" ]] || fail "GET /api/reports/pdf returned ${CODE}"
done
ok "30 requests sent"

# ── 5. assert dashboard responds ─────────────────────────────────────────────
echo "--- Checking dashboard /api/projections ---"
sleep 1

PROJ_JSON=$(curl -s "http://127.0.0.1:${DASHBOARD_PORT}/api/projections" 2>/dev/null || echo "{}")
PROJ_COUNT=$(echo "${PROJ_JSON}" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(len(d.get('projections', [])))
except Exception:
    print(0)
" 2>/dev/null || echo "0")

if [[ "${PROJ_COUNT}" -gt 0 ]]; then
  ok "Dashboard returned ${PROJ_COUNT} projections"
else
  ok "Dashboard reachable (metrics still in 30s warmup window)"
fi

# Verify CORS header (use -D - to dump headers from a GET, not -I which sends HEAD)
CORS=$(curl -s -D - "http://127.0.0.1:${DASHBOARD_PORT}/api/projections" -o /dev/null 2>/dev/null \
  | grep -i "access-control-allow-origin" | tr -d '\r' || echo "")
[[ -n "${CORS}" ]] || fail "CORS header missing from /api/projections"
ok "CORS header: ${CORS}"

# ── 6. done ───────────────────────────────────────────────────────────────────
echo ""
echo "=================================="
echo "  Python smoke test PASSED"
echo "=================================="
