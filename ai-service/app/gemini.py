import json
import os

from google import genai
from google.genai import errors, types
from pydantic import BaseModel

from .provider import ProviderError, ProviderResult

DEFAULT_MODEL = "gemini-3.8-flash"
TIMEOUT_MILLISECONDS = 60_000
MAX_OUTPUT_TOKENS = 32_768


class GeminiProvider:
    name = "gemini"

    def __init__(self, client, model: str) -> None:
        self.client = client
        self.model = model

    def generate_json(self, system: str, document: str, schema: type[BaseModel]) -> ProviderResult:
        config = types.GenerateContentConfig(
            system_instruction=system,
            response_mime_type="application/json",
            response_json_schema=schema.model_json_schema(by_alias=True),
            temperature=0.2,
            max_output_tokens=MAX_OUTPUT_TOKENS,
        )
        # Errors are replaced, because provider messages can quote the request.
        try:
            response = self.client.models.generate_content(
                model=self.model, contents=document, config=config
            )
        except errors.ClientError as error:
            code = "RATE_LIMITED" if error.code == 429 else "PROVIDER_ERROR"
            raise ProviderError(code) from None
        except errors.APIError:
            raise ProviderError("PROVIDER_ERROR") from None
        except Exception as error:
            timed_out = isinstance(error, TimeoutError) or "timeout" in type(error).__name__.lower()
            raise ProviderError("PROVIDER_TIMEOUT" if timed_out else "PROVIDER_ERROR") from None

        input_tokens = _count(response, "prompt_token_count")
        output_tokens = _count(response, "candidates_token_count") + _count(
            response, "thoughts_token_count"
        )
        try:
            data = _parse(response)
        except ProviderError as error:
            raise ProviderError(error.code, input_tokens, output_tokens) from None
        return ProviderResult(
            data=data, model=self.model, input_tokens=input_tokens, output_tokens=output_tokens
        )


def _parse(response):
    try:
        if response.candidates[0].finish_reason != types.FinishReason.STOP:
            raise ValueError
        return json.loads(response.text)
    except (IndexError, TypeError, ValueError, AttributeError):
        raise ProviderError("INVALID_OUTPUT") from None


def _count(response, field: str) -> int:
    return getattr(response.usage_metadata, field, None) or 0


def get_provider() -> GeminiProvider:
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        raise ProviderError("PROVIDER_ERROR")
    client = genai.Client(api_key=key, http_options=types.HttpOptions(timeout=TIMEOUT_MILLISECONDS))
    return GeminiProvider(client, os.environ.get("GEMINI_MODEL") or DEFAULT_MODEL)
