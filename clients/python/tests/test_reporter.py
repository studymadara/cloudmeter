import pytest
from unittest.mock import patch, MagicMock
from cloudmeter_client.reporter import CloudMeterReporter


def test_record_calls_post():
    reporter = CloudMeterReporter()
    with patch.object(reporter, '_post') as mock_post:
        reporter.record('/api/test', 'GET', 200, 45, 1024)
        import time; time.sleep(0.05)  # let daemon thread run
        mock_post.assert_called_once()


def test_post_swallows_exceptions():
    reporter = CloudMeterReporter(sidecar_url="http://127.0.0.1:19999")  # nothing listening
    # Should not raise
    reporter._post(b'{"route":"GET /test","method":"GET","status":200,"durationMs":10,"egressBytes":0}')


def test_record_formats_route_with_method():
    reporter = CloudMeterReporter()
    posted = []
    def capture(payload):
        posted.append(payload)
    reporter._post = capture
    reporter.record('/api/users/{id}', 'get', 200, 30)
    import time; time.sleep(0.05)
    assert len(posted) == 1
    import json
    data = json.loads(posted[0])
    assert data['route'] == 'GET /api/users/{id}'
    assert data['method'] == 'GET'
