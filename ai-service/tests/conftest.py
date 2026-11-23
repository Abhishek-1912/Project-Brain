import pytest
from fastapi.testclient import TestClient

from app.main import app

SERVICE_TOKEN = "test-service-token"


@pytest.fixture(autouse=True)
def service_token(monkeypatch):
    monkeypatch.setenv("AI_SERVICE_TOKEN", SERVICE_TOKEN)


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def auth_headers():
    return {"Authorization": f"Bearer {SERVICE_TOKEN}"}
