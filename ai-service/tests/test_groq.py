import json

import httpx
import pytest

from app.groq import GroqProvider
from app.provider import ProviderError
from app.providers import get_provider
from app.schemas import AnalysisResult


def completion(content='{"summary":"ok"}', finish="stop", prompt_tokens=120, completion_tokens=30):
    return {
        "choices": [{"index": 0, "finish_reason": finish, "message": {"role": "assistant", "content": content}}],
        "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens},
    }


def provider(handler):
    requests = []

    def record(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return handler(request)

    client = httpx.Client(base_url="https://groq.test/openai/v1", transport=httpx.MockTransport(record))
    return GroqProvider(client, "openai/gpt-oss-120b", max_output_tokens=4096), requests


def run(instance):
    return instance.generate_json("System rules", '{"document":"Dummy"}', AnalysisResult)


def test_sends_schema_and_untrusted_document_without_tools():
    instance, requests = provider(lambda request: httpx.Response(200, json=completion()))

    run(instance)

    body = json.loads(requests[0].content)
    assert requests[0].url.path == "/openai/v1/chat/completions"
    assert body["model"] == "openai/gpt-oss-120b"
    assert body["messages"] == [
        {"role": "system", "content": "System rules"},
        {"role": "user", "content": '{"document":"Dummy"}'},
    ]
    assert body["response_format"]["type"] == "json_schema"
    assert body["response_format"]["json_schema"]["name"] == "AnalysisResult"
    assert body["response_format"]["json_schema"]["strict"] is False
    assert body["response_format"]["json_schema"]["schema"] == AnalysisResult.model_json_schema(by_alias=True)
    assert body["reasoning_effort"] == "low"
    assert body["include_reasoning"] is False
    assert body["max_completion_tokens"] == 4096
    assert "tools" not in body


def test_returns_parsed_data_and_token_counts():
    instance, _requests = provider(lambda request: httpx.Response(200, json=completion()))

    result = run(instance)

    assert result.data == {"summary": "ok"}
    assert result.model == "openai/gpt-oss-120b"
    assert result.input_tokens == 120
    assert result.output_tokens == 30


@pytest.mark.parametrize("payload", [completion(finish="length"), completion(content="not json")])
def test_cut_off_or_malformed_output_is_invalid_and_keeps_tokens(payload):
    instance, _requests = provider(lambda request: httpx.Response(200, json=payload))

    with pytest.raises(ProviderError) as caught:
        run(instance)

    assert caught.value.code == "INVALID_OUTPUT"
    assert caught.value.input_tokens == 120
    assert caught.value.output_tokens == 30


@pytest.mark.parametrize(
    "status, body, code",
    [
        (429, {"error": {"message": "private text"}}, "RATE_LIMITED"),
        (500, {"error": {"message": "private text"}}, "PROVIDER_ERROR"),
        (401, {"error": {"message": "private text"}}, "PROVIDER_ERROR"),
        (400, {"error": {"code": "json_validate_failed", "message": "private text"}}, "INVALID_OUTPUT"),
    ],
)
def test_http_errors_map_to_safe_codes(status, body, code):
    instance, _requests = provider(lambda request: httpx.Response(status, json=body))

    with pytest.raises(ProviderError) as caught:
        run(instance)

    assert caught.value.code == code
    assert "private text" not in str(caught.value)
    assert caught.value.__suppress_context__ is True


def test_timeout_is_a_safe_timeout_code():
    def slow(request):
        raise httpx.ReadTimeout("private text", request=request)

    instance, _requests = provider(slow)

    with pytest.raises(ProviderError) as caught:
        run(instance)

    assert caught.value.code == "PROVIDER_TIMEOUT"


def test_ai_provider_setting_selects_groq(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER", "groq")
    monkeypatch.setenv("GROQ_API_KEY", "test-groq-key")
    monkeypatch.delenv("GROQ_MODEL", raising=False)

    selected = get_provider()

    assert isinstance(selected, GroqProvider)
    assert selected.name == "groq"
    assert selected.model == "openai/gpt-oss-120b"


def test_missing_groq_key_is_a_safe_provider_error(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER", "groq")
    monkeypatch.delenv("GROQ_API_KEY", raising=False)

    with pytest.raises(ProviderError) as caught:
        get_provider()

    assert caught.value.code == "PROVIDER_ERROR"


def test_unknown_provider_setting_is_a_safe_provider_error(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER", "mystery")

    with pytest.raises(ProviderError) as caught:
        get_provider()

    assert caught.value.code == "PROVIDER_ERROR"
