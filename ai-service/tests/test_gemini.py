from types import SimpleNamespace

import pytest
from google.genai import errors, types

from app.gemini import GeminiProvider, get_provider
from app.provider import ProviderError
from app.schemas import AnalysisResult


class FakeModels:
    def __init__(self, outcome):
        self.outcome = outcome
        self.calls = []

    def generate_content(self, model, contents, config):
        self.calls.append({"model": model, "contents": contents, "config": config})
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


def response(text='{"summary":"ok"}', finish=types.FinishReason.STOP, candidates=True):
    return SimpleNamespace(
        text=text,
        candidates=[SimpleNamespace(finish_reason=finish)] if candidates else [],
        usage_metadata=SimpleNamespace(
            prompt_token_count=100, candidates_token_count=20, thoughts_token_count=7
        ),
    )


def provider(outcome):
    models = FakeModels(outcome)
    return GeminiProvider(SimpleNamespace(models=models), "gemini-test"), models


def run(instance):
    return instance.generate_json("System rules", '{"document":"Dummy"}', AnalysisResult)


def test_sends_json_schema_without_tools():
    instance, models = provider(response())

    run(instance)

    call = models.calls[0]
    assert call["model"] == "gemini-test"
    assert call["contents"] == '{"document":"Dummy"}'
    assert call["config"].system_instruction == "System rules"
    assert call["config"].response_mime_type == "application/json"
    assert "summary" in call["config"].response_json_schema["properties"]
    assert not call["config"].tools


def test_returns_parsed_data_and_counts_thinking_tokens():
    instance, _models = provider(response())

    result = run(instance)

    assert result.data == {"summary": "ok"}
    assert result.model == "gemini-test"
    assert result.input_tokens == 100
    assert result.output_tokens == 27


@pytest.mark.parametrize(
    "outcome",
    [
        response(finish=types.FinishReason.MAX_TOKENS),
        response(finish=types.FinishReason.SAFETY),
        response(text='{"summary":'),
        response(text=None),
        response(candidates=False),
    ],
)
def test_cut_off_blocked_or_malformed_output_is_invalid(outcome):
    instance, _models = provider(outcome)

    with pytest.raises(ProviderError) as caught:
        run(instance)

    assert caught.value.code == "INVALID_OUTPUT"


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (errors.ClientError(429, {"error": {"message": "private text"}}), "RATE_LIMITED"),
        (errors.ClientError(403, {"error": {"message": "private text"}}), "PROVIDER_ERROR"),
        (errors.ServerError(503, {"error": {"message": "private text"}}), "PROVIDER_ERROR"),
        (TimeoutError("private text"), "PROVIDER_TIMEOUT"),
        (RuntimeError("private text"), "PROVIDER_ERROR"),
    ],
)
def test_provider_errors_map_to_safe_codes(error, code):
    instance, _models = provider(error)

    with pytest.raises(ProviderError) as caught:
        run(instance)

    assert caught.value.code == code
    assert "private text" not in str(caught.value)
    assert caught.value.__cause__ is None
    assert caught.value.__suppress_context__ is True


def test_missing_key_is_a_safe_provider_error(monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)

    with pytest.raises(ProviderError) as caught:
        get_provider()

    assert caught.value.code == "PROVIDER_ERROR"


def test_cut_off_output_keeps_its_token_counts():
    instance, _models = provider(response(finish=types.FinishReason.MAX_TOKENS))

    with pytest.raises(ProviderError) as caught:
        run(instance)

    assert caught.value.code == "INVALID_OUTPUT"
    assert caught.value.input_tokens == 100
    assert caught.value.output_tokens == 27
