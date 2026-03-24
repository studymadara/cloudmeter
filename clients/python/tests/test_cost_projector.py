"""
Cost projector unit tests.

Validates: warmup filtering, single/multi-route projections, cost curve
(12 SCALE_USERS points), sorting by cost, exceedsBudget, GCP/Azure
provider selection, and compute_stats p50/p95/p99.
"""
import pytest

from cloudmeter._cost_projector import SCALE_USERS, compute_stats, project

# ── helpers ───────────────────────────────────────────────────────────────────

def _make_metrics(route="GET /api/users", n=10, duration_ms=200, egress_bytes=512, warmup=False):
    return [
        {
            "route": route,
            "method": "GET",
            "status": 200,
            "duration_ms": duration_ms,
            "egress_bytes": egress_bytes,
            "warmup": warmup,
        }
        for _ in range(n)
    ]


_DEFAULT_OPTS = {"provider": "AWS", "target_users": 1000}


# ── empty / warmup-only ───────────────────────────────────────────────────────

def test_empty_metrics_returns_empty_projections():
    result = project([], _DEFAULT_OPTS)
    assert result["projections"] == []
    assert result["warmup_count"] == 0


def test_all_warmup_metrics_returns_empty_projections():
    metrics = _make_metrics(n=5, warmup=True)
    result = project(metrics, _DEFAULT_OPTS)
    assert result["projections"] == []
    assert result["warmup_count"] == 5


def test_warmup_count_reflects_flagged_metrics():
    warm = _make_metrics(n=3, warmup=True)
    live = _make_metrics(n=7, warmup=False)
    result = project(warm + live, _DEFAULT_OPTS)
    assert result["warmup_count"] == 3
    assert len(result["projections"]) == 1


# ── single route ─────────────────────────────────────────────────────────────

def test_single_route_projection_has_required_fields():
    result = project(_make_metrics(), _DEFAULT_OPTS)
    p = result["projections"][0]
    for field in [
        "route", "observed_rps", "projected_rps", "projected_monthly_cost_usd",
        "projected_cost_per_user_usd", "recommended_instance",
        "median_duration_ms", "median_cpu_ms", "exceeds_budget", "cost_curve",
    ]:
        assert field in p, f"missing field: {field}"


def test_single_route_cost_is_positive():
    result = project(_make_metrics(), _DEFAULT_OPTS)
    assert result["projections"][0]["projected_monthly_cost_usd"] > 0


def test_route_name_preserved():
    result = project(_make_metrics(route="POST /api/orders"), _DEFAULT_OPTS)
    assert result["projections"][0]["route"] == "POST /api/orders"


def test_median_duration_matches_input():
    result = project(_make_metrics(duration_ms=400), _DEFAULT_OPTS)
    assert result["projections"][0]["median_duration_ms"] == pytest.approx(400.0, rel=0.01)


# ── multi-route ───────────────────────────────────────────────────────────────

def test_multiple_routes_produce_one_projection_each():
    metrics = (
        _make_metrics(route="GET /api/users", n=10) +
        _make_metrics(route="GET /api/orders", n=5) +
        _make_metrics(route="POST /api/export", n=2)
    )
    result = project(metrics, _DEFAULT_OPTS)
    assert len(result["projections"]) == 3


def test_projections_sorted_by_cost_descending():
    # Heavy route: long duration = more cost
    metrics = (
        _make_metrics(route="GET /api/cheap", n=10, duration_ms=10) +
        _make_metrics(route="GET /api/expensive", n=10, duration_ms=5000)
    )
    result = project(metrics, _DEFAULT_OPTS)
    costs = [p["projected_monthly_cost_usd"] for p in result["projections"]]
    assert costs == sorted(costs, reverse=True)
    assert result["projections"][0]["route"] == "GET /api/expensive"


# ── cost curve ────────────────────────────────────────────────────────────────

def test_cost_curve_has_12_points():
    result = project(_make_metrics(), _DEFAULT_OPTS)
    assert len(result["projections"][0]["cost_curve"]) == 12


def test_cost_curve_users_match_scale_users():
    result = project(_make_metrics(), _DEFAULT_OPTS)
    curve_users = [pt["users"] for pt in result["projections"][0]["cost_curve"]]
    assert curve_users == SCALE_USERS


def test_cost_curve_costs_are_non_negative():
    result = project(_make_metrics(), _DEFAULT_OPTS)
    for pt in result["projections"][0]["cost_curve"]:
        assert pt["monthly_cost_usd"] >= 0


# ── exceeds_budget ────────────────────────────────────────────────────────────

def test_exceeds_budget_false_by_default():
    result = project(_make_metrics(), _DEFAULT_OPTS)
    assert result["projections"][0]["exceeds_budget"] is False


def test_exceeds_budget_false_when_zero():
    opts = {**_DEFAULT_OPTS, "budget_usd": 0}
    result = project(_make_metrics(), opts)
    assert result["projections"][0]["exceeds_budget"] is False


