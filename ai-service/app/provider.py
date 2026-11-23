from dataclasses import dataclass
from typing import Any, Protocol

from pydantic import BaseModel

ERROR_STATUS = {
    "RATE_LIMITED": 429,
    "PROVIDER_TIMEOUT": 504,
    "PROVIDER_ERROR": 502,
    "INVALID_OUTPUT": 502,
}


class ProviderError(Exception):
    """Carries only a safe code, never provider text that could quote a document."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code
        self.status = ERROR_STATUS[code]


@dataclass(frozen=True)
class ProviderResult:
    data: Any
    model: str
    input_tokens: int
    output_tokens: int


class AiProvider(Protocol):
    name: str

    def generate_json(self, system: str, document: str, schema: type[BaseModel]) -> ProviderResult: ...
