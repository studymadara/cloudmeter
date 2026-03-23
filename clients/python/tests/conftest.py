"""
Shared fixtures.

All tests mock out _sidecar.start() so no binary download or subprocess
spawn happens. Each test that cares about reported metrics patches
cloudmeter._reporter.report directly — the reporter fires synchronously
from the framework's after-request hook before threading, so no sleep needed.
"""
import pytest
from unittest.mock import patch, MagicMock


@pytest.fixture(autouse=True)
def no_sidecar():
    """Prevent any test from spawning the real sidecar binary."""
    with patch("cloudmeter._sidecar.start"), \
         patch("cloudmeter._sidecar.get_ingest_port", return_value=7778):
        yield


@pytest.fixture()
def capture_reports():
    """
    Returns a list that collects every call made to _reporter.report.
    Usage:
        def test_foo(capture_reports):
            client.get('/api/users/1')
            assert capture_reports[0]['route'] == 'GET /api/users/<int:user_id>'
    """
    calls = []
    with patch("cloudmeter._reporter.report", side_effect=lambda **kw: calls.append(kw)):
        yield calls
