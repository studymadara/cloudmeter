import json
import threading
import time
import urllib.request
from urllib.error import URLError


class CloudMeterReporter:
    def __init__(self, sidecar_url="http://127.0.0.1:7778"):
        self._url = sidecar_url.rstrip('/') + '/api/metrics'

    def record(self, route: str, method: str, status: int, duration_ms: int, egress_bytes: int = 0):
        """Fire-and-forget metric report. Never raises."""
        payload = json.dumps({
            "route": method.upper() + " " + route,
            "method": method.upper(),
            "status": status,
            "durationMs": duration_ms,
            "egressBytes": egress_bytes,
        }).encode('utf-8')
        t = threading.Thread(target=self._post, args=(payload,), daemon=True)
        t.start()

    def _post(self, payload: bytes):
        try:
            req = urllib.request.Request(self._url, data=payload,
                                         headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=2):
                pass
        except Exception:
            pass  # never let reporting crash the app
