"""
Native Python dashboard server — no third-party dependencies.
Runs in a daemon thread on 127.0.0.1:7777 (default).
Silently no-ops if the port is already in use.
"""

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any, Dict, Optional

from . import _reporter
from ._cost_projector import compute_stats, project
from ._pricing_catalog import PRICING_DATE

_DASHBOARD_HTML = os.path.join(os.path.dirname(__file__), "dashboard.html")

_server: Optional[HTTPServer] = None
_lock = threading.Lock()


def start(opts: Optional[Dict] = None) -> None:
    """Start the dashboard server. Calling more than once is a no-op."""
    global _server
    with _lock:
        if _server is not None:
            return

    opts = opts or {}
    provider = opts.get("provider", "AWS")
    region = opts.get("region", "us-east-1")
    target_users = opts.get("target_users", 1000)
    rpu = opts.get("requests_per_user_per_second", 1.0)
    budget_usd = opts.get("budget_usd", 0.0)
    port = opts.get("port", 7777)

    proj_opts = {
        "provider": provider,
        "target_users": target_users,
        "requests_per_user_per_second": rpu,
        "budget_usd": budget_usd,
    }

    handler = _make_handler(provider, region, target_users, rpu, budget_usd, proj_opts)

    try:
        srv = HTTPServer(("127.0.0.1", port), handler)
    except OSError:
        # EADDRINUSE — silently no-op
        return

    with _lock:
        _server = srv

    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    print(f"[cloudmeter] dashboard → http://127.0.0.1:{port}", flush=True)


def _stop() -> None:
    """For tests only — shuts down the server and resets state."""
    global _server
    with _lock:
        if _server:
            _server.shutdown()
            _server = None


def _make_handler(
    provider: str,
    region: str,
    target_users: int,
    rpu: float,
    budget_usd: float,
    proj_opts: Dict,
) -> type:
    class _Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args: Any) -> None:  # silence access logs
            pass

        def _send_json(self, body: Any, status: int = 200) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self) -> None:
            if self.path == "/":
                try:
                    with open(_DASHBOARD_HTML, "rb") as f:
                        html = f.read()
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.end_headers()
                    self.wfile.write(html)
                except OSError:
                    self.send_response(500)
                    self.end_headers()
                    self.wfile.write(b"dashboard.html not found")
                return

            if self.path == "/api/projections":
                metrics = _reporter.get_metrics()
                result = project(metrics, proj_opts)
                projs = result["projections"]
                total = sum(p["projected_monthly_cost_usd"] for p in projs)
                self._send_json(
                    {
                        "meta": {
                            "provider": provider,
                            "region": region,
                            "targetUsers": target_users,
                            "requestsPerUserPerSecond": rpu,
                            "budgetUsd": budget_usd,
                            "pricingDate": PRICING_DATE,
                            "pricingSource": "static",
                        },
                        "projections": _camel_projections(projs),
                        "summary": {
                            "totalProjectedMonthlyCostUsd": round(total, 2),
                            "anyExceedsBudget": any(p["exceeds_budget"] for p in projs),
                            "warmupMetricsExcluded": result["warmup_count"],
                        },
                    }
                )
                return

            if self.path == "/api/stats":
                metrics = _reporter.get_metrics()
                result = project(metrics, proj_opts)
                stats = compute_stats(metrics, result["projections"], proj_opts)
                self._send_json({"stats": _camel_stats(stats)})
                return

            self.send_response(404)
            self.end_headers()

        def do_POST(self) -> None:
            if self.path == "/api/recording/start":
                _reporter.start_recording()
                self._send_json({"status": "recording"})
                return

            if self.path == "/api/recording/stop":
                _reporter.stop_recording()
                self._send_json({"status": "stopped"})
                return

            self.send_response(404)
            self.end_headers()

    return _Handler


# ── camelCase adapters for the dashboard HTML (which uses JS naming) ──────────


def _camel_projections(projs: list) -> list:
    out = []
    for p in projs:
        out.append(
            {
                "route": p["route"],
                "observedRps": p["observed_rps"],
                "projectedRps": p["projected_rps"],
                "projectedMonthlyCostUsd": p["projected_monthly_cost_usd"],
                "projectedCostPerUserUsd": p["projected_cost_per_user_usd"],
                "recommendedInstance": p["recommended_instance"],
                "medianDurationMs": p["median_duration_ms"],
                "medianCpuMs": p["median_cpu_ms"],
                "exceedsBudget": p["exceeds_budget"],
                "costCurve": [{"users": c["users"], "monthlyCostUsd": c["monthly_cost_usd"]} for c in p["cost_curve"]],
            }
        )
    return out


def _camel_stats(stats: list) -> list:
    out = []
    for s in stats:
        out.append(
            {
                "route": s["route"],
                "requests": s["requests"],
                "p50CostUsd": s["p50_cost_usd"],
                "p95CostUsd": s["p95_cost_usd"],
                "p99CostUsd": s["p99_cost_usd"],
                "varianceRatio": s["variance_ratio"],
                "varianceWarning": s["variance_warning"],
            }
        )
    return out
