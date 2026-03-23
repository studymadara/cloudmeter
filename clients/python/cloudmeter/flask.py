"""
Flask integration.

Usage:
    from cloudmeter.flask import CloudMeterFlask

    app = Flask(__name__)
    CloudMeterFlask(app, provider="AWS", target_users=1000)

    # Or use the Flask extension pattern:
    cm = CloudMeterFlask()
    cm.init_app(app)
"""
import time

from . import _reporter, _sidecar


class CloudMeterFlask:
    def __init__(self, app=None, **kwargs):
        self._kwargs = kwargs
        if app is not None:
            self.init_app(app, **kwargs)

    def init_app(self, app, **kwargs):
        opts = {**self._kwargs, **kwargs}
        _sidecar.start(**opts)

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
