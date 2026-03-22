import time
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from .reporter import CloudMeterReporter


class CloudMeterMiddleware(BaseHTTPMiddleware):
    """
    FastAPI / Starlette middleware. Add to your app:

        from cloudmeter_client.fastapi_ext import CloudMeterMiddleware
        app.add_middleware(CloudMeterMiddleware)
    """
    def __init__(self, app, sidecar_url="http://127.0.0.1:7778"):
        super().__init__(app)
        self._reporter = CloudMeterReporter(sidecar_url)

    async def dispatch(self, request: Request, call_next):
        start = time.monotonic()
        response = await call_next(request)
        try:
            duration_ms = int((time.monotonic() - start) * 1000)
            route = request.scope.get('route')
            if route is not None:
                path = route.path
            else:
                path = request.url.path
            egress = int(response.headers.get('content-length', 0) or 0)
            self._reporter.record(path, request.method, response.status_code, duration_ms, egress)
        except Exception:
            pass
        return response
