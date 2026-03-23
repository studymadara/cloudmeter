"""
Fire-and-forget metric reporter.

Every call spawns a daemon thread so the user's request thread is never blocked.
All exceptions are swallowed — this must never crash the host application.
"""

import json
import threading
import urllib.request

from . import _sidecar


def report(
    route: str,
    method: str,
    status: int,
    duration_ms: int,
    egress_bytes: int = 0,
) -> None:
    def _send() -> None:
        try:
            payload = json.dumps(
                {
                    "route": route,
                    "method": method.upper(),
                    "status": status,
                    "durationMs": duration_ms,
                    "egressBytes": egress_bytes,
                }
            ).encode()
            req = urllib.request.Request(
                f"http://127.0.0.1:{_sidecar.get_ingest_port()}/api/metrics",
                data=payload,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            urllib.request.urlopen(req, timeout=0.5)
        except Exception:
            pass  # always silent — never affect the host app

    threading.Thread(target=_send, daemon=True).start()
