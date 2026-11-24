import pytest
from fastapi.testclient import TestClient

from app.providers import get_provider
from app.main import app
from app.provider import ProviderResult

SERVICE_TOKEN = "test-service-token"


class FakeProvider:
    name = "fake"

    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.calls = []

    def generate_json(self, system, document, schema):
        self.calls.append({"system": system, "document": document, "schema": schema})
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return ProviderResult(data=outcome, model="fake-model", input_tokens=10, output_tokens=5)


@pytest.fixture
def use_provider():
    def install(*outcomes):
        provider = FakeProvider(*outcomes)
        app.dependency_overrides[get_provider] = lambda: provider
        return provider

    yield install
    app.dependency_overrides.clear()


@pytest.fixture(autouse=True)
def service_token(monkeypatch):
    monkeypatch.setenv("AI_SERVICE_TOKEN", SERVICE_TOKEN)


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def auth_headers():
    return {"Authorization": f"Bearer {SERVICE_TOKEN}"}
