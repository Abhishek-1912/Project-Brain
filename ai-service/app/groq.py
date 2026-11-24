import json
import os

import httpx
from pydantic import BaseModel

from .provider import ProviderError, ProviderResult

DEFAULT_MODEL = "openai/gpt-oss-120b"
BASE_URL = "https://api.groq.com/openai/v1"
TIMEOUT_SECONDS = 60.0
# Groq's free tier allows about 8,000 tokens per minute for this model, so requests stay modest.
DEFAULT_MAX_OUTPUT_TOKENS = 4096


class GroqProvider:
    name = "groq"

    def __init__(self, client: httpx.Client, model: str, max_output_tokens: int = DEFAULT_MAX_OUTPUT_TOKENS) -> None:
        self.client = client
        self.model = model
        self.max_output_tokens = max_output_tokens

    def generate_json(self, system: str, document: str, schema: type[BaseModel]) -> ProviderResult:
        body = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": document},
            ],
            # Best-effort schema mode: strict mode would need every field required. Pydantic checks the result.
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": schema.__name__,
                    "strict": False,
                    "schema": schema.model_json_schema(by_alias=True),
                },
            },
            "reasoning_effort": "low",
            "include_reasoning": False,
            "max_completion_tokens": self.max_output_tokens,
        }
        # Errors are replaced, because provider messages can quote the request.
        try:
            response = self.client.post("/chat/completions", json=body)
        except httpx.TimeoutException:
            raise ProviderError("PROVIDER_TIMEOUT") from None
        except httpx.HTTPError:
            raise ProviderError("PROVIDER_ERROR") from None
        if response.status_code != 200:
            raise ProviderError(_error_code(response)) from None

        try:
            payload = response.json()
            usage = payload.get("usage") or {}
            input_tokens = int(usage.get("prompt_tokens") or 0)
            output_tokens = int(usage.get("completion_tokens") or 0)
            choice = payload["choices"][0]
        except (ValueError, KeyError, IndexError, TypeError, AttributeError):
            raise ProviderError("INVALID_OUTPUT") from None
        try:
            if choice.get("finish_reason") != "stop":
                raise ValueError
            data = json.loads(choice["message"]["content"])
        except (ValueError, KeyError, TypeError, AttributeError):
            raise ProviderError("INVALID_OUTPUT", input_tokens, output_tokens) from None
        return ProviderResult(data=data, model=self.model, input_tokens=input_tokens, output_tokens=output_tokens)


def _error_code(response: httpx.Response) -> str:
    if response.status_code == 429:
        return "RATE_LIMITED"
    if response.status_code in (408, 504):
        return "PROVIDER_TIMEOUT"
    try:
        error = response.json().get("error") or {}
        # Groq reports output that failed its JSON schema check this way; it deserves the one retry.
        if response.status_code == 400 and error.get("code") == "json_validate_failed":
            return "INVALID_OUTPUT"
    except (ValueError, AttributeError):
        pass
    return "PROVIDER_ERROR"


def get_groq_provider() -> GroqProvider:
    key = os.environ.get("GROQ_API_KEY")
    if not key:
        raise ProviderError("PROVIDER_ERROR")
    client = httpx.Client(
        base_url=BASE_URL,
        headers={"Authorization": f"Bearer {key}"},
        timeout=TIMEOUT_SECONDS,
    )
    return GroqProvider(client, os.environ.get("GROQ_MODEL") or DEFAULT_MODEL)
