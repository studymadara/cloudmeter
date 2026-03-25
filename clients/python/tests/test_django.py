"""
Django middleware tests.

Uses lightweight mock request/response objects — no Django server needed.
This tests the middleware logic without requiring a full Django project setup.
"""
import time
from unittest.mock import patch

# ── mock Django objects ───────────────────────────────────────────────────────

class MockResolverMatch:
    def __init__(self, route):
        self.route = route


class MockRequest:
    def __init__(self, method="GET", path="/", route=None):
        self.method = method
        self.path = path
        self.resolver_match = MockResolverMatch(route) if route else None


class MockResponse:
    def __init__(self, status_code=200, content_length=None):
        self.status_code = status_code
        self._content_length = content_length

    def get(self, key, default=None):
        if key == "Content-Length":
            return str(self._content_length) if self._content_length else None
        return default


# ── helpers ───────────────────────────────────────────────────────────────────

def _default_response(_req):
    return MockResponse()


def make_middleware(get_response=None):
    """Import fresh to reset _started global between tests."""
    import cloudmeter.django as django_mod
    django_mod._started = False  # reset between tests

    if get_response is None:
        get_response = _default_response

    # _dashboard_server.start is already patched by the autouse reset_reporter fixture
    return django_mod.CloudMeterMiddleware(get_response)


# ── route template tests ──────────────────────────────────────────────────────

def test_django_uses_resolver_route(capture_reports):
    mw = make_middleware()
    req = MockRequest(method="GET", path="/api/users/42/", route="api/users/<int:pk>/")
    mw(req)
    assert capture_reports[0]["route"] == "GET api/users/<int:pk>/"


def test_django_falls_back_to_path_when_no_resolver(capture_reports):
    mw = make_middleware()
    req = MockRequest(method="GET", path="/api/health")
    req.resolver_match = None
    mw(req)
    assert capture_reports[0]["route"] == "GET /api/health"


# ── method and status ─────────────────────────────────────────────────────────

def test_post_method_captured(capture_reports):
    mw = make_middleware(lambda req: MockResponse(status_code=201))
    req = MockRequest(method="POST", path="/api/orders/", route="api/orders/")
    mw(req)
    assert capture_reports[0]["method"] == "POST"
    assert capture_reports[0]["status"] == 201


def test_404_captured(capture_reports):
    mw = make_middleware(lambda req: MockResponse(status_code=404))
    req = MockRequest(method="GET", path="/api/missing/", route="api/missing/")
    mw(req)
    assert capture_reports[0]["status"] == 404


# ── duration ──────────────────────────────────────────────────────────────────

def test_duration_positive(capture_reports):
    def slow_response(req):
        time.sleep(0.01)
        return MockResponse()

    mw = make_middleware(slow_response)
    req = MockRequest(method="GET", path="/api/slow/", route="api/slow/")
    mw(req)
    assert capture_reports[0]["duration_ms"] >= 0


# ── egress bytes ──────────────────────────────────────────────────────────────

def test_egress_bytes_captured(capture_reports):
    mw = make_middleware(lambda req: MockResponse(content_length=4096))
    req = MockRequest(method="GET", path="/api/download/", route="api/download/")
    mw(req)
    assert capture_reports[0]["egress_bytes"] == 4096


# ── reporter error never crashes the app ─────────────────────────────────────

def test_reporter_error_does_not_crash(capture_reports):
    mw = make_middleware()
    with patch("cloudmeter._reporter.report", side_effect=RuntimeError("boom")):
        req = MockRequest(method="GET", path="/api/products/", route="api/products/")
        response = mw(req)
    assert response.status_code == 200


# ── dashboard starts only once ───────────────────────────────────────────────

def test_dashboard_starts_only_once():
    import cloudmeter.django as django_mod
    django_mod._started = False

    with patch("cloudmeter._dashboard_server.start") as mock_start:
        django_mod.CloudMeterMiddleware(lambda req: MockResponse())
        django_mod.CloudMeterMiddleware(lambda req: MockResponse())
        django_mod.CloudMeterMiddleware(lambda req: MockResponse())

    mock_start.assert_called_once()
