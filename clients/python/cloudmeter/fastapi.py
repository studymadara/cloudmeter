"""
FastAPI / Starlette integration.

Usage:
    from cloudmeter.fastapi import CloudMeterMiddleware

    app = FastAPI()
    app.add_middleware(CloudMeterMiddleware, provider="AWS", target_users=1000)

Route templates (e.g. /api/users/{user_id}) are captured via Starlette's
routing scope — no manual normalization needed.
"""

import time

from . import _reporter, _sidecar


class CloudMeterMiddleware:
    """ASGI middleware compatible with FastAPI and any Starlette-based app."""

    def __init__(self, app, **kwargs):
        self._app = app
        _sidecar.start(**kwargs)

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        start = time.monotonic()
        status_code = 500
        content_length = 0

        async def send_wrapper(message):
            nonlocal status_code, content_length
            if message["type"] == "http.response.start":
                status_code = message["status"]
                for name, value in message.get("headers", []):
                    if name.lower() == b"content-length":
                        content_length = int(value)
            await send(message)

        await self._app(scope, receive, send_wrapper)

        try:
            duration_ms = int((time.monotonic() - start) * 1000)
            # Starlette populates scope["route"] after routing resolves
            route_obj = scope.get("route")
            route_path = route_obj.path if route_obj is not None else scope.get("path", "/")
            method = scope.get("method", "GET")
            _reporter.report(
                route=f"{method} {route_path}",
                method=method,
                status=status_code,
                duration_ms=duration_ms,
                egress_bytes=content_length,
            )
        except Exception:
            pass
