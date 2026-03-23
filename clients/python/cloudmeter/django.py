"""
Django integration.

Add to settings.py:

    MIDDLEWARE = [
        'cloudmeter.django.CloudMeterMiddleware',
        ...
    ]

    CLOUDMETER = {
        "provider": "AWS",
        "region":   "us-east-1",
        "target_users": 1000,
    }

Route templates come from Django's URL resolver (e.g. api/users/<int:pk>/).
"""

import time

from . import _reporter, _sidecar

_started = False


class CloudMeterMiddleware:
    def __init__(self, get_response):
        global _started
        self.get_response = get_response

        if not _started:
            _started = True
            try:
                from django.conf import settings

                opts = getattr(settings, "CLOUDMETER", {})
            except Exception:
                opts = {}
            _sidecar.start(**opts)

    def __call__(self, request):
        start = time.monotonic()
        response = self.get_response(request)
        duration_ms = int((time.monotonic() - start) * 1000)

        try:
            if hasattr(request, "resolver_match") and request.resolver_match:
                # resolver_match.route gives the raw URL pattern
                route = request.resolver_match.route or request.path
            else:
                route = request.path
            egress = int(response.get("Content-Length") or 0)
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
