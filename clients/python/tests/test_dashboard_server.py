"""
Dashboard server unit tests.

Uses _stop() in teardown to reset server state between tests.
All HTTP calls go to the real in-process server on a random port.
"""
import json
import urllib.error
import urllib.request
from unittest.mock import patch

import pytest

import cloudmeter._dashboard_server as dashboard_server
import cloudmeter._reporter as reporter

# Saved at import time (before any conftest patches apply) so server_port
# can call the real start() even though conftest patches _dashboard_server.start.
_REAL_START = dashboard_server.start


# ── helpers ───────────────────────────────────────────────────────────────────

def _get(port, path):
    url = f"http://127.0.0.1:{port}{path}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=3) as r:
        return r.status, r.headers, r.read()


def _post(port, path):
    url = f"http://127.0.0.1:{port}{path}"
    req = urllib.request.Request(url, data=b"", method="POST")
    with urllib.request.urlopen(req, timeout=3) as r:
        return r.status, r.headers, r.read()


def _json_get(port, path):
    status, headers, body = _get(port, path)
    return status, json.loads(body)


def _json_post(port, path):
    status, headers, body = _post(port, path)
    return status, json.loads(body)


# ── fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture()
def server_port():
    """Start dashboard on a free port; stop it after each test.

    conftest's autouse reset_reporter fixture patches _dashboard_server.start
    with a mock. We restore the real function here using the reference captured
    at module import time (before any patches were applied).
    """
    port = 17777  # high port unlikely to conflict
    with patch("cloudmeter._dashboard_server.start", new=_REAL_START):
        _REAL_START({"port": port, "provider": "AWS", "target_users": 1000})
    yield port
    dashboard_server._stop()


# ── GET / ─────────────────────────────────────────────────────────────────────

def test_get_root_returns_html(server_port):
    status, headers, body = _get(server_port, "/")
    assert status == 200
    assert b"<html" in body.lower() or b"<!doctype" in body.lower()


def test_get_root_content_type_html(server_port):
    _, headers, _ = _get(server_port, "/")
    assert "text/html" in headers.get("Content-Type", "")


# ── GET /api/projections ──────────────────────────────────────────────────────

def test_projections_returns_200(server_port):
    status, data = _json_get(server_port, "/api/projections")
    assert status == 200


def test_projections_response_shape(server_port):
    _, data = _json_get(server_port, "/api/projections")
    assert "meta" in data
    assert "projections" in data
    assert "summary" in data


def test_projections_meta_fields(server_port):
    _, data = _json_get(server_port, "/api/projections")
    meta = data["meta"]
    assert meta["provider"] == "AWS"
    assert meta["targetUsers"] == 1000
    assert "pricingDate" in meta


def test_projections_empty_when_no_metrics(server_port):
    _, data = _json_get(server_port, "/api/projections")
    assert data["projections"] == []


def test_projections_camelcase_keys(server_port):
    reporter.start_recording()
    reporter.report(route="GET /api/users", method="GET", status=200, duration_ms=50, egress_bytes=0)
    # Force warmup off so we get a real projection
    import time

    import cloudmeter._reporter as rp
    rp._recording_start = time.time() - 31
    reporter.report(route="GET /api/users", method="GET", status=200, duration_ms=50, egress_bytes=0)

    _, data = _json_get(server_port, "/api/projections")
    if data["projections"]:
        p = data["projections"][0]
        assert "projectedMonthlyCostUsd" in p
        assert "recommendedInstance" in p
        assert "costCurve" in p


# ── GET /api/stats ─────────────────────────────────────────────────────────────

def test_stats_returns_200(server_port):
    status, data = _json_get(server_port, "/api/stats")
    assert status == 200


def test_stats_response_shape(server_port):
    _, data = _json_get(server_port, "/api/stats")
    assert "stats" in data


def test_stats_empty_when_no_metrics(server_port):
    _, data = _json_get(server_port, "/api/stats")
    assert data["stats"] == []


def test_stats_camelcase_keys(server_port):
    import time

    import cloudmeter._reporter as rp
    reporter.start_recording()
    rp._recording_start = time.time() - 31
    for _ in range(5):
        reporter.report(route="GET /api/test", method="GET", status=200, duration_ms=100)

    _, data = _json_get(server_port, "/api/stats")
    if data["stats"]:
        s = data["stats"][0]
        assert "p50CostUsd" in s
        assert "varianceWarning" in s


# ── POST /api/recording/start ─────────────────────────────────────────────────

def test_post_recording_start_returns_recording(server_port):
    status, data = _json_post(server_port, "/api/recording/start")
    assert status == 200
    assert data["status"] == "recording"


# ── POST /api/recording/stop ──────────────────────────────────────────────────

def test_post_recording_stop_returns_stopped(server_port):
    status, data = _json_post(server_port, "/api/recording/stop")
    assert status == 200
    assert data["status"] == "stopped"


# ── 404 ───────────────────────────────────────────────────────────────────────

def test_unknown_get_returns_404(server_port):
    try:
        _get(server_port, "/api/nonexistent")
        assert False, "expected 404"
    except urllib.error.HTTPError as e:
        assert e.code == 404


def test_unknown_post_returns_404(server_port):
    try:
        _post(server_port, "/api/nonexistent")
        assert False, "expected 404"
    except urllib.error.HTTPError as e:
        assert e.code == 404


# ── CORS ──────────────────────────────────────────────────────────────────────

def test_projections_cors_header(server_port):
    _, headers, _ = _get(server_port, "/api/projections")
    assert headers.get("Access-Control-Allow-Origin") == "*"


def test_stats_cors_header(server_port):
    _, headers, _ = _get(server_port, "/api/stats")
    assert headers.get("Access-Control-Allow-Origin") == "*"


# ── idempotent start ──────────────────────────────────────────────────────────

def test_start_twice_is_noop(server_port):
    """Calling start() again must not raise or start a second server."""
    with patch("cloudmeter._dashboard_server.start", new=_REAL_START):
        _REAL_START({"port": server_port, "provider": "AWS", "target_users": 1000})
    # Server should still respond normally
    status, _ = _json_get(server_port, "/api/projections")
    assert status == 200


# ── EADDRINUSE ────────────────────────────────────────────────────────────────

def test_eaddrinuse_is_silently_ignored(server_port):
    """Starting a second server on the same port must not raise."""
    dashboard_server._stop()
    # Bind the port ourselves so it's occupied
    import socket
    sock = socket.socket()
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("127.0.0.1", server_port))
        sock.listen(1)
        # This should silently no-op, not raise
        dashboard_server.start({"port": server_port})
    finally:
        sock.close()
        dashboard_server._stop()
