"""
Flask middleware tests.

These validate the things your friends will care about immediately:
  - Route templates, not raw paths (/api/users/<int:id> not /api/users/42)
  - HTTP method and status captured correctly
  - Duration is a positive number
  - Errors in the reporter never crash the app
  - Middleware works with Flask blueprints
"""
import pytest
from flask import Flask, Blueprint
from cloudmeter.flask import CloudMeterFlask


# ── app fixtures ─────────────────────────────────────────────────────────────

@pytest.fixture()
def app():
    flask_app = Flask(__name__)
    CloudMeterFlask(flask_app, provider="AWS", target_users=100)

    @flask_app.route("/api/users/<int:user_id>")
    def get_user(user_id):
        return {"id": user_id}, 200

    @flask_app.route("/api/products")
    def list_products():
        return [], 200

    @flask_app.route("/api/items/<string:slug>")
    def get_item(slug):
        return {"slug": slug}, 200

    @flask_app.route("/api/missing")
    def not_found():
        return {"error": "gone"}, 404

    @flask_app.route("/api/upload", methods=["POST"])
    def upload():
        return {"ok": True}, 201

    return flask_app


@pytest.fixture()
def client(app):
    return app.test_client()


# ── route template tests ──────────────────────────────────────────────────────

def test_route_template_not_raw_path(client, capture_reports):
    """Most important test: /api/users/42 must be reported as /api/users/<int:user_id>."""
    client.get("/api/users/42")
    assert len(capture_reports) == 1
    assert capture_reports[0]["route"] == "GET /api/users/<int:user_id>"


def test_string_route_template(client, capture_reports):
    client.get("/api/items/my-product")
    assert capture_reports[0]["route"] == "GET /api/items/<string:slug>"


def test_static_route(client, capture_reports):
    client.get("/api/products")
    assert capture_reports[0]["route"] == "GET /api/products"


# ── method and status ─────────────────────────────────────────────────────────

def test_method_captured(client, capture_reports):
    client.post("/api/upload", json={})
    assert capture_reports[0]["method"] == "POST"


def test_status_200(client, capture_reports):
    client.get("/api/products")
    assert capture_reports[0]["status"] == 200


def test_status_404(client, capture_reports):
    client.get("/api/missing")
    assert capture_reports[0]["status"] == 404


def test_status_201(client, capture_reports):
    client.post("/api/upload", json={})
    assert capture_reports[0]["status"] == 201


# ── duration ──────────────────────────────────────────────────────────────────

def test_duration_is_positive(client, capture_reports):
    client.get("/api/products")
    assert capture_reports[0]["duration_ms"] >= 0


# ── one report per request ────────────────────────────────────────────────────

def test_one_report_per_request(client, capture_reports):
    client.get("/api/products")
    client.get("/api/users/1")
    client.get("/api/users/2")
    assert len(capture_reports) == 3


# ── reporter errors never crash the app ──────────────────────────────────────

def test_reporter_error_does_not_crash_app(app, capture_reports):
    """If reporter throws, the app response must still be returned."""
    from unittest.mock import patch
    with patch("cloudmeter._reporter.report", side_effect=RuntimeError("boom")):
        client = app.test_client()
        resp = client.get("/api/products")
    assert resp.status_code == 200


# ── Flask extension pattern (init_app) ────────────────────────────────────────

def test_init_app_pattern(capture_reports):
    flask_app = Flask(__name__)
    cm = CloudMeterFlask()  # create without app
    cm.init_app(flask_app)  # wire up later (common in app factories)

    @flask_app.route("/ping")
    def ping():
        return "pong", 200

    with flask_app.test_client() as c:
        resp = c.get("/ping")
    assert resp.status_code == 200
    assert capture_reports[0]["route"] == "GET /ping"


# ── blueprint routes ──────────────────────────────────────────────────────────

def test_blueprint_route_template(capture_reports):
    flask_app = Flask(__name__)
    CloudMeterFlask(flask_app)

    bp = Blueprint("orders", __name__, url_prefix="/api")

    @bp.route("/orders/<int:order_id>")
    def get_order(order_id):
        return {"order_id": order_id}, 200

    flask_app.register_blueprint(bp)

    with flask_app.test_client() as c:
        c.get("/api/orders/99")

    assert capture_reports[0]["route"] == "GET /api/orders/<int:order_id>"
