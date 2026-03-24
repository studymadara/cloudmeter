"""
Shared fixtures.

The dashboard server is patched out in all tests so no port is bound.
capture_reports patches _reporter.report directly — synchronous call,
no threading, no sleep needed.
"""
from unittest.mock import patch

import pytest

import cloudmeter._reporter as _reporter


@pytest.fixture(autouse=True)
def reset_reporter():
    """Clear the in-process buffer and stop dashboard server before every test."""
    _reporter.clear()
    with patch("cloudmeter._dashboard_server.start"):
        yield
    _reporter.clear()


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
