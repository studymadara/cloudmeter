"""
Reporter unit tests.

Validates the in-memory buffer API: report(), start_recording(),
stop_recording(), get_metrics(), clear(), and the warmup flag.
"""
import time

import cloudmeter._reporter as reporter

# ── buffer basics ─────────────────────────────────────────────────────────────

def test_report_not_stored_before_start_recording():
    """Metrics dropped when not recording."""
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    assert reporter.get_metrics() == []


def test_report_stored_after_start_recording():
    reporter.start_recording()
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    assert len(reporter.get_metrics()) == 1


def test_report_not_stored_after_stop_recording():
    reporter.start_recording()
    reporter.stop_recording()
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    assert reporter.get_metrics() == []


def test_start_recording_clears_previous_buffer():
    reporter.start_recording()
    reporter.report(route="GET /a", method="GET", status=200, duration_ms=1)
    reporter.start_recording()  # second call clears
    assert reporter.get_metrics() == []


def test_get_metrics_returns_copy():
    """Mutating the returned list must not affect the buffer."""
    reporter.start_recording()
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    m = reporter.get_metrics()
    m.clear()
    assert len(reporter.get_metrics()) == 1


def test_clear_empties_buffer_and_stops_recording():
    reporter.start_recording()
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    reporter.clear()
    assert reporter.get_metrics() == []
    # after clear, report() should be silently dropped
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    assert reporter.get_metrics() == []


# ── metric payload ────────────────────────────────────────────────────────────

def test_metric_shape():
    reporter.start_recording()
    reporter.report(
        route="GET /api/users/{id}",
        method="GET",
        status=200,
        duration_ms=42,
        egress_bytes=1024,
    )
    m = reporter.get_metrics()[0]
    assert m["route"] == "GET /api/users/{id}"
    assert m["method"] == "GET"
    assert m["status"] == 200
    assert m["duration_ms"] == 42
    assert m["egress_bytes"] == 1024
    assert "ts" in m
    assert "warmup" in m


def test_egress_bytes_defaults_to_zero():
    reporter.start_recording()
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    assert reporter.get_metrics()[0]["egress_bytes"] == 0


def test_method_uppercased():
    reporter.start_recording()
    reporter.report(route="POST /api/orders", method="post", status=201, duration_ms=10)
    assert reporter.get_metrics()[0]["method"] == "POST"


def test_multiple_metrics_accumulate():
    reporter.start_recording()
    for i in range(5):
        reporter.report(route="GET /ping", method="GET", status=200, duration_ms=i)
    assert len(reporter.get_metrics()) == 5


# ── warmup flag ───────────────────────────────────────────────────────────────

def test_warmup_true_immediately_after_start():
    reporter.start_recording()
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    assert reporter.get_metrics()[0]["warmup"] is True


def test_warmup_false_after_warmup_period(monkeypatch):
    """Simulate a metric recorded after the 30-second warmup window."""
    reporter.start_recording()
    # Monkey-patch _recording_start to be 31 seconds in the past
    monkeypatch.setattr(reporter, "_recording_start", time.time() - 31)
    reporter.report(route="GET /ping", method="GET", status=200, duration_ms=5)
    assert reporter.get_metrics()[0]["warmup"] is False


# ── error safety ──────────────────────────────────────────────────────────────

def test_report_never_raises_on_bad_input():
    """report() must swallow all exceptions — never crash the host app."""
    reporter.start_recording()
    # Pass no kwargs — this exercises the exception path
    try:
        reporter.report(route=None, method=None, status=None, duration_ms=None)
    except Exception as exc:
        raise AssertionError(f"report() raised: {exc}")
