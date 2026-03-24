"""
FastAPI / Starlette middleware tests.

FastAPI route templates use {param} syntax (e.g. /api/users/{user_id}).
Starlette populates scope["route"] after routing, which gives us the template.
"""
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cloudmeter.fastapi import CloudMeterMiddleware

# ── app fixture ───────────────────────────────────────────────────────────────

@pytest.fixture()
def app():
    fastapi_app = FastAPI()
    fastapi_app.add_middleware(CloudMeterMiddleware, provider="AWS", target_users=100)

    @fastapi_app.get("/api/users/{user_id}")
    def get_user(user_id: int):
        return {"id": user_id}

    @fastapi_app.get("/api/products")
    def list_products():
        return []

    @fastapi_app.post("/api/orders")
    def create_order():
        return {"created": True}

    @fastapi_app.get("/api/missing")
    def missing():
        from fastapi import HTTPException
        raise HTTPException(status_code=404, detail="not found")

    return fastapi_app


@pytest.fixture()
def client(app):
    return TestClient(app, raise_server_exceptions=False)


# ── route template tests ──────────────────────────────────────────────────────

def test_route_template_not_raw_path(client, capture_reports):
    """/api/users/42 must be reported as /api/users/{user_id}."""
    client.get("/api/users/42")
    assert len(capture_reports) == 1
    assert capture_reports[0]["route"] == "GET /api/users/{user_id}"


def test_static_route(client, capture_reports):
    client.get("/api/products")
    assert capture_reports[0]["route"] == "GET /api/products"


# ── method and status ─────────────────────────────────────────────────────────

def test_post_method(client, capture_reports):
    client.post("/api/orders")
    assert capture_reports[0]["method"] == "POST"
    assert capture_reports[0]["status"] == 200


def test_404_captured(client, capture_reports):
    client.get("/api/missing")
    assert capture_reports[0]["status"] == 404


# ── duration ──────────────────────────────────────────────────────────────────

def test_duration_positive(client, capture_reports):
    client.get("/api/products")
    assert capture_reports[0]["duration_ms"] >= 0


# ── reporter error doesn't crash the app ─────────────────────────────────────

def test_reporter_error_does_not_crash_app(app):
    from unittest.mock import patch
    with patch("cloudmeter._reporter.report", side_effect=RuntimeError("boom")):
        c = TestClient(app, raise_server_exceptions=False)
        resp = c.get("/api/products")
    assert resp.status_code == 200


# ── multiple requests ─────────────────────────────────────────────────────────

def test_one_report_per_request(client, capture_reports):
    client.get("/api/products")
    client.get("/api/users/1")
    client.get("/api/users/2")
    assert len(capture_reports) == 3
