import time
from .reporter import CloudMeterReporter


class CloudMeterFlask:
    """
    Flask integration. Add to your app:

        from cloudmeter_client.flask_ext import CloudMeterFlask
        CloudMeterFlask(app)
    """
    def __init__(self, app=None, sidecar_url="http://127.0.0.1:7778"):
        self._reporter = CloudMeterReporter(sidecar_url)
        if app is not None:
            self.init_app(app)

    def init_app(self, app):
        app.before_request(self._before)
        app.after_request(self._after)
        # Store start time in Flask's g
        import flask
        self._flask = flask

    def _before(self):
        self._flask.g._cm_start = time.monotonic()

    def _after(self, response):
        try:
            duration_ms = int((time.monotonic() - self._flask.g._cm_start) * 1000)
            from flask import request
            route = request.url_rule.rule if request.url_rule else request.path
            egress = int(response.headers.get('Content-Length', 0) or 0)
            self._reporter.record(route, request.method, response.status_code, duration_ms, egress)
        except Exception:
            pass
        return response
