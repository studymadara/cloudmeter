"""
Flask integration.

Usage:
    from cloudmeter.flask import CloudMeterFlask

    app = Flask(__name__)
    CloudMeterFlask(app, provider="AWS", target_users=1000)

    # Or use the Flask extension pattern:
    cm = CloudMeterFlask()
    cm.init_app(app)

Options:
    provider                      'AWS' | 'GCP' | 'AZURE'  (default 'AWS')
    region                        str                        (default 'us-east-1')
    target_users                  int                        (default 1000)
    requests_per_user_per_second  float                      (default 1.0)
    budget_usd                    float                      (default 0 = disabled)
    port                          int                        (default 7777)

On first call, starts the dashboard at http://127.0.0.1:<port>.
"""

import time

from . import _dashboard_server, _reporter


class CloudMeterFlask:
    def __init__(self, app=None, **kwargs):
        self._kwargs = kwargs
        if app is not None:
            self.init_app(app, **kwargs)

    def init_app(self, app, **kwargs):
        opts = {**self._kwargs, **kwargs}
        _reporter.start_recording()
        _dashboard_server.start(opts)

        app.before_request(_before)
        app.after_request(_after)


def _before():
    from flask import g

    g._cm_start = time.monotonic()


def _after(response):
    from flask import g, request

    try:
        duration_ms = int((time.monotonic() - g._cm_start) * 1000)
        # request.url_rule gives the template e.g. /api/users/<int:id>
        route = str(request.url_rule) if request.url_rule else request.path
        egress = int(response.headers.get("Content-Length") or 0)
        _reporter.report(
            route=f"{request.method} {route}",
            method=request.method,
            status=response.status_code,
            duration_ms=duration_ms,
            egress_bytes=egress,
        )
    except Exception:
        pass
    return response
