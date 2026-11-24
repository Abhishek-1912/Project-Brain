import pytest


def upload():
    return {"file": ("notes.txt", b"Dummy requirement", "text/plain")}


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"Authorization": "Bearer wrong-token"},
        {"Authorization": "test-service-token"},
        {"Authorization": "Basic test-service-token"},
    ],
)
def test_v1_rejects_missing_and_wrong_service_token(client, headers):
    response = client.post("/v1/extract", files=upload(), headers=headers)

    assert response.status_code == 401
    assert response.json() == {"code": "UNAUTHORIZED"}


def test_v1_fails_closed_without_configured_token(client, monkeypatch, auth_headers):
    monkeypatch.delenv("AI_SERVICE_TOKEN")

    response = client.post("/v1/extract", files=upload(), headers=auth_headers)

    assert response.status_code == 401


def test_health_needs_no_token_and_returns_no_config(client):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json"])
def test_private_service_publishes_no_api_docs(client, path):
    assert client.get(path).status_code == 404


def test_invalid_request_does_not_echo_input(client, auth_headers):
    response = client.post(
        "/v1/extract", data={"secret": "private document text"}, headers=auth_headers
    )

    assert response.status_code == 422
    assert response.json() == {"code": "INVALID_REQUEST"}
