"""
CloudMeter — per-endpoint cloud cost monitoring for Python web apps.

Quick start:

    Flask:
        from cloudmeter.flask import CloudMeterFlask
        CloudMeterFlask(app, provider="AWS", target_users=1000)

    FastAPI:
        from cloudmeter.fastapi import CloudMeterMiddleware
        app.add_middleware(CloudMeterMiddleware, provider="AWS", target_users=1000)

    Django — settings.py:
        MIDDLEWARE = ['cloudmeter.django.CloudMeterMiddleware', ...]
        CLOUDMETER = {"provider": "AWS", "target_users": 1000}

Dashboard: http://localhost:7777
"""
from ._sidecar import start, stop, ensure_binary

__all__ = ["start", "stop", "ensure_binary"]
__version__ = "0.1.0"
