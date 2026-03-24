"""
Native Python cost projector — port of sidecar-rs/src/projector.rs.

CPU proxy: Python has no per-request CPU measurement API (GIL).
Wall-clock duration_ms / 1000 is used as cpu_core_seconds.
Cost accuracy is ±40% vs the Java agent's ±20%.
"""

import statistics
from typing import Dict, List, Optional

from ._pricing_catalog import (
    GIB_IN_BYTES,
    HOURS_PER_MONTH,
    InstanceType,
    get_egress_rate_per_gib,
    get_instances,
)

SCALE_USERS = [100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000, 500_000, 1_000_000]
RECORDING_WINDOW_S = 300.0  # 5-minute rolling window (matches Rust sidecar)


def project(metrics: List[Dict], opts: Optional[Dict] = None) -> Dict:
    """
    Project monthly cost per route.

    opts keys:
        provider                  str   'AWS' | 'GCP' | 'AZURE'  (default 'AWS')
        target_users              int   (default 1000)
        requests_per_user_per_second  float  (default 1.0)
        budget_usd                float  (default 0 = disabled)

    Returns dict with keys: projections, warmup_count
    """
    opts = opts or {}
    provider = opts.get("provider", "AWS")
    target_users = opts.get("target_users", 1000)
    rpu = opts.get("requests_per_user_per_second", 1.0)
    budget_usd = opts.get("budget_usd", 0.0)

    instances = get_instances(provider)
    egress_rate = get_egress_rate_per_gib(provider)

    live = [m for m in metrics if not m.get("warmup", False)]
    warmup_count = len(metrics) - len(live)

    if not live:
        return {"projections": [], "warmup_count": warmup_count}

    total_observed_rps = len(live) / RECORDING_WINDOW_S
    safe_rps = max(total_observed_rps, 1e-9)
    target_total_rps = target_users * rpu

    by_route: Dict[str, list] = {}
    for m in live:
        by_route.setdefault(m["route"], []).append(m)

    projections = [
        _project_route(route, entries, safe_rps, target_total_rps, target_users, rpu, budget_usd, instances, egress_rate)
        for route, entries in by_route.items()
    ]
    projections.sort(key=lambda p: p["projected_monthly_cost_usd"], reverse=True)
    return {"projections": projections, "warmup_count": warmup_count}


def compute_stats(metrics: List[Dict], projections: List[Dict], opts: Optional[Dict] = None) -> List[Dict]:
    """
    Compute per-request cost distribution (p50/p95/p99) per route.
    Used by the variance panel in the dashboard.
    """
    opts = opts or {}
    provider = opts.get("provider", "AWS")
    egress_rate = get_egress_rate_per_gib(provider)
    instances = get_instances(provider)

    hourly_by_route: Dict[str, float] = {}
    for p in projections:
        inst = next((i for i in instances if i.name == p["recommended_instance"]), instances[0])
        hourly_by_route[p["route"]] = inst.hourly_usd

    live = [m for m in metrics if not m.get("warmup", False)]

    by_route: Dict[str, list] = {}
    for m in live:
        by_route.setdefault(m["route"], []).append(m)

    stats = []
    for route, entries in by_route.items():
        hourly_usd = hourly_by_route.get(route, 0.096)  # fallback: m5.large

        costs = sorted(
            (m["duration_ms"] / 1000.0 / 3600.0) * hourly_usd
            + (m.get("egress_bytes", 0) / GIB_IN_BYTES) * egress_rate
            for m in entries
        )

        p50 = _percentile(costs, 50)
        p95 = _percentile(costs, 95)
        p99 = _percentile(costs, 99)
        ratio = p95 / p50 if p50 > 0 else 0.0

        stats.append(
            {
                "route": route,
                "requests": len(entries),
                "p50_cost_usd": p50,
                "p95_cost_usd": p95,
                "p99_cost_usd": p99,
                "variance_ratio": round(ratio, 2),
                "variance_warning": ratio > 1.5,
            }
        )

    stats.sort(key=lambda s: s["p95_cost_usd"], reverse=True)
    return stats


# ── internal ──────────────────────────────────────────────────────────────────

def _project_route(
    route: str,
    entries: list,
    total_observed_rps: float,
    target_total_rps: float,
    target_users: int,
    rpu: float,
    budget_usd: float,
    instances: List[InstanceType],
    egress_rate: float,
) -> Dict:
    observed_rps = len(entries) / RECORDING_WINDOW_S
    scale_factor = target_total_rps / total_observed_rps
    projected_rps = observed_rps * scale_factor

    cpu_core_secs = [m["duration_ms"] / 1000.0 for m in entries]
    egress_bytes = [float(m.get("egress_bytes", 0)) for m in entries]
    durations = [float(m["duration_ms"]) for m in entries]

    median_cpu = _median(cpu_core_secs)
    median_egress = _median(egress_bytes)
    median_duration = _median(durations)

    monthly_cost = _monthly_cost(projected_rps, median_cpu, median_egress, instances, egress_rate)
    cost_per_user = monthly_cost / max(target_users, 1)
    recommended = _select_instance(projected_rps * median_cpu, instances)

    cost_curve = []
    for users in SCALE_USERS:
        scaled_rps = observed_rps * ((users * rpu) / total_observed_rps)
        cost_curve.append(
            {
                "users": users,
                "monthly_cost_usd": _r2(_monthly_cost(scaled_rps, median_cpu, median_egress, instances, egress_rate)),
            }
        )

    return {
        "route": route,
        "observed_rps": _r4(observed_rps),
        "projected_rps": _r1(projected_rps),
        "projected_monthly_cost_usd": _r2(monthly_cost),
        "projected_cost_per_user_usd": _r6(cost_per_user),
        "recommended_instance": recommended.name,
        "median_duration_ms": _r2(median_duration),
        "median_cpu_ms": _r2(median_cpu * 1000.0),
        "exceeds_budget": budget_usd > 0 and monthly_cost > budget_usd,
        "cost_curve": cost_curve,
    }


def _monthly_cost(
    projected_rps: float,
    median_cpu_core_sec: float,
    median_egress_bytes: float,
    instances: List[InstanceType],
    egress_rate: float,
) -> float:
    required_cores = projected_rps * median_cpu_core_sec
    inst = _select_instance(required_cores, instances)
    seconds_per_mo = HOURS_PER_MONTH * 3600.0
    egress_gib_per_mo = projected_rps * median_egress_bytes * seconds_per_mo / GIB_IN_BYTES
    return inst.hourly_usd * HOURS_PER_MONTH + egress_gib_per_mo * egress_rate


def _select_instance(required_cores: float, instances: List[InstanceType]) -> InstanceType:
    for inst in instances:
        if inst.vcpu >= required_cores:
            return inst
    return instances[-1]


def _median(values: List[float]) -> float:
    if not values:
        return 0.0
    return statistics.median(values)


def _percentile(sorted_values: List[float], p: int) -> float:
    if not sorted_values:
        return 0.0
    idx = min(int(p / 100.0 * len(sorted_values)), len(sorted_values) - 1)
    return sorted_values[idx]


def _r1(v: float) -> float:
    return round(v, 1)


def _r2(v: float) -> float:
    return round(v, 2)


def _r4(v: float) -> float:
    return round(v, 4)


def _r6(v: float) -> float:
    return round(v, 6)
