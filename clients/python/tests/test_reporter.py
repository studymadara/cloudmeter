"""
Reporter unit tests.

Validates the fire-and-forget HTTP POST logic, payload shape,
error swallowing, and method normalisation.
"""
import json
import time
import threading
from unittest.mock import patch, MagicMock
import cloudmeter._reporter as reporter


def _call_report_and_wait(**kwargs):
    """Call report() and wait long enough for the daemon thread to fire."""
    reporter.report(**kwargs)
    time.sleep(0.15)


# ── payload shape ─────────────────────────────────────────────────────────────

def test_posts_correct_json():
    captured = []

    def fake_urlopen(req, timeout=None):
        captured.append(json.loads(req.data))
        return MagicMock()

    with patch("cloudmeter._reporter.urllib.request.urlopen", fake_urlopen):
        _call_report_and_wait(
            route="GET /api/users/{id}",
            method="GET",
            status=200,
            duration_ms=42,
            egress_bytes=1024,
        )

    assert len(captured) == 1
    assert captured[0]["route"] == "GET /api/users/{id}"
    assert captured[0]["method"] == "GET"
    assert captured[0]["status"] == 200
    assert captured[0]["durationMs"] == 42
    assert captured[0]["egressBytes"] == 1024


def test_egress_bytes_defaults_to_zero():
    captured = []

    def fake_urlopen(req, timeout=None):
        captured.append(json.loads(req.data))
        return MagicMock()

    with patch("cloudmeter._reporter.urllib.request.urlopen", fake_urlopen):
        _call_report_and_wait(route="GET /ping", method="GET", status=200, duration_ms=5)

    assert captured[0]["egressBytes"] == 0


def test_method_uppercased():
    captured = []

    def fake_urlopen(req, timeout=None):
        captured.append(json.loads(req.data))
        return MagicMock()

    with patch("cloudmeter._reporter.urllib.request.urlopen", fake_urlopen):
        _call_report_and_wait(route="POST /api/orders", method="post", status=201, duration_ms=10)

    assert captured[0]["method"] == "POST"


# ── error swallowing ──────────────────────────────────────────────────────────

def test_network_error_is_swallowed():
    """If the sidecar is down, report() must not raise."""
    import urllib.error
    with patch(
        "cloudmeter._reporter.urllib.request.urlopen",
        side_effect=urllib.error.URLError("connection refused"),
    ):
        reporter.report(route="GET /api/test", method="GET", status=200, duration_ms=1)
        time.sleep(0.15)
    # no exception = pass


def test_generic_exception_is_swallowed():
    with patch("cloudmeter._reporter.urllib.request.urlopen", side_effect=Exception("anything")):
        reporter.report(route="GET /api/test", method="GET", status=200, duration_ms=1)
        time.sleep(0.15)
    # no exception = pass


# ── fire-and-forget (daemon thread) ──────────────────────────────────────────

def test_report_does_not_block_caller():
    """report() should return immediately, not wait for the HTTP POST."""
    slow_barrier = threading.Event()

    def slow_urlopen(req, timeout=None):
        slow_barrier.wait(timeout=2)
        return MagicMock()

    with patch("cloudmeter._reporter.urllib.request.urlopen", slow_urlopen):
        start = time.monotonic()
        reporter.report(route="GET /api/test", method="GET", status=200, duration_ms=1)
        elapsed = time.monotonic() - start

    assert elapsed < 0.1, f"report() blocked for {elapsed:.3f}s — should be instant"
    slow_barrier.set()  # unblock the thread


# ── correct HTTP target ───────────────────────────────────────────────────────

def test_posts_to_correct_endpoint():
    captured_urls = []

    def fake_urlopen(req, timeout=None):
        captured_urls.append(req.full_url)
        return MagicMock()

    with patch("cloudmeter._reporter.urllib.request.urlopen", fake_urlopen), \
         patch("cloudmeter._sidecar.get_ingest_port", return_value=7778):
        _call_report_and_wait(route="GET /api/test", method="GET", status=200, duration_ms=1)

    assert captured_urls[0] == "http://127.0.0.1:7778/api/metrics"
