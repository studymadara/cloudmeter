import time
from .reporter import CloudMeterReporter


class CloudMeterDjangoMiddleware:
    """
    Django middleware. Add to settings.py MIDDLEWARE:

        'cloudmeter_client.django_ext.CloudMeterDjangoMiddleware'
    """
    def __init__(self, get_response, sidecar_url="http://127.0.0.1:7778"):
        self.get_response = get_response
        self._reporter = CloudMeterReporter(sidecar_url)

    def __call__(self, request):
        start = time.monotonic()
        response = self.get_response(request)
        try:
            duration_ms = int((time.monotonic() - start) * 1000)
            route = getattr(request.resolver_match, 'route', request.path) if request.resolver_match else request.path
            egress = int(response.get('Content-Length', 0) or 0)
            self._reporter.record(route, request.method, response.status_code, duration_ms, egress)
        except Exception:
            pass
        return response