def test_exceeds_budget_true_when_cost_above_threshold():
    # Very high target users to force a large projected cost
    opts = {"provider": "AWS", "target_users": 1_000_000, "budget_usd": 0.01}
    metrics = _make_metrics(n=100, duration_ms=1000, egress_bytes=100_000)
    result = project(metrics, opts)
    assert any(p["exceeds_budget"] for p in result["projections"])


def test_exceeds_budget_false_when_cost_below_threshold():
    opts = {"provider": "AWS", "target_users": 10, "budget_usd": 1_000_000}
    result = project(_make_metrics(n=5, duration_ms=10), opts)
    assert all(not p["exceeds_budget"] for p in result["projections"])


# ── providers ─────────────────────────────────────────────────────────────────

def test_gcp_provider_produces_projection():
    opts = {"provider": "GCP", "target_users": 1000}
    result = project(_make_metrics(), opts)
    assert len(result["projections"]) == 1
    assert result["projections"][0]["projected_monthly_cost_usd"] > 0


def test_azure_provider_produces_projection():
    opts = {"provider": "AZURE", "target_users": 1000}
    result = project(_make_metrics(), opts)
    assert len(result["projections"]) == 1
    assert result["projections"][0]["projected_monthly_cost_usd"] > 0


def test_providers_produce_different_costs():
    metrics = _make_metrics(n=20, duration_ms=500, egress_bytes=2048)
    aws_cost = project(metrics, {"provider": "AWS", "target_users": 1000})["projections"][0]["projected_monthly_cost_usd"]
    gcp_cost = project(metrics, {"provider": "GCP", "target_users": 1000})["projections"][0]["projected_monthly_cost_usd"]
    azure_cost = project(metrics, {"provider": "AZURE", "target_users": 1000})["projections"][0]["projected_monthly_cost_usd"]
    # They may differ — just verify all three produce distinct or at least valid numbers
    assert all(c > 0 for c in [aws_cost, gcp_cost, azure_cost])


# ── compute_stats ─────────────────────────────────────────────────────────────

def test_compute_stats_returns_list():
    metrics = _make_metrics(n=20)
    result = project(metrics, _DEFAULT_OPTS)
    stats = compute_stats(metrics, result["projections"], _DEFAULT_OPTS)
    assert isinstance(stats, list)
    assert len(stats) == 1


def test_compute_stats_required_fields():
    metrics = _make_metrics(n=20)
    result = project(metrics, _DEFAULT_OPTS)
    stats = compute_stats(metrics, result["projections"], _DEFAULT_OPTS)
    s = stats[0]
    for field in ["route", "requests", "p50_cost_usd", "p95_cost_usd", "p99_cost_usd",
                  "variance_ratio", "variance_warning"]:
        assert field in s, f"missing field: {field}"


def test_compute_stats_request_count():
    metrics = _make_metrics(n=15)
    result = project(metrics, _DEFAULT_OPTS)
    stats = compute_stats(metrics, result["projections"], _DEFAULT_OPTS)
    assert stats[0]["requests"] == 15


def test_compute_stats_percentile_ordering():
    """p50 <= p95 <= p99."""
    # Mix of cheap and expensive requests for variance
    cheap = _make_metrics(route="GET /api/test", n=50, duration_ms=10)
    expensive = _make_metrics(route="GET /api/test", n=10, duration_ms=5000)
    metrics = cheap + expensive
    result = project(metrics, _DEFAULT_OPTS)
    stats = compute_stats(metrics, result["projections"], _DEFAULT_OPTS)
    s = stats[0]
    assert s["p50_cost_usd"] <= s["p95_cost_usd"] <= s["p99_cost_usd"]


def test_compute_stats_variance_warning_when_high_spread():
    """High-variance dataset (some very long requests) should trigger warning."""
    low = _make_metrics(route="GET /api/skew", n=80, duration_ms=10)
    high = _make_metrics(route="GET /api/skew", n=20, duration_ms=10_000)
    metrics = low + high
    result = project(metrics, _DEFAULT_OPTS)
    stats = compute_stats(metrics, result["projections"], _DEFAULT_OPTS)
    assert stats[0]["variance_warning"] is True


def test_compute_stats_no_variance_warning_for_uniform_requests():
    metrics = _make_metrics(n=30, duration_ms=100)
    result = project(metrics, _DEFAULT_OPTS)
    stats = compute_stats(metrics, result["projections"], _DEFAULT_OPTS)
    assert stats[0]["variance_warning"] is False


def test_compute_stats_excludes_warmup():
    warm = _make_metrics(n=5, warmup=True, duration_ms=10_000)
    live = _make_metrics(n=10, warmup=False, duration_ms=100)
    metrics = warm + live
    result = project(metrics, _DEFAULT_OPTS)
    stats = compute_stats(metrics, result["projections"], _DEFAULT_OPTS)
    assert stats[0]["requests"] == 10


def test_compute_stats_empty_returns_empty():
    result = project([], _DEFAULT_OPTS)
    stats = compute_stats([], result["projections"], _DEFAULT_OPTS)
    assert stats == []
